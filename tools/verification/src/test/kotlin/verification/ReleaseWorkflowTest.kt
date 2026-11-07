package verification

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.readText
import kotlin.io.path.writeText
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
            arrayOf("public-test-manifest", "Production Android certificate is required"),
            arrayOf("public-test-certificate", "Production Android certificate is required"),
            arrayOf("public-test-mode", "Production Android certificate is required"),
            arrayOf("unverified-eleven-manifest", "All eleven package bytes must be verified"),
            arrayOf("missing-apk", "Expected exactly eleven installation assets"),
            arrayOf("extra-apk", "Expected exactly eleven installation assets"),
            arrayOf("nested-asset", "Expected exactly eleven installation assets"),
            arrayOf("linked-asset", "Unsafe checksum entry"),
        )
    }

    @Test fun publishStateMachine() = temporary { temp ->
        val tag = TEST_TAG
        val workflow = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve(".github/workflows/releases.yml") }
            .first(Files::isRegularFile)
        val script = workflow.readText()
            .substringAfter("      - name: Verify payload and publish exactly eleven installation attachments\n")
            .substringAfter("        run: |\n")
            .trimIndent()
        check(script.startsWith("set -euo pipefail"))
        val apks = Files.createDirectories(temp.resolve("assets"))
        val attachments = releaseTargets.map { target ->
            val name = target.fileName(tag.removePrefix("v"))
            val path = apks.resolve(name).apply { writeText("synthetic protocol-only payload for ${target.id}; not native package acceptance") }
            mapOf("name" to name, "sha256" to sha256(path))
        }
        Files.createDirectories(temp.resolve("manifest")).resolve("release-manifest.json").writeText(
            json(
                mapOf(
                    "source_commit" to if (scenario == "wrong-source") OTHER_SOURCE else RELEASE_SOURCE,
                    "tag" to tag,
                    "schema_version" to 2,
                    "all_eleven_package_bytes_verified" to (scenario != "unverified-eleven-manifest"),
                    "mode" to if (scenario == "public-test-mode") "verified-public-test-dry-run" else "verified-payload",
                    "public_test_dry_run" to (scenario == "public-test-manifest"),
                    "android_certificate_sha256" to if (scenario == "public-test-certificate") PUBLIC_RELEASE_TEST_CERTIFICATE else CERTIFICATE,
                    "source_dirty" to (scenario == "dirty-manifest"),
                    "attachments" to attachments,
                ),
            ),
        )
        when (scenario) {
            "tampered-apk" -> apks.resolve(attachments.first().getValue("name")).writeText("tampered")
            "missing-apk" -> Files.delete(apks.resolve(attachments.first().getValue("name")))
            "extra-apk" -> apks.resolve("unexpected.apk").writeText("extra")
            "nested-asset" -> {
                val name = attachments.first().getValue("name")
                Files.move(apks.resolve(name), Files.createDirectory(apks.resolve("nested")).resolve(name))
            }
            "linked-asset" -> {
                val name = attachments.first().getValue("name")
                val retained = Files.move(apks.resolve(name), temp.resolve(name))
                Files.createSymbolicLink(apks.resolve(name), retained)
            }
        }
        val stateFile = temp.resolve("github.json")
        stateFile.writeText(json(mapOf("scenario" to scenario, "calls" to emptyList<Any>())))
        val bin = Files.createDirectories(temp.resolve("bin"))
        // Each invocation remains a real child process; only the already-loaded Kotlin model is reused.
        val client = Path.of(System.getProperty("verification.offlineClient"))
        check(Files.isExecutable(client)) { "Missing compiled offline test client" }
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
        mapOf("gh" to "gh", "sha256sum" to "checksum").forEach { (name, mode) ->
            bin.resolve(name).apply {
                writeText("#!/bin/sh\nexec ${quote(client.absolutePathString())} $mode \"${'$'}@\"\n")
                check(toFile().setExecutable(true, true))
            }
        }
        OfflineProcessServer { mode, arguments, input ->
            when (mode) {
                "gh" -> MockReleaseGitHub.respond(arguments, stateFile, tag)
                "checksum" -> MockReleaseChecksum.respond(arguments, input, temp)
                else -> error("Unknown offline mode: $mode")
            }
        }.use { offline ->
            val capture = temp.resolve("output.log")
            val builder = ProcessBuilder("bash", "-c", script)
                .directory(temp.toFile()).redirectErrorStream(true).redirectOutput(capture.toFile())
            builder.environment().apply {
                this["PATH"] = bin.absolutePathString() + java.io.File.pathSeparator + getValue("PATH")
                this["MOCK_RELEASE_STATE"] = stateFile.absolutePathString()
                this["MOCK_RELEASE_ENDPOINT"] = offline.endpoint
                this["MOCK_RELEASE_TOKEN"] = offline.token
                this["GH_TOKEN"] = "offline-test-token"
                this["GH_REPO"] = "example/offline"
                this["RELEASE_TAG"] = tag
                this["SOURCE_SHA"] = RELEASE_SOURCE
            }
            val process = builder.start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.descendants().use { children -> children.toList().asReversed().forEach { it.destroyForcibly() } }
                process.destroyForcibly().waitFor()
                error("Publish regression timed out: $scenario\n${capture.readText()}")
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
                if (scenario.startsWith("public-test-") || scenario == "unverified-eleven-manifest") assertTrue("Public fixtures must fail before any GitHub mutation: $calls", calls.isEmpty())
            }
            assertFalse(calls.toString(), calls.any { "PATCH" in it || "DELETE" in it || it.any { arg -> "/commits/" in arg } })
            if (scenario == "cyclic-tag") {
                println("cyclic-tag JVM=${System.getProperty("os.arch")}; Java=${System.getProperty("java.version")}; mock API calls=${calls.size}; deadline=60s")
            }
        }
    }
}

/** macOS and Linux expose different checksum CLIs; validate the same real bytes with the JDK. */
internal object MockReleaseChecksum {
    fun respond(args: Array<String>, input: String, directory: Path): OfflineProcessResult {
        check(args.toList() == listOf("-c"))
        var valid = true
        val output = StringBuilder()
        input.reader().buffered().forEachLine { line ->
            val (expected, filename) = line.split("  ", limit = 2)
            val matches = sha256(directory.resolve(filename)) == expected
            output.appendLine("$filename: ${if (matches) "OK" else "FAILED"}")
            valid = valid && matches
        }
        return OfflineProcessResult(if (valid) 0 else 1, output.toString())
    }
}

/** A process boundary ensures Bash receives realistic exit codes, stdout, stderr and persisted state. */
internal object MockReleaseGitHub {
    private class ApiFailure(val status: Int) : RuntimeException()

    fun respond(args: Array<String>, stateFile: Path, tag: String): OfflineProcessResult {
        val state = readJson(stateFile).toMutableMap()
        val calls = state["calls"].array().toMutableList().apply { add(args.toList()) }
        state["calls"] = calls
        val scenario = state.str("scenario")
        fun fail(status: Int): Nothing = throw ApiFailure(status)
        fun release(target: String = RELEASE_SOURCE, draft: Boolean = true): Map<String, Any> = mapOf("tag_name" to tag, "target_commitish" to target, "draft" to draft)
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
                    endpoint == "git/ref/tags/$tag" -> {
                        if (scenario == "ref-permission-error") fail(403)
                        val sha = state["tag"] as? String ?: initialTag() ?: fail(404)
                        val type = when (scenario) {
                            "annotated-tag", "nested-tag", "conflicting-annotated-tag", "tag-object-error", "cyclic-tag" -> "tag"
                            "non-commit-tag" -> "tree"
                            else -> "commit"
                        }
                        state["tag"] = sha
                        json(mapOf("ref" to "refs/tags/$tag", "object" to mapOf("type" to type, "sha" to if (type == "tag") TAG_OBJECT else sha)))
                    }
                    endpoint.startsWith("git/tags/") -> {
                        if (scenario == "tag-object-error") fail(422)
                        val nested = (scenario == "nested-tag" && endpoint.endsWith(TAG_OBJECT)) || scenario == "cyclic-tag"
                        json(mapOf("object" to mapOf("type" to if (nested) "tag" else "commit", "sha" to if (nested) NESTED_TAG_OBJECT else state.getValue("tag"))))
                    }
                    endpoint == "git/refs" -> {
                        check("--method" in args && "POST" in args)
                        check("ref=refs/tags/$tag" in args && "sha=$RELEASE_SOURCE" in args)
                        if (scenario == "create-permission-error") fail(403)
                        if (scenario == "create-validation-error") fail(422)
                        state["tag"] = if (scenario == "create-race-conflict") OTHER_SOURCE else RELEASE_SOURCE
                        if (scenario.startsWith("create-race-")) fail(if (scenario == "create-race-409") 409 else 422)
                        json(mapOf("ref" to "refs/tags/$tag"))
                    }
                    else -> error("Unexpected API request: ${'$'}{args.toList()}")
                }
            } else {
                check(args.first() == "release" && args[2] == tag)
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
                        val assets = args.drop(3).filter { it.startsWith("assets/") }.map { Path.of(it).fileName.toString() }
                        state["assets"] = assets + if (scenario == "extra-release-asset") listOf("debug.txt") else emptyList()
                        if (scenario == "tag-moved-during-upload") state["tag"] = OTHER_SOURCE
                        ""
                    }
                    "view" -> state["assets"].array().joinToString("\n", postfix = "\n")
                    "edit" -> {
                        check("--draft=false" in args)
                        check("--latest" in args && "--prerelease" !in args)
                        state["published"] = true
                        ""
                    }
                    else -> error("Unexpected release command: ${'$'}{args.toList()}")
                }
            }
            stateFile.writeText(json(state))
            return OfflineProcessResult(0, output)
        } catch (failure: ApiFailure) {
            stateFile.writeText(json(state))
            return OfflineProcessResult(1, stderr = "gh: Mock API error (HTTP ${failure.status})\n")
        }
    }
}
