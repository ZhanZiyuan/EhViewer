package verification

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.writeText

private val ciJobs = setOf("source_policy", "shared_kmp", "android", "ios", "rust_host", "desktop_intel", "desktop_intel_native", "desktop_linux_arm64", "windows_native_core", "release_dry_run")
private val fastJobs = setOf("source_policy", "shared_kmp", "android")

/** Classify the entire change since a successful PR run; unknown paths fail closed to full CI. */
fun ciPlan(policy: Map<String, Any?>, changed: List<String>, source: String, head: String, baseline: String, forceFull: Boolean): Map<String, Any?> {
    require(listOf(source, head, baseline).all { Regex("[0-9a-f]{40}").matches(it) && it != "0".repeat(40) })
    require(changed.distinct().size == changed.size && changed.all { it.isNotBlank() && !it.contains('\n') && !it.contains('\r') && !Path.of(it).isAbsolute && Path.of(it).normalize().toString() == it && !it.startsWith("../") })
    require(policy["schema_version"] == 1 && policy["fast_jobs"].array().toSet() == fastJobs)
    val all = policy["full_jobs"].array().map { it as String }
    require(all.size == ciJobs.size && all.toSet() == ciJobs)
    val dependencies = policy["dependencies"].obj().mapValues { (_, value) -> value.array().map { it as String } }
    require(dependencies.all { (job, parents) -> job in ciJobs && parents.all { it in ciJobs && it != job } })
    val groups = policy["platform_groups"].obj().mapValues { (_, value) -> value.obj() }
    require(groups.isNotEmpty() && groups.values.all { group -> group["jobs"].array().isNotEmpty() && group["jobs"].array().all { it in ciJobs } })
    fun matches(patterns: Any?, name: String) = patterns.array().any { Regex(it as String).containsMatchIn(name) }
    val selected = fastJobs.toMutableSet()
    val reasons = linkedMapOf<String, String>()
    var full = forceFull
    for (name in changed) {
        val matched = groups.filterValues { matches(it["patterns"], name) }
        val reason = when {
            matches(policy["full_patterns"], name) -> "full: build, ABI, release, policy or milestone change".also { full = true }
            matched.isNotEmpty() -> "platform: ${matched.keys.sorted().joinToString()}".also { matched.values.forEach { selected += it["jobs"].array().map { job -> job as String } } }
            matches(policy["fast_patterns"], name) -> "fast: Android or documentation change"
            else -> "full: unclassified path".also { full = true }
        }
        reasons[name] = reason
    }
    if (full) selected += ciJobs
    // Bound closure also rejects a cyclic declaration rather than guessing an execution order.
    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()
    fun visit(job: String) {
        require(job !in visiting) { "Cyclic CI dependencies" }
        if (!visited.add(job)) return
        visiting += job
        dependencies[job].orEmpty().forEach {
            selected += it
            visit(it)
        }
        visiting -= job
    }
    selected.toList().forEach(::visit)
    val tier = if (full) {
        "full"
    } else if (selected == fastJobs) {
        "fast"
    } else {
        "platform"
    }
    return mapOf(
        "schema_version" to 1, "source_commit" to source, "head_commit" to head, "baseline_commit" to baseline,
        "tier" to tier, "force_full" to forceFull, "changed_paths" to reasons,
        "jobs" to all.associateWith { job -> mapOf("required" to (job in selected), "reason" to if (job in selected) "Required by $tier plan" else "Not affected by the declared change set") },
        "production_publication" to false,
    )
}

/** A skipped required job is a failure, even though GitHub normally treats skipped checks as passing. */
fun ciGate(plan: Map<String, Any?>, actual: Map<String, Any?>, source: String): Map<String, Any?> {
    require(plan["schema_version"] == 1 && plan["source_commit"] == source && plan["tier"] in setOf("fast", "platform", "full"))
    val jobs = plan["jobs"].obj()
    require(jobs.keys == ciJobs && actual.keys == ciJobs) { "Missing or undeclared CI result" }
    require(fastJobs.all { jobs[it].obj()["required"] == true }) { "Android and shared regressions are always mandatory" }
    require(plan["tier"] != "full" || jobs.values.all { it.obj()["required"] == true })
    val results = jobs.mapValues { (job, declaration) ->
        val required = declaration.obj()["required"]
        require(required is Boolean)
        val result = actual[job].obj().str("result")
        require(result == "success" || !required && result == "skipped") { "$job: $result cannot satisfy required=$required" }
        mapOf("required" to required, "result" to result, "reason" to declaration.obj().str("reason"))
    }
    return mapOf("source_commit" to source, "head_commit" to plan["head_commit"], "baseline_commit" to plan["baseline_commit"], "tier" to plan["tier"], "jobs" to results, "result" to "PASS", "production_publication" to false)
}

fun writeCiPlan(): Map<String, Any?> {
    val env = System.getenv()
    val source = command("git", "rev-parse", "HEAD").trim()
    require(env["GITHUB_SHA"] == source && env["GITHUB_REPOSITORY"] == "ZhanZiyuan/EhViewer")
    val event = readJson(Path.of(requireNotNull(env["GITHUB_EVENT_PATH"])))
    val pr = event["pull_request"]?.obj()
    val head = pr?.get("head")?.obj()?.str("sha") ?: source
    var baseline = pr?.get("base")?.obj()?.str("sha") ?: source
    var priorSuccess = false
    if (pr != null) {
        val number = (event["number"] as Number).toLong()
        val runs = JsonSlurper().parseText(command("gh", "api", "repos/ZhanZiyuan/EhViewer/actions/workflows/ci.yml/runs?event=pull_request&status=success&per_page=100")).obj()["workflow_runs"].array()
        // Only a successful run belonging to this PR, with a reachable head, can narrow coverage.
        for (value in runs) {
            val run = value.obj()
            if (run["path"] != ".github/workflows/ci.yml" || run["conclusion"] != "success" || run["head_repository"].obj()["full_name"] != "ZhanZiyuan/EhViewer") continue
            if (run["pull_requests"].array().none { (it.obj()["number"] as Number).toLong() == number }) continue
            val candidate = run.str("head_sha")
            if (candidate == head) continue
            val reachable = command("git", "merge-base", candidate, head).trim() == candidate
            if (reachable) {
                baseline = candidate
                priorSuccess = true
                break
            }
        }
    }
    // Include base-branch changes in the actual tested merge, not only the feature branch head.
    val changed = command("git", "diff", "--name-only", "--no-renames", "-z", baseline, source).split('\u0000').filter(String::isNotEmpty)
    val force = !priorSuccess || env["GITHUB_REF"] == "refs/heads/main" || event["inputs"]?.obj()?.get("full") in setOf(true, "true")
    val plan = ciPlan(readJson(root.resolve(".github/ci-plan.json")), changed, source, head, baseline, force)
    val build = root.resolve("tools/verification/build")
    build.resolve("ci-plan.json").writeText(json(plan))
    Path.of(requireNotNull(env["GITHUB_OUTPUT"])).appendText("plan=${JsonOutput.toJson(plan)}\ntier=${plan["tier"]}\n")
    Path.of(requireNotNull(env["GITHUB_STEP_SUMMARY"])).appendText("### CI plan: ${plan["tier"]}\n\nSource: `$source`  \nHead: `$head`  \nBaseline: `$baseline`\n\n${changed.size} changed paths; Android critical regressions are mandatory.\n")
    return plan
}

fun verifyCiGate(): Map<String, Any?> {
    val plan = readJson(root.resolve("tools/verification/build/ci-plan.json"))
    val declared = JsonSlurper().parseText(requireNotNull(System.getenv("CI_PLAN"))).obj()
    require(plan == declared) { "Downloaded plan differs from the source job output" }
    val recalculated = ciPlan(
        readJson(root.resolve(".github/ci-plan.json")),
        plan["changed_paths"].obj().keys.toList(),
        plan.str("source_commit"),
        plan.str("head_commit"),
        plan.str("baseline_commit"),
        plan["force_full"] == true,
    )
    require(plan == recalculated) { "Plan differs from the declared path policy" }
    val actual = JsonSlurper().parseText(requireNotNull(System.getenv("CI_RESULTS"))).obj()
    val source = command("git", "rev-parse", "HEAD").trim()
    val result = ciGate(plan, actual, source)
    System.getenv("GITHUB_STEP_SUMMARY")?.let { location ->
        Path.of(location).appendText(
            "### ci/gate: PASS (${plan["tier"]})\n\nSource: `$source`\n\n| Job | Required | Actual result |\n| --- | --- | --- |\n" + result["jobs"].obj().entries.joinToString("\n") { (job, value) ->
                val row = value.obj()
                "| $job | ${row["required"]} | ${row["result"]} |"
            } + "\n\n[Actions run](https://github.com/ZhanZiyuan/EhViewer/actions/runs/${System.getenv("GITHUB_RUN_ID")})\n",
        )
    }
    return result
}
