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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private const val RELEASE_SOURCE = "1111111111111111111111111111111111111111"
private const val OTHER_SOURCE = "2222222222222222222222222222222222222222"
private const val TAG_OBJECT = "3333333333333333333333333333333333333333"
private const val NESTED_TAG_OBJECT = "4444444444444444444444444444444444444444"
private const val TEST_TAG = "v2.34.56"

/** Execute the actual workflow script against a local stateful GitHub CLI substitute. */
@RunWith(Parameterized::class)
class ReleaseWorkflowTest(private val scenario: String, private val expectedError: String?) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<String?>> = listOf(
            arrayOf("first-publication", null),
            arrayOf("draft-without-tag", null),
            arrayOf("draft-with-tag", null),
            arrayOf("existing-tag", null),
            arrayOf("annotated-tag", null),
            arrayOf("nested-tag", null),
            arrayOf("create-race-409", null),
            arrayOf("create-race-422", null),
            arrayOf("conflicting-tag", "Tag points to a different commit"),
            arrayOf("conflicting-annotated-tag", "Tag points to a different commit"),
            arrayOf("conflicting-draft", "Draft belongs to a different commit"),
            arrayOf("published-release", "Release already published"),
            arrayOf("duplicate-drafts", "Multiple releases use this tag"),
            arrayOf("ref-permission-error", "(HTTP 403)"),
            arrayOf("create-permission-error", "(HTTP 403)"),
            arrayOf("create-validation-error", "(HTTP 404)"),
            arrayOf("create-race-conflict", "Tag points to a different commit"),
            arrayOf("release-list-error", "(HTTP 500)"),
            arrayOf("tag-object-error", "(HTTP 422)"),
            arrayOf("non-commit-tag", "Tag does not resolve to a commit"),
            arrayOf("cyclic-tag", "Annotated tag nesting exceeds"),
            arrayOf("upload-error", "(HTTP 503)"),
            arrayOf("extra-release-asset", "debug.txt"),
            arrayOf("tag-moved-during-upload", "Tag points to a different commit"),
            arrayOf("tampered-apk", "FAILED"),
            arrayOf("dirty-manifest", "Refusing to publish a dirty source tree"),
            arrayOf("wrong-source", "Manifest source commit differs"),
            arrayOf("missing-apk", "Expected exactly four APKs"),
            arrayOf("extra-apk", "Expected exactly four APKs"),
        )
    }

    @Test fun publishStateMachine() = temporary { temp ->
        val workflow = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve(".github/workflows/releases.yml") }
            .first(Files::isRegularFile)
        val script = workflow.readText()
            .substringAfter("      - name: Verify payload and publish exactly four APK attachments\n")
            .substringAfter("        run: |\n")
            .trimIndent()
        check(script.startsWith("set -euo pipefail"))
        val apks = Files.createDirectories(temp.resolve("apks"))
        val attachments = (abis.keys + "universal").map { abi ->
            val name = "EhViewer-${TEST_TAG.removePrefix("v")}-$abi.apk"
            val path = apks.resolve(name).apply { writeText("synthetic APK for $abi") }
            mapOf("name" to name, "sha256" to sha256(path))
        }
        Files.createDirectories(temp.resolve("manifest")).resolve("release-manifest.json").writeText(
            json(
                mapOf(
                    "source_commit" to if (scenario == "wrong-source") OTHER_SOURCE else RELEASE_SOURCE,
                    "tag" to TEST_TAG,
                    "source_dirty" to (scenario == "dirty-manifest"),
                    "attachments" to attachments,
                ),
            ),
        )
        when (scenario) {
            "tampered-apk" -> apks.resolve(attachments.first().getValue("name")).writeText("tampered")
            "missing-apk" -> Files.delete(apks.resolve(attachments.first().getValue("name")))
            "extra-apk" -> apks.resolve("unexpected.apk").writeText("extra")
        }
        val stateFile = temp.resolve("github.json")
        stateFile.writeText(json(mapOf("scenario" to scenario, "calls" to emptyList<Any>())))
        val bin = Files.createDirectories(temp.resolve("bin"))
        // This temporary launcher only invokes the Kotlin mock; no shell test file is tracked.
        val classpath = listOf(
            ReleaseWorkflowTest::class.java,
            Options::class.java,
            Unit::class.java,
            groovy.json.JsonSlurper::class.java,
            groovy.json.JsonOutput::class.java,
            groovy.lang.GroovyObject::class.java,
        ).map { Path.of(it.protectionDomain.codeSource.location.toURI()).absolutePathString() }.distinct().joinToString(File.pathSeparator)
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
        val java = Path.of(System.getProperty("java.home"), "bin/java").absolutePathString()
        mapOf("gh" to "MockReleaseGitHub", "sha256sum" to "MockReleaseChecksum").forEach { (name, mainClass) ->
            bin.resolve(name).apply {
                writeText("#!/bin/sh\nexec ${quote(java)} -cp ${quote(classpath)} verification.$mainClass \"${'$'}@\"\n")
                check(toFile().setExecutable(true, true))
            }
        }
        val capture = temp.resolve("output.log")
        val builder = ProcessBuilder("bash", "-c", script)
            .directory(temp.toFile()).redirectErrorStream(true).redirectOutput(capture.toFile())
        builder.environment().apply {
            this["PATH"] = bin.absolutePathString() + File.pathSeparator + getValue("PATH")
            this["MOCK_RELEASE_STATE"] = stateFile.absolutePathString()
            this["GH_TOKEN"] = "offline-test-token"
            this["GH_REPO"] = "example/offline"
            this["RELEASE_TAG"] = TEST_TAG
            this["SOURCE_SHA"] = RELEASE_SOURCE
        }
        val process = builder.start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            error("Publish regression timed out: $scenario")
        }
        val output = capture.readText()
        val state = readJson(stateFile)
        val calls = state.getValue("calls").array().map { call -> call.array().map { it as String } }
        if (expectedError == null) {
            assertEquals(output, 0, process.exitValue())
            assertEquals(RELEASE_SOURCE, state["tag"])
            assertEquals(true, state["published"])
            assertEquals(attachments.map { it.getValue("name") }.toSet(), state["assets"].array().toSet())
            if (scenario.startsWith("draft-")) assertFalse(calls.any { it.take(2) == listOf("release", "create") })
            if (scenario == "first-publication") {
                val createTag = calls.indexOfFirst { "POST" in it }
                val createDraft = calls.indexOfFirst { it.take(2) == listOf("release", "create") }
                assertTrue(calls.toString(), createTag >= 0 && createTag < createDraft)
            }
        } else {
            assertTrue(output, process.exitValue() != 0)
            assertTrue(output, output.contains(expectedError))
            assertFalse(calls.toString(), calls.any { it.take(2) == listOf("release", "edit") })
        }
        assertFalse(calls.toString(), calls.any { "PATCH" in it || "DELETE" in it || it.any { arg -> "/commits/" in arg } })
    }
}

/** macOS and Linux expose different checksum CLIs; validate the same real bytes with the JDK. */
object MockReleaseChecksum {
    @JvmStatic fun main(args: Array<String>) {
        check(args.toList() == listOf("-c"))
        var valid = true
        System.`in`.bufferedReader().forEachLine { line ->
            val (expected, filename) = line.split("  ", limit = 2)
            val matches = sha256(Path.of(filename)) == expected
            println("$filename: ${if (matches) "OK" else "FAILED"}")
            valid = valid && matches
        }
        if (!valid) exitProcess(1)
    }
}

/** A process boundary ensures Bash receives realistic exit codes, stdout, stderr and persisted state. */
object MockReleaseGitHub {
    private class ApiFailure(val status: Int) : RuntimeException()

    @JvmStatic fun main(args: Array<String>) {
        val stateFile = Path.of(System.getenv("MOCK_RELEASE_STATE"))
        val state = readJson(stateFile).toMutableMap()
        val calls = state["calls"].array().toMutableList().apply { add(args.toList()) }
        state["calls"] = calls
        val scenario = state.str("scenario")
        fun fail(status: Int): Nothing = throw ApiFailure(status)
        fun release(target: String = RELEASE_SOURCE, draft: Boolean = true): Map<String, Any> = mapOf("tag_name" to TEST_TAG, "target_commitish" to target, "draft" to draft)
        fun initialTag(): String? = when (scenario) {
            "draft-with-tag", "existing-tag", "annotated-tag", "nested-tag", "tag-object-error", "non-commit-tag", "cyclic-tag" -> RELEASE_SOURCE
            "conflicting-tag", "conflicting-annotated-tag" -> OTHER_SOURCE
            else -> null
        }
        try {
            val output = if (args.first() == "api") {
                val endpoint = args.first { it.startsWith("repos/") }.removePrefix("repos/example/offline/")
                when {
                    endpoint == "releases?per_page=100" -> {
                        check("--paginate" in args && "--slurp" in args)
                        if (scenario == "release-list-error") fail(500)
                        val releases = when (scenario) {
                            "draft-without-tag", "draft-with-tag" -> listOf(release())
                            "conflicting-draft" -> listOf(release(OTHER_SOURCE))
                            "published-release" -> listOf(release(draft = false))
                            "duplicate-drafts" -> listOf(release(), release())
                            else -> emptyList()
                        }
                        // Put matching drafts on a subsequent page to exercise pagination.
                        json(listOf(emptyList<Any>(), releases))
                    }
                    endpoint == "git/ref/tags/$TEST_TAG" -> {
                        if (scenario == "ref-permission-error") fail(403)
                        val sha = state["tag"] as? String ?: initialTag() ?: fail(404)
                        val type = when (scenario) {
                            "annotated-tag", "nested-tag", "conflicting-annotated-tag", "tag-object-error", "cyclic-tag" -> "tag"
                            "non-commit-tag" -> "tree"
                            else -> "commit"
                        }
                        state["tag"] = sha
                        json(mapOf("ref" to "refs/tags/$TEST_TAG", "object" to mapOf("type" to type, "sha" to if (type == "tag") TAG_OBJECT else sha)))
                    }
                    endpoint.startsWith("git/tags/") -> {
                        if (scenario == "tag-object-error") fail(422)
                        val nested = (scenario == "nested-tag" && endpoint.endsWith(TAG_OBJECT)) || scenario == "cyclic-tag"
                        json(mapOf("object" to mapOf("type" to if (nested) "tag" else "commit", "sha" to if (nested) NESTED_TAG_OBJECT else state.getValue("tag"))))
                    }
                    endpoint == "git/refs" -> {
                        check("--method" in args && "POST" in args)
                        check("ref=refs/tags/$TEST_TAG" in args && "sha=$RELEASE_SOURCE" in args)
                        if (scenario == "create-permission-error") fail(403)
                        if (scenario == "create-validation-error") fail(422)
                        state["tag"] = if (scenario == "create-race-conflict") OTHER_SOURCE else RELEASE_SOURCE
                        if (scenario.startsWith("create-race-")) fail(if (scenario == "create-race-409") 409 else 422)
                        json(mapOf("ref" to "refs/tags/$TEST_TAG"))
                    }
                    else -> error("Unexpected API request: ${'$'}{args.toList()}")
                }
            } else {
                check(args.first() == "release" && args[2] == TEST_TAG)
                when (args[1]) {
                    "create" -> {
                        check("--verify-tag" in args && "--draft" in args)
                        check(args[args.indexOf("--target") + 1] == RELEASE_SOURCE)
                        check(state["tag"] == RELEASE_SOURCE)
                        state["created"] = true
                        "https://example.invalid/draft\n"
                    }
                    "upload" -> {
                        if (scenario == "upload-error") fail(503)
                        val assets = args.drop(3).filter { it.endsWith(".apk") }.map { Path.of(it).fileName.toString() }
                        state["assets"] = assets + if (scenario == "extra-release-asset") listOf("debug.txt") else emptyList()
                        if (scenario == "tag-moved-during-upload") state["tag"] = OTHER_SOURCE
                        ""
                    }
                    "view" -> state["assets"].array().joinToString("\n", postfix = "\n")
                    "edit" -> {
                        check("--draft=false" in args && "--latest" in args)
                        state["published"] = true
                        ""
                    }
                    else -> error("Unexpected release command: ${'$'}{args.toList()}")
                }
            }
            stateFile.writeText(json(state))
            print(output)
        } catch (failure: ApiFailure) {
            stateFile.writeText(json(state))
            System.err.println("gh: Mock API error (HTTP ${failure.status})")
            exitProcess(1)
        }
    }
}
