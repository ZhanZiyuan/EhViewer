package verification

import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

val stagedUpstreamJobs = setOf(
    "android", "ci/ios", "ci/source-policy", "ci/shared-kmp", "ci/rust-host (linux-x86_64)", "ci/rust-host (macos-arm64)",
    "ci/desktop (macos-x86_64-rosetta)", "ci/desktop (macos-x86_64-native)", "ci/desktop (linux-aarch64-runtime)", "ci/rust-native (windows-arm64-core)",
)

data class StagedArtifact(val role: String, val metadata: Map<String, Any?>, val catalog: Map<String, Any?>, val run: Map<String, Any?>) {
    val id get() = (metadata["id"] as Number).toLong()
    val runId get() = (run["id"] as Number).toLong()
}

/** Bound independent reads; any failure cancels and joins the other owned workers. */
fun <T> stagedDownloads(items: List<T>, action: (T) -> Unit) {
    require(items.isNotEmpty()) { "Expected actual artifact downloads" }
    val workers = Executors.newFixedThreadPool(minOf(4, items.size))
    val completed = ExecutorCompletionService<Unit>(workers)
    try {
        items.forEach { item -> completed.submit(Callable { action(item) }) }
        repeat(items.size) {
            try {
                completed.take().get()
            } catch (failure: ExecutionException) {
                throw requireNotNull(failure.cause)
            }
        }
    } finally {
        workers.shutdownNow()
        check(workers.awaitTermination(30, TimeUnit.SECONDS)) { "Owned artifact workers did not stop" }
    }
}

/** Preserve same-SHA main snapshot reuse; feature and PR artifacts must come from this run. */
fun stagedArtifactPlan(context: Map<String, Any?>, source: String, repository: String): List<StagedArtifact> {
    require(repository == "ZhanZiyuan/EhViewer" && Regex("[0-9a-f]{40}").matches(source) && source != "0".repeat(40)) { "Unexpected target repository/source" }
    fun run(value: Map<String, Any?>) {
        require((value["id"] as Number).toLong() > 0 && value["repository"].obj()["full_name"] == repository && value["head_repository"].obj()["full_name"] == repository && value["path"] == ".github/workflows/ci.yml") { "Expected an actual target CI run" }
        require(value["event"] in setOf("push", "pull_request", "workflow_dispatch") && Regex("[0-9a-f]{40}").matches(value.str("head_sha")) && (value["run_attempt"] as Number).toInt() > 0) { "Unexpected CI event/head/attempt" }
    }
    val current = context["run"].obj().also(::run)
    require(current["event"] == "pull_request" || current["head_sha"] == source) { "Push/manual source differs from its actual head" }
    val history = context["jobs"].obj()["jobs"].array().map(Any?::obj)
    require((context["jobs"].obj()["total_count"] as Number).toInt() == history.size) { "Incomplete upstream job catalog" }
    val attempt = (current["run_attempt"] as Number).toInt()
    require(history.all { (it["run_id"] as Number).toLong() == (current["id"] as Number).toLong() && (it["run_attempt"] as Number).toInt() in 1..attempt }) { "Cross-run or future-attempt upstream jobs" }
    require(history.map { it.str("name") }.toSet() - stagedUpstreamJobs - setOf("ci/gate", "ci/release-dry-run") == emptySet<String>()) { "Undeclared staging dependency" }
    // A failed-job rerun retains successful same-source producers from earlier attempts.
    // Always use each job's latest result; an old success cannot mask a new failure.
    val jobs = history.groupBy { it.str("name") }.map { (_, versions) ->
        require(versions.map { it["run_attempt"] }.toSet().size == versions.size) { "Ambiguous upstream jobs within one attempt" }
        versions.maxBy { (it["run_attempt"] as Number).toInt() }
    }
    stagedUpstreamJobs.forEach { name ->
        val job = jobs.single { it["name"] == name }
        require(job["status"] == "completed" && job["conclusion"] == "success") { "Every integrated upstream job must actually succeed: $name" }
    }
    val dryRun = jobs.singleOrNull { it["name"] == "ci/release-dry-run" }
    val activeDryRun = dryRun?.get("status") == "in_progress" && dryRun["conclusion"] == null && (dryRun["run_attempt"] as Number).toInt() == attempt
    // The run-level API can still say queued while this actual job is running.
    // Completed failures stay rejected; queued alone never establishes readiness.
    val ready = when (current["status"]) {
        "completed" -> current["conclusion"] == "success"
        "in_progress" -> current["conclusion"] == null
        "queued", "waiting" -> current["conclusion"] == null && activeDryRun
        else -> false
    }
    require(ready) { "Staging needs a successful run or an active same-attempt dry-run after every upstream success: status=${current["status"]}, conclusion=${current["conclusion"]}, attempt=$attempt" }
    val android = context["android_run"].obj().also(::run)
    val reused = android["id"] != current["id"]
    val trustedMain = current["head_branch"] == "main" && current["event"] in setOf("push", "workflow_dispatch") && current["head_sha"] == source
    if (reused) {
        require(trustedMain && android["head_branch"] == "main" && android["event"] == "push" && android["head_sha"] == source && android["status"] == "completed" && android["conclusion"] == "success") { "Only a successful same-SHA main push may supply the existing snapshot" }
    } else {
        require(android == current) { "Current Android run metadata differs" }
    }
    fun catalog(value: Map<String, Any?>): List<Map<String, Any?>> {
        val entries = value["artifacts"].array().map(Any?::obj)
        require((value["total_count"] as Number).toInt() == entries.size && entries.map { it["id"] }.toSet().size == entries.size && entries.map { it["name"] }.toSet().size == entries.size) { "Incomplete or ambiguous official artifact catalog" }
        return entries
    }
    val currentCatalog = context["catalog"].obj()
    val androidCatalog = context["android_catalog"].obj()
    require(reused || androidCatalog == currentCatalog) { "Same-run Android catalog differs from the actual current catalog" }
    val available = catalog(currentCatalog)
    val androidAvailable = catalog(androidCatalog)
    val native = linkedMapOf(
        "policy" to "source-policy", "phase" to "migration-phase", "shared" to "shared-core", "linux-host" to "desktop-native-linux-x86_64", "macos-host" to "desktop-native-macos-arm64",
        "linux-runtime" to "linux-arm64-runtime", "ios" to "ios-startup", "apple-rust" to "ios-rust-build", "intel-producer" to "intel-dmg-on-arm", "intel-consumer" to "intel-dmg-native",
        "domain-native" to "domain-native",
        "common-native" to "common-native",
        "windows-core" to "windows-arm64-core", "windows-jvm" to "windows-arm64-jvm", "windows-runtime" to "windows-arm64-runtime", "windows-package" to "windows-arm64-package", "windows-launch" to "windows-arm64-zip-launch",
    )
    val result = native.map { (role, prefix) -> StagedArtifact(role, available.single { it["name"] == "$prefix-$source" }, currentCatalog, current) }.toMutableList()
    val apks = listOf("arm64-v8a", "armeabi-v7a", "universal", "x86_64").map { abi -> StagedArtifact("apk-$abi", androidAvailable.single { it["name"] == "snapshot-$source-$abi" }, androidCatalog, android) }
    val diagnostics = if (trustedMain) StagedArtifact("encrypted-diagnostics", androidAvailable.single { it["name"] == "snapshot-diagnostics-$source" }, androidCatalog, android) else null
    val marker = "snapshot-$source-${diagnostics?.id ?: 0}-${apks.joinToString("-") { it.id.toString() }}"
    result += apks
    result += StagedArtifact("snapshot", androidAvailable.single { it["name"] == marker }, androidCatalog, android)
    if (!reused) result += StagedArtifact("android-reports", available.single { it["name"] == "verification-reports" }, currentCatalog, current)
    if (diagnostics != null) result += diagnostics
    val selectedCurrentNames = result.filter { it.runId == (current["id"] as Number).toLong() }.map { it.metadata.str("name") }.toSet()
    // The tested verifier is shared by jobs; it is tooling, not a release candidate input.
    require(available.map { it.str("name") }.toSet() - setOf("staged-dry-run-$source", "verification-tools-$source") == selectedCurrentNames) { "Missing or unexpected current-run artifact" }
    available.singleOrNull { it["name"] == "verification-tools-$source" }?.let { artifact ->
        require(
            artifact["expired"] == false && artifact["workflow_run"].obj()["head_sha"] == current["head_sha"] &&
                (artifact["workflow_run"].obj()["id"] as Number).toLong() == (current["id"] as Number).toLong(),
        ) { "Shared verifier must belong to this run/source" }
    }
    result.forEach { artifact ->
        val owner = artifact.metadata["workflow_run"].obj()
        require((owner["id"] as Number).toLong() == artifact.runId && owner["head_sha"] == artifact.run["head_sha"] && artifact.metadata["expired"] == false) { "Selected artifact provenance changed or expired" }
    }
    require(result.map { it.id }.toSet().size == result.size) { "The selected run set repeats an artifact identity" }
    return result
}

/** Read-only GitHub acquisition; actual byte validation remains the shared inspector's job. */
fun fetchStagedInputs(repository: String, runId: Long, androidRunId: Long, output: Path): Path {
    require(repository == "ZhanZiyuan/EhViewer" && runId > 0 && androidRunId > 0 && !output.exists()) { "Expected target CI identities and a fresh input directory" }
    val source = command("git", "rev-parse", "HEAD").trim()
    fun api(suffix: String): Map<String, Any?> {
        var last: CommandFailure? = null
        repeat(3) {
            try {
                return JsonSlurper().parseText(command("gh", "api", "repos/$repository/$suffix")).obj()
            } catch (failure: CommandFailure) {
                last = failure
                if (Regex("HTTP (401|403|404)").containsMatchIn(failure.output)) throw failure
            }
        }
        throw requireNotNull(last)
    }
    val current = api("actions/runs/$runId")
    if (System.getenv("GITHUB_ACTIONS") == "true") require(System.getenv("GITHUB_REPOSITORY") == repository && System.getenv("GITHUB_RUN_ID") == runId.toString() && System.getenv("GITHUB_RUN_ATTEMPT") == current["run_attempt"].toString() && System.getenv("GITHUB_SHA") == source) { "CI must inspect its own actual run/source" }
    val android = if (androidRunId == runId) current else api("actions/runs/$androidRunId")
    val currentCatalog = api("actions/runs/$runId/artifacts?per_page=100")
    val context = mapOf(
        "repository" to repository,
        "run" to current,
        "catalog" to currentCatalog,
        "jobs" to api("actions/runs/$runId/jobs?filter=all&per_page=100"),
        "android_run" to android,
        "android_catalog" to if (androidRunId == runId) currentCatalog else api("actions/runs/$androidRunId/artifacts?per_page=100"),
    )
    Files.createDirectory(output)
    output.resolve("context.json").writeText(json(context))
    val plan = stagedArtifactPlan(context, source, repository)
    val archives = Files.createDirectory(output.resolve("archives"))
    stagedDownloads(plan) { artifact ->
        val archive = archives.resolve("${artifact.id}.zip")
        var downloaded = false
        for (attempt in 1..3) {
            val partial = archives.resolve("${artifact.id}-attempt-$attempt.zip")
            val errors = archives.resolve("${artifact.id}-attempt-$attempt.stderr.log")
            val process = ProcessBuilder("gh", "api", "repos/$repository/actions/artifacts/${artifact.id}/zip").redirectOutput(partial.toFile()).redirectError(errors.toFile()).start()
            try {
                val finished = process.waitFor(10, TimeUnit.MINUTES)
                if (finished && process.exitValue() == 0) {
                    // inspectArtifactArchive performs the authoritative digest/CRC/payload pass once.
                    require(Files.size(partial) == (artifact.metadata["size_in_bytes"] as Number).toLong()) { "Incomplete downloaded artifact" }
                    Files.move(partial, archive)
                    downloaded = true
                    break
                }
                if (Regex("HTTP (401|403|404)").containsMatchIn(errors.readText())) break
            } finally {
                if (process.isAlive) {
                    process.descendants().use { it.toList().asReversed().forEach { child -> child.destroyForcibly() } }
                    process.destroyForcibly().waitFor()
                }
            }
        }
        require(downloaded) { "Artifact ${artifact.id} download failed; retained attempts are in $archives" }
    }
    return output
}
