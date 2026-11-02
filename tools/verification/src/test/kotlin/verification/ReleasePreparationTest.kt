package verification

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Execute the real prepare step with a temporary Git repository and an offline API substitute. */
@RunWith(Parameterized::class)
class ReleasePreparationTest(private val scenario: String, private val expectedError: String?) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<String?>> = listOf(
            arrayOf("stable", null),
            arrayOf("branch-dry-run", null),
            arrayOf("branch-dry-run-existing-tag", null),
            arrayOf("branch-dry-run-failed-ci", "Successful PR CI is required"),
            arrayOf("branch-dry-run-push-only", "Successful PR CI is required"),
            arrayOf("branch-dry-run-other-branch", "Dry-run branch is not authorized"),
            arrayOf("already-published", null),
            arrayOf("wrong-version", "Requested version differs"),
            arrayOf("manual-other-branch", "Manual publication must run from main"),
            arrayOf("unmerged", "Source is not merged into main"),
            arrayOf("failed-ci", "Successful main CI is required"),
            arrayOf("pr-only-ci", "Successful main CI is required"),
            arrayOf("manual-ci", null),
            arrayOf("scheduled-ci", null),
            arrayOf("other-branch-ci", "Successful main CI is required"),
            arrayOf("api-error", "HTTP 403"),
            arrayOf("conflicting-tag", "Existing tag points to a different commit"),
        )
    }

    @Test fun prepareStateMachine() = temporary { temp ->
        val workflow = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve(".github/workflows/releases.yml") }.first(Files::isRegularFile).readText()
        val script = workflow.substringAfter("      - name: Validate source, CI and version\n")
            .substringAfter("        run: |\n").substringBefore("\n      - ").trimIndent()
        check(script.startsWith("set -euo pipefail"))
        fun git(vararg args: String) = command("git", "-C", temp, *args)
        git("init", "-b", "main")
        fun commit() = git("-c", "user.name=Offline Test", "-c", "user.email=offline@example.invalid", "-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "Offline fixture")
        commit()
        if (scenario in setOf("conflicting-tag", "branch-dry-run-existing-tag")) git("tag", "v2.34.56")
        Files.createDirectories(temp.resolve("app")).resolve("build.gradle.kts").writeText("val releaseVersion = \"2.34.56\"\n")
        git("add", "app/build.gradle.kts")
        commit()
        val sha = git("rev-parse", "HEAD").trim()
        if (scenario != "unmerged") git("update-ref", "refs/remotes/origin/main", sha)
        val classpath = listOf(ReleasePreparationTest::class.java, Options::class.java, Unit::class.java, groovy.json.JsonSlurper::class.java, groovy.json.JsonOutput::class.java, groovy.lang.GroovyObject::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).absolutePathString() }.distinct().joinToString(File.pathSeparator)
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val bin = Files.createDirectories(temp.resolve("bin"))
        bin.resolve("gh").apply {
            writeText("#!/bin/sh\nexec ${quote(Path.of(System.getProperty("java.home"), "bin/java").toString())} -cp ${quote(classpath)} verification.MockReleasePreparation \"${'$'}@\"\n")
            check(toFile().setExecutable(true, true))
        }
        val output = temp.resolve("step-output")
        output.writeText("")
        val capture = temp.resolve("capture")
        val publish = !scenario.startsWith("branch-dry-run")
        val builder = ProcessBuilder("bash", "-c", script).directory(temp.toFile()).redirectErrorStream(true).redirectOutput(capture.toFile())
        builder.environment().apply {
            this["PATH"] = bin.toString() + File.pathSeparator + getValue("PATH")
            this["MOCK_SCENARIO"] = scenario
            this["PUBLISH"] = publish.toString()
            this["REQUESTED_TAG"] = if (scenario == "wrong-version") "v9.0.0" else ""
            this["GITHUB_EVENT_NAME"] = "workflow_dispatch"
            this["GITHUB_REF"] = when {
                scenario in setOf("manual-other-branch", "branch-dry-run-other-branch") -> "refs/heads/feature/test"
                scenario.startsWith("branch-dry-run") -> "refs/heads/feature/kmp"
                else -> "refs/heads/main"
            }
            this["GITHUB_REPOSITORY"] = "example/offline"
            this["GITHUB_OUTPUT"] = output.toString()
            this["RUNNER_TEMP"] = temp.toString()
        }
        val process = builder.start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            error("Prepare regression timed out: $scenario")
        }
        val log = capture.readText()
        if (expectedError != null) {
            assertTrue(log, process.exitValue() != 0)
            assertTrue(log, log.contains(expectedError))
            assertEquals("", output.readText())
        } else {
            assertEquals(log, 0, process.exitValue())
            val values = output.readText().lineSequence().filter(String::isNotBlank).associate { it.substringBefore('=') to it.substringAfter('=') }
            assertEquals("v2.34.56", values["tag"])
            assertEquals(sha, values["sha"])
            assertEquals(publish.toString(), values["publish"])
            assertEquals((!scenario.endsWith("already-published")).toString(), values["build"])
        }
    }
}

object MockReleasePreparation {
    @JvmStatic fun main(args: Array<String>) {
        val scenario = System.getenv("MOCK_SCENARIO")
        check(args.first() == "api")
        val endpoint = args[1]
        when {
            endpoint.startsWith("repos/example/offline/actions/workflows/ci.yml/runs?head_sha=") -> {
                check("--jq" in args)
                val run = mapOf(
                    "head_branch" to if (scenario.startsWith("branch-dry-run")) {
                        "feature/kmp"
                    } else if (scenario == "other-branch-ci") {
                        "feature/test"
                    } else {
                        "main"
                    },
                    "conclusion" to if (scenario in setOf("failed-ci", "branch-dry-run-failed-ci")) "failure" else "success",
                    "event" to when (scenario) {
                        "pr-only-ci", "branch-dry-run", "branch-dry-run-existing-tag", "branch-dry-run-failed-ci" -> "pull_request"
                        "manual-ci" -> "workflow_dispatch"
                        "scheduled-ci" -> "schedule"
                        else -> "push"
                    },
                )
                print(command("jq", "-r", args[args.indexOf("--jq") + 1], input = json(mapOf("workflow_runs" to listOf(run)))))
            }
            endpoint.startsWith("repos/example/offline/releases/tags/") && scenario.endsWith("already-published") -> print("{\"draft\":false}")
            endpoint.startsWith("repos/example/offline/releases/tags/") -> {
                System.err.println("gh: Offline API error (HTTP ${if (scenario == "api-error") 403 else 404})")
                exitProcess(1)
            }
            else -> error("Unexpected API request")
        }
    }
}
