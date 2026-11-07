package verification

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Run the actual CI plan against offline GitHub responses, including pagination and reruns. */
@RunWith(Parameterized::class)
class SnapshotWorkflowTest(private val scenario: String, private val build: Boolean?, private val attempt: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any?>> = listOf(
            arrayOf("new", true, 1), arrayOf("unchanged", false, 1), arrayOf("paginated", false, 1),
            arrayOf("missing-apk", true, 1), arrayOf("missing-marker", true, 1), arrayOf("missing-diagnostics", true, 1),
            arrayOf("expired", true, 1), arrayOf("empty", true, 1), arrayOf("duplicate", true, 1), arrayOf("replaced-apk", true, 1),
            arrayOf("wrong-sha", true, 1), arrayOf("wrong-branch", true, 1), arrayOf("wrong-workflow", true, 1),
            arrayOf("failed", true, 1), arrayOf("fork", true, 1), arrayOf("pull-request-source", true, 1),
            arrayOf("branch", true, 1), arrayOf("pull-request", true, 1), arrayOf("manual", false, 1),
            arrayOf("partial-then-complete", false, 1), arrayOf("api-error", null, 1), arrayOf("artifact-api-error", null, 1),
            arrayOf("rerun", false, 2), arrayOf("rerun-failed", true, 2), arrayOf("rerun-earlier-success", false, 3),
        )
    }

    @Test fun snapshotPlanStateMachine() = temporary { temp ->
        val workflow = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve(".github/workflows/ci.yml") }.first(Files::isRegularFile).readText()
        val script = workflow.substringAfter("      - name: Plan snapshot\n").substringAfter("        run: |\n")
            .substringBefore("\n      - uses: actions/setup-java@v5").trimIndent()
        check(script.startsWith("set -euo pipefail"))
        command("git", "init", "-b", "main", temp)
        command("git", "-C", temp, "-c", "user.name=Offline Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "Snapshot fixture")
        val sha = command("git", "-C", temp, "rev-parse", "HEAD").trim()
        val classpath = listOf(SnapshotWorkflowTest::class.java, Options::class.java, Unit::class.java, groovy.json.JsonOutput::class.java, groovy.json.JsonSlurper::class.java, groovy.lang.GroovyObject::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(File.pathSeparator)
        fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val bin = Files.createDirectories(temp.resolve("bin"))
        bin.resolve("gh").apply {
            writeText("#!/bin/sh\nexec ${quote(Path.of(System.getProperty("java.home"), "bin/java").toString())} -cp ${quote(classpath)} verification.MockSnapshotGitHub \"${'$'}@\"\n")
            check(toFile().setExecutable(true, true))
        }
        val output = temp.resolve("output").apply { writeText("") }
        val capture = temp.resolve("log")
        val process = ProcessBuilder("bash", "-c", script).directory(temp.toFile()).redirectErrorStream(true).redirectOutput(capture.toFile()).apply {
            environment().apply {
                this["PATH"] = bin.toString() + File.pathSeparator + getValue("PATH")
                this["MOCK_SCENARIO"] = scenario
                this["SOURCE_SHA"] = sha
                this["GITHUB_REPOSITORY"] = "example/EhViewer"
                this["GITHUB_RUN_ID"] = "900"
                this["GITHUB_RUN_ATTEMPT"] = attempt.toString()
                this["GITHUB_REF"] = if (scenario == "branch") "refs/heads/feature/test" else "refs/heads/main"
                this["GITHUB_EVENT_NAME"] = when (scenario) {
                    "pull-request" -> "pull_request"
                    "manual" -> "workflow_dispatch"
                    else -> "push"
                }
                this["GITHUB_OUTPUT"] = output.toString()
                this["RUNNER_TEMP"] = temp.toString()
            }
        }.start()
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            error("Snapshot test timed out")
        }
        if (build == null) {
            assertTrue(capture.readText(), process.exitValue() != 0)
            assertEquals("", output.readText())
        } else {
            assertEquals(capture.readText(), 0, process.exitValue())
            val artifactRun = if (build || scenario.startsWith("rerun")) 900 else 101
            assertEquals("build=$build\nartifact_run_id=$artifactRun\n", output.readText())
        }
    }
}

object MockSnapshotGitHub {
    @JvmStatic fun main(args: Array<String>) {
        val scenario = System.getenv("MOCK_SCENARIO")
        check(scenario !in setOf("branch", "pull-request")) { "Untrusted checks must not query snapshot history" }
        check(args.first() == "api")
        val endpoint = args.first { it.startsWith("repos/") }
        val sha = System.getenv("SOURCE_SHA")
        fun fail(): Nothing {
            System.err.println("gh: Offline API error (HTTP 403)")
            exitProcess(1)
        }
        fun run(id: Int = 101, success: Boolean = true): Map<String, Any?> = mapOf(
            "id" to id,
            "head_sha" to if (scenario == "wrong-sha") "f".repeat(40) else sha,
            "head_branch" to if (scenario == "wrong-branch") "feature/test" else "main",
            "event" to if (scenario == "pull-request-source") "pull_request" else "push",
            "conclusion" to if (!success || scenario == "failed") "failure" else "success",
            "path" to if (scenario == "wrong-workflow") ".github/workflows/other.yml" else ".github/workflows/ci.yml",
            "head_repository" to mapOf("full_name" to if (scenario == "fork") "other/EhViewer" else "example/EhViewer"),
        )
        val result: Any = when {
            "/workflows/ci.yml/runs?" in endpoint -> {
                if (scenario == "api-error") fail()
                check("--paginate" in args && "--slurp" in args && "head_sha=$sha" in endpoint)
                val runs = if (scenario == "new" || scenario.startsWith("rerun")) {
                    emptyList()
                } else if (scenario == "partial-then-complete") {
                    listOf(run(102), run())
                } else {
                    listOf(run())
                }
                if (scenario == "paginated") listOf(mapOf("workflow_runs" to emptyList<Any>()), mapOf("workflow_runs" to runs)) else listOf(mapOf("workflow_runs" to runs))
            }
            "/attempts/" in endpoint -> run(900, scenario != "rerun-failed" && !(scenario == "rerun-earlier-success" && endpoint.endsWith("/2")))
            "/artifacts?" in endpoint -> {
                if (scenario == "artifact-api-error") fail()
                check("--paginate" in args && "--slurp" in args)
                var names = listOf("snapshot-$sha-2-3-4-5-6", "snapshot-diagnostics-$sha") + listOf("arm64-v8a", "armeabi-v7a", "universal", "x86_64").map { "snapshot-$sha-$it" }
                names = when (scenario) {
                    "missing-apk" -> names.dropLast(1)
                    "missing-marker" -> names.drop(1)
                    "missing-diagnostics" -> names.filterNot { "diagnostics" in it }
                    "duplicate" -> names + names.first()
                    "partial-then-complete" -> if ("/102/" in endpoint) emptyList() else names
                    else -> names
                }
                listOf(mapOf("artifacts" to names.mapIndexed { i, name -> mapOf("id" to if (scenario == "replaced-apk" && i == 2) 999 else i + 1, "name" to name, "expired" to (scenario == "expired" && i == 0), "size_in_bytes" to if (scenario == "empty" && i == 0) 0 else 100) }))
            }
            else -> error("Unexpected API endpoint")
        }
        print(json(result))
    }
}
