package verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Routing fixtures only. Actual package bytes and platform execution are validated separately. */
class StagedArtifactInputsTest {
    private val source = "1".repeat(40)
    private val repository = "ZhanZiyuan/EhViewer"
    private fun rejects(block: () -> Unit) = assertNotNull(runCatching(block).exceptionOrNull())
    private fun fixture(branch: String = "feature/kmp", event: String = "push"): Map<String, Any?> {
        val head = if (event == "pull_request") "2".repeat(40) else source
        val run = mapOf("id" to 100, "run_attempt" to 1, "head_sha" to head, "head_branch" to branch, "event" to event, "status" to "completed", "conclusion" to "success", "path" to ".github/workflows/ci.yml", "repository" to mapOf("full_name" to repository), "head_repository" to mapOf("full_name" to repository))
        val prefixes = listOf("source-policy", "migration-phase", "shared-core", "desktop-native-linux-x86_64", "desktop-native-macos-arm64", "linux-arm64-runtime", "ios-startup", "ios-rust-build", "intel-dmg-on-arm", "intel-dmg-native", "windows-arm64-core", "windows-arm64-jvm", "windows-arm64-runtime", "windows-arm64-package", "windows-arm64-zip-launch", "domain-native", "common-native")
        var id = 200
        fun artifact(name: String): Map<String, Any?> = mapOf("id" to ++id, "name" to name, "expired" to false, "workflow_run" to mapOf("id" to 100, "head_sha" to head))
        val artifacts = prefixes.map { artifact("$it-$source") }.toMutableList()
        val apks = listOf("arm64-v8a", "armeabi-v7a", "universal", "x86_64").map { artifact("snapshot-$source-$it") }
        artifacts += apks
        val diagnostics = if (branch == "main" && event != "pull_request") artifact("snapshot-diagnostics-$source").also { artifacts += it } else null
        artifacts += artifact("snapshot-$source-${diagnostics?.get("id") ?: 0}-${apks.joinToString("-") { it["id"].toString() }}")
        artifacts += artifact("verification-reports")
        val catalog = mapOf("total_count" to artifacts.size, "artifacts" to artifacts)
        val jobs = (stagedUpstreamJobs + setOf("ci/gate", "ci/release-dry-run")).map { mapOf("name" to it, "run_id" to 100, "run_attempt" to 1, "status" to "completed", "conclusion" to "success") }
        return mapOf("repository" to repository, "run" to run, "android_run" to run, "catalog" to catalog, "android_catalog" to catalog, "jobs" to mapOf("total_count" to jobs.size, "jobs" to jobs))
    }

    @Test fun featureAndPrSelectExactly23CurrentRunArtifactsWithoutConfusingMergeSource() {
        assertEquals(setOf("android", "ci/ios", "ci/source-policy", "ci/shared-kmp", "ci/rust-host (linux-x86_64)", "ci/rust-host (macos-arm64)", "ci/desktop (macos-x86_64-rosetta)", "ci/desktop (macos-x86_64-native)", "ci/desktop (linux-aarch64-runtime)", "ci/rust-native (windows-arm64-core)"), stagedUpstreamJobs)
        for (event in listOf("push", "pull_request")) {
            val context = fixture(event = event)
            val plan = stagedArtifactPlan(context, source, repository)
            assertEquals(23, plan.size)
            assertEquals(23, plan.map { it.id }.toSet().size)
            assertTrue(plan.all { it.runId == 100L })
            assertEquals(4, plan.count { it.role.startsWith("apk-") })
            assertFalse(plan.any { it.role == "encrypted-diagnostics" })
            if (event == "pull_request") assertTrue(plan.all { it.run["head_sha"] == "2".repeat(40) })
        }
    }

    @Test fun onlySameRunSourceBoundSharedVerifierMayAccompanyActualPackageInputs() {
        val context = fixture(event = "pull_request")
        val catalog = context["catalog"].obj()
        val entries = catalog["artifacts"].array()
        val tool = entries.first().obj() + mapOf("id" to 999, "name" to "verification-tools-$source")
        fun changed(value: Map<String, Any?>): Map<String, Any?> {
            val updated = catalog + mapOf("total_count" to entries.size + 1, "artifacts" to (entries + value))
            return context + mapOf("catalog" to updated, "android_catalog" to updated)
        }
        assertEquals(23, stagedArtifactPlan(changed(tool), source, repository).size)
        rejects { stagedArtifactPlan(changed(tool + ("expired" to true)), source, repository) }
        rejects { stagedArtifactPlan(changed(tool + ("workflow_run" to mapOf("id" to 99, "head_sha" to "2".repeat(40)))), source, repository) }
        rejects { stagedArtifactPlan(changed(tool + ("workflow_run" to mapOf("id" to 100, "head_sha" to "3".repeat(40)))), source, repository) }
        rejects { stagedArtifactPlan(changed(tool + ("name" to "verification-tools-undeclared")), source, repository) }
    }

    @Test fun everyMissingFailedCancelledSkippedNeutralOrPendingUpstreamIsRejected() {
        val context = fixture()
        val jobs = context["jobs"].obj()["jobs"].array().map(Any?::obj)
        for (name in stagedUpstreamJobs) {
            val missing = jobs.filterNot { it["name"] == name }
            rejects { stagedArtifactPlan(context + ("jobs" to mapOf("total_count" to missing.size, "jobs" to missing)), source, repository) }
            for (result in listOf("failure", "cancelled", "skipped", "neutral", "pending", "")) {
                val changed = jobs.map { if (it["name"] == name) it + ("conclusion" to result) else it }
                rejects { stagedArtifactPlan(context + ("jobs" to mapOf("total_count" to changed.size, "jobs" to changed)), source, repository) }
            }
        }
    }

    @Test fun laggingQueuedRunNeedsEveryUpstreamSuccessAndAnActualCurrentAttemptDryRun() {
        val context = fixture(event = "pull_request")
        val original = context["run"].obj()
        val jobs = context["jobs"].obj()["jobs"].array().map(Any?::obj)
        fun changed(status: String, conclusion: String? = null, jobStatus: String = "in_progress", jobAttempt: Int = 1): Map<String, Any?> {
            val run = original + mapOf("status" to status, "conclusion" to conclusion)
            val active = jobs.map { if (it["name"] == "ci/release-dry-run") it + mapOf("status" to jobStatus, "conclusion" to null, "run_attempt" to jobAttempt) else it }
            return context + mapOf("run" to run, "android_run" to run, "jobs" to mapOf("total_count" to active.size, "jobs" to active))
        }
        for (status in listOf("queued", "waiting", "in_progress")) assertEquals(23, stagedArtifactPlan(changed(status), source, repository).size)
        for (status in listOf("queued", "waiting")) {
            for (jobStatus in listOf("queued", "completed", "cancelled")) rejects { stagedArtifactPlan(changed(status, jobStatus = jobStatus), source, repository) }
            rejects { stagedArtifactPlan(changed(status, jobAttempt = 2), source, repository) }
        }
        for (status in listOf("completed", "in_progress", "queued", "waiting", "requested", "unknown")) {
            for (conclusion in listOf("failure", "cancelled", "skipped", "neutral")) rejects { stagedArtifactPlan(changed(status, conclusion), source, repository) }
        }
        rejects { stagedArtifactPlan(changed("completed"), source, repository) }
        val incomplete = changed("queued")
        val missing = incomplete["jobs"].obj()["jobs"].array().drop(1)
        rejects { stagedArtifactPlan(incomplete + ("jobs" to mapOf("total_count" to missing.size, "jobs" to missing)), source, repository) }
    }

    @Test fun failedJobRerunsReuseOnlyTheLatestActualResultOfEverySameSourceProducer() {
        val context = fixture(event = "pull_request")
        val current = context["run"].obj() + mapOf("run_attempt" to 2, "status" to "queued", "conclusion" to null)
        val previous = context["jobs"].obj()["jobs"].array().map(Any?::obj)
        val active = previous.single { it["name"] == "ci/release-dry-run" } + mapOf("run_attempt" to 2, "status" to "in_progress", "conclusion" to null)
        fun changed(extra: List<Map<String, Any?>>): Map<String, Any?> {
            val history = previous + active + extra
            return context + mapOf("run" to current, "android_run" to current, "jobs" to mapOf("total_count" to history.size, "jobs" to history))
        }
        assertEquals(23, stagedArtifactPlan(changed(emptyList()), source, repository).size)
        for (name in stagedUpstreamJobs) {
            val rerun = previous.single { it["name"] == name } + ("run_attempt" to 2)
            assertEquals(23, stagedArtifactPlan(changed(listOf(rerun)), source, repository).size)
            for (conclusion in listOf("failure", "cancelled", "skipped", "neutral", null)) {
                rejects { stagedArtifactPlan(changed(listOf(rerun + ("conclusion" to conclusion))), source, repository) }
            }
        }
        rejects { stagedArtifactPlan(changed(listOf(active)), source, repository) }
        rejects { stagedArtifactPlan(changed(listOf(active + ("run_attempt" to 3))), source, repository) }
        rejects { stagedArtifactPlan(changed(listOf(active + ("run_id" to 99))), source, repository) }
        val stale = changed(emptyList())
        val history = previous + (active + ("run_attempt" to 1))
        rejects { stagedArtifactPlan(stale + ("jobs" to mapOf("total_count" to history.size, "jobs" to history)), source, repository) }
    }

    private fun observedPendingIos(): Map<String, Any?> {
        val context = fixture(event = "pull_request")
        val run = context["run"].obj() + mapOf("status" to "queued", "conclusion" to null)
        val jobs = context["jobs"].obj()["jobs"].array().map { value ->
            val job = value.obj()
            when (job["name"]) {
                "ci/ios" -> job + mapOf("status" to "in_progress", "conclusion" to null)
                "ci/release-dry-run" -> job + mapOf("status" to "queued", "conclusion" to null)
                else -> job
            }
        }
        return context + mapOf("run" to run, "android_run" to run, "jobs" to mapOf("total_count" to jobs.size, "jobs" to jobs))
    }

    @Test fun recordedPendingIosObservationMustBecomeActualSuccessBeforeInputsAreAuthorized() {
        val pending = observedPendingIos()
        val iosReady = pending["jobs"].obj()["jobs"].array().map { value ->
            val job = value.obj()
            if (job["name"] == "ci/ios") job + mapOf("status" to "completed", "conclusion" to "success") else job
        }
        val waitingForSelf = pending + ("jobs" to mapOf("total_count" to iosReady.size, "jobs" to iosReady))
        val ready = fixture(event = "pull_request")
        var reads = 0
        var pauses = 0
        val result = awaitStagedMetadata(attempts = 3, pause = { pauses++ }, read = {
            when (++reads) {
                1 -> pending
                2 -> waitingForSelf
                else -> ready
            }
        }, validate = { stagedArtifactPlan(it, source, repository) })
        assertEquals(ready, result)
        assertEquals(3, reads)
        assertEquals(2, pauses)
    }

    @Test fun staleMetadataExhaustionAndElapsedDeadlineNeverBecomeSuccessfulPackages() {
        var reads = 0
        var pauses = 0
        assertThrows(StagedMetadataPending::class.java) {
            awaitStagedMetadata(attempts = 3, pause = { pauses++ }, read = {
                reads++
                observedPendingIos()
            }, validate = { stagedArtifactPlan(it, source, repository) })
        }
        assertEquals(3, reads)
        assertEquals(2, pauses)
        reads = 0
        var nanos = 0L
        assertThrows(StagedMetadataPending::class.java) {
            awaitStagedMetadata(now = { nanos }, pause = { nanos = java.time.Duration.ofMinutes(3).toNanos() }, read = {
                reads++
                observedPendingIos()
            }, validate = { stagedArtifactPlan(it, source, repository) })
        }
        assertEquals(1, reads)
    }

    @Test fun pollingNeverRetriesFailedCancelledSkippedNeutralOrInvalidProvenance() {
        val good = fixture(event = "pull_request")
        val original = good["run"].obj()
        val wrongRepository = original + ("head_repository" to mapOf("full_name" to "other/EhViewer"))
        val invalid = listOf("failure", "cancelled", "skipped", "neutral").map { result ->
            good + ("run" to original + ("conclusion" to result))
        } + listOf(good + ("run" to wrongRepository))
        for (context in invalid) {
            var reads = 0
            var pauses = 0
            val failure = assertThrows(IllegalArgumentException::class.java) {
                awaitStagedMetadata(pause = { pauses++ }, read = {
                    reads++
                    context
                }, validate = { stagedArtifactPlan(it, source, repository) })
            }
            assertFalse(failure is StagedMetadataPending)
            assertEquals(1, reads)
            assertEquals(0, pauses)
        }
        val pending = observedPendingIos()
        val failedJobs = pending["jobs"].obj()["jobs"].array().map { value ->
            val job = value.obj()
            if (job["name"] == "ci/ios") job + mapOf("status" to "completed", "conclusion" to "failure") else job
        }
        var reads = 0
        var pauses = 0
        assertThrows(IllegalArgumentException::class.java) {
            awaitStagedMetadata(pause = { pauses++ }, read = {
                if (++reads == 1) pending else pending + ("jobs" to mapOf("total_count" to failedJobs.size, "jobs" to failedJobs))
            }, validate = { stagedArtifactPlan(it, source, repository) })
        }
        assertEquals(2, reads)
        assertEquals(1, pauses)
    }

    @Test fun incompleteDuplicatedCrossRunAndUndeclaredJobListsFail() {
        val context = fixture()
        val jobs = context["jobs"].obj()["jobs"].array().map(Any?::obj)
        val variants = listOf(jobs.dropLast(1), jobs + jobs.first(), jobs.mapIndexed { i, value -> if (i == 0) value + ("run_id" to 99) else value }, jobs + (jobs.first() + ("name" to "undeclared")))
        variants.forEachIndexed { index, values ->
            val count = if (index == 0) jobs.size else values.size
            rejects { stagedArtifactPlan(context + ("jobs" to mapOf("total_count" to count, "jobs" to values)), source, repository) }
        }
    }

    @Test fun replacedApkIdsWrongOwnerAndExpiredSelectedArtifactsCannotUseTheOldMarker() {
        val context = fixture()
        val entries = context["catalog"].obj()["artifacts"].array().map(Any?::obj)
        val firstApk = entries.indexOfFirst { it["name"] == "snapshot-$source-arm64-v8a" }
        for (change in listOf(mapOf("id" to 999), mapOf("expired" to true), mapOf("workflow_run" to mapOf("id" to 99, "head_sha" to source)), mapOf("workflow_run" to mapOf("id" to 100, "head_sha" to "3".repeat(40))))) {
            val altered = entries.mapIndexed { i, value -> if (i == firstApk) value + change else value }
            val catalog = mapOf("total_count" to altered.size, "artifacts" to altered)
            rejects { stagedArtifactPlan(context + mapOf("catalog" to catalog, "android_catalog" to catalog), source, repository) }
        }
    }

    private fun reused(): Map<String, Any?> {
        val context = fixture(branch = "main")
        val current = context["run"].obj()
        val entries = context["catalog"].obj()["artifacts"].array().map(Any?::obj)
        val remaining = entries.filter { !it.str("name").startsWith("snapshot-") && it["name"] != "verification-reports" }
        val old = entries.map { entry ->
            entry + mapOf("workflow_run" to mapOf("id" to 99, "head_sha" to source), "expired" to (!entry.str("name").startsWith("snapshot-")))
        }
        return context + mapOf("android_run" to current + ("id" to 99), "catalog" to mapOf("total_count" to remaining.size, "artifacts" to remaining), "android_catalog" to mapOf("total_count" to old.size, "artifacts" to old))
    }

    @Test fun successfulSameShaMainSnapshotReuseDoesNotDependOnExpiredOldBuildReports() {
        val plan = stagedArtifactPlan(reused(), source, repository)
        assertEquals(23, plan.size)
        assertEquals(17, plan.count { it.runId == 100L })
        assertEquals(6, plan.count { it.runId == 99L })
        assertFalse(plan.any { it.role == "android-reports" })
        assertTrue(plan.any { it.role == "encrypted-diagnostics" && it.runId == 99L })
    }

    @Test fun featurePrFailedForkWrongSourceBranchOrManualCachedRunsCannotSupplyApks() {
        val context = reused()
        val old = context["android_run"].obj()
        for (change in listOf(mapOf("head_sha" to "3".repeat(40)), mapOf("head_branch" to "feature/kmp"), mapOf("conclusion" to "failure"), mapOf("event" to "workflow_dispatch"), mapOf("head_repository" to mapOf("full_name" to "other/EhViewer")))) {
            rejects { stagedArtifactPlan(context + ("android_run" to old + change), source, repository) }
        }
        for (change in listOf(mapOf("head_branch" to "feature/kmp"), mapOf("event" to "pull_request"))) {
            rejects { stagedArtifactPlan(context + ("run" to context["run"].obj() + change), source, repository) }
        }
    }

    @Test fun mismatchedSameRunCatalogUnexpectedArchivesAndTruncatedCatalogsFail() {
        val context = fixture()
        val catalog = context["catalog"].obj()
        rejects { stagedArtifactPlan(context + ("android_catalog" to catalog + ("total_count" to 999)), source, repository) }
        val entries = catalog["artifacts"].array().map(Any?::obj)
        val altered = entries + (entries.first() + mapOf("id" to 999, "name" to "unexpected"))
        val input = mapOf("total_count" to altered.size, "artifacts" to altered)
        rejects { stagedArtifactPlan(context + mapOf("catalog" to input, "android_catalog" to input), source, repository) }
        val truncated = catalog + ("total_count" to 999)
        rejects { stagedArtifactPlan(context + mapOf("catalog" to truncated, "android_catalog" to truncated), source, repository) }
    }
}
