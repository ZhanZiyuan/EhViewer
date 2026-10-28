package verification

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePolicyTest {
    private fun git(directory: Path, vararg args: String) = command(
        "git",
        "-C",
        directory,
        "-c",
        "user.name=Verification",
        "-c",
        "user.email=verification@example.invalid",
        *args,
    )

    private fun repository(block: (Path, String) -> Unit) = temporary { directory ->
        git(directory, "init", "--quiet")
        git(directory, "config", "core.autocrlf", "false")
        directory.resolve(".gitignore").writeText("build/\n")
        directory.resolve("legacy.py").writeText("print('existing historical tool')\n")
        directory.resolve("gradlew").writeText("#!/bin/sh\nexit 0\n")
        directory.resolve("Main.kt").writeText("package owned\n")
        git(directory, "add", ".")
        git(directory, "commit", "--quiet", "-m", "Record fixture baseline")
        block(directory, git(directory, "rev-parse", "HEAD").trim())
    }

    @Test fun acceptsOwnedSourceMetadataAndUntrackedUnicodeWithoutFollowingIgnoredBuildOutput() = repository { directory, baseline ->
        val source = if (System.getProperty("os.name").startsWith("Windows")) "漫画 reader.kt" else "漫画\n reader.kt"
        directory.resolve(source).writeText("package owned\n")
        directory.resolve("build.gradle.kts").writeText("plugins {}\n")
        directory.resolve("lib.rs").writeText("#![allow(dead_code)]\n")
        directory.resolve("Info.plist").writeText("<plist/>\n")
        directory.resolve("build").createDirectories().resolve("generated.c").writeText("generated fixture")
        val result = sourcePolicy(directory, baseline)
        assertEquals(listOf("Info.plist", "build.gradle.kts", "lib.rs", source).sorted(), result["changed_files"])
        assertEquals(3, result["changed_kotlin_rust_files"])
        assertEquals(baseline, result["source_commit"])
        assertEquals(true, result["source_dirty"])
        assertEquals("NOT_VERIFIED", result["complete_copy_or_license_audit"])
        assertEquals(false, result["reference_checkout_required"])
        assertFalse(result["changed_files"].array().any { it.toString().startsWith("build/") })
    }

    @Test fun rejectsNewForbiddenLanguagesIncludingCaseChangesAndNonGradleKotlinScripts() = repository { directory, baseline ->
        for (extension in listOf("swift", "m", "mm", "java", "c", "C", "cpp", "h", "py", "js", "ts", "tsx", "sh", "ps1", "bat", "dart", "go", "rb", "kts")) {
            val source = directory.resolve("new.$extension").also { it.writeText("forbidden fixture") }
            assertThrows(extension, IllegalArgumentException::class.java) { sourcePolicy(directory, baseline) }
            source.deleteExisting()
        }
    }

    @Test fun unchangedHistoricalToolsRemainAllowedButRewritingThemIsRejected() = repository { directory, baseline ->
        assertEquals(false, sourcePolicy(directory, baseline)["source_dirty"])
        val legacy = directory.resolve("legacy.py")
        legacy.writeText("print('changed implementation')\n")
        git(directory, "add", "legacy.py")
        assertThrows(IllegalArgumentException::class.java) { sourcePolicy(directory, baseline) }
        legacy.deleteExisting()
        git(directory, "add", "legacy.py")
        assertEquals("PASS", sourcePolicy(directory, baseline)["result"])
        directory.resolve("gradlew").writeText("#!/bin/sh\nexit 1\n")
        assertThrows(IllegalArgumentException::class.java) { sourcePolicy(directory, baseline) }
    }

    @Test fun renamedAndCommittedForbiddenSourceCannotDisappearFromTheCumulativeAudit() = repository { directory, baseline ->
        git(directory, "mv", "Main.kt", "Main.java")
        assertThrows(IllegalArgumentException::class.java) { sourcePolicy(directory, baseline) }
        git(directory, "commit", "--quiet", "-m", "Record forbidden fixture")
        assertThrows(IllegalArgumentException::class.java) { sourcePolicy(directory, baseline) }
    }

    @Test fun stagedSourceIsAuditedEvenWhenTheWorkingCopyIsRestoredOrRemoved() = repository { directory, baseline ->
        val legacy = directory.resolve("legacy.py")
        val original = legacy.readText()
        legacy.writeText("print('staged rewrite')\n")
        git(directory, "add", "legacy.py")
        legacy.writeText(original)
        assertThrows(IllegalArgumentException::class.java) { sourcePolicy(directory, baseline) }
        git(directory, "reset", "--quiet", "HEAD", "--", "legacy.py")
        val source = directory.resolve("Copied.kt").also { it.writeText("package me.him188.ani.test\n") }
        git(directory, "add", "Copied.kt")
        source.writeText("package owned\n")
        assertThrows(IllegalArgumentException::class.java) { sourcePolicy(directory, baseline) }
        source.deleteExisting()
        assertThrows(IllegalArgumentException::class.java) { sourcePolicy(directory, baseline) }
    }

    @Test fun rejectsDisguisedInterpreterScriptsAndRecognizableReferenceSourceMarkers() = repository { directory, baseline ->
        val script = directory.resolve("resource.txt")
        for (text in listOf("#!/usr/bin/env python3\n", "\uFEFF#!/bin/sh\n")) {
            script.writeText(text)
            assertThrows(IllegalArgumentException::class.java) { sourcePolicy(directory, baseline) }
        }
        script.deleteExisting()
        val rustScript = directory.resolve("script.rs").also { it.writeText("#!/usr/bin/env cargo\n") }
        assertThrows(IllegalArgumentException::class.java) { sourcePolicy(directory, baseline) }
        rustScript.deleteExisting()
        val source = directory.resolve("Copied.kt")
        for (text in listOf("package me.him188.ani.test\n", "import org.openani.platform.Test\n", "// SPDX-License-Identifier: AGPL-3.0-only\n")) {
            source.writeText(text)
            assertThrows(IllegalArgumentException::class.java) { sourcePolicy(directory, baseline) }
        }
    }

    @Test fun rejectsUntrackedAndIndexedLinksWithoutReadingTheExternalSource() = repository { directory, baseline ->
        temporary { outside ->
            val original = outside.resolve("Reference.kt").also { it.writeText("untouched reference fixture") }
            Files.createSymbolicLink(directory.resolve("Reference.kt"), original)
            assertThrows(IllegalArgumentException::class.java) { sourcePolicy(directory, baseline) }
            git(directory, "add", "Reference.kt")
            assertThrows(IllegalArgumentException::class.java) { sourcePolicy(directory, baseline) }
            assertEquals("untouched reference fixture", original.readText())
        }
    }

    @Test fun rejectsGitlinksAndUntrackedNestedRepositories() = repository { directory, baseline ->
        git(directory, "update-index", "--add", "--cacheinfo", "160000,$baseline,reference")
        assertThrows(IllegalArgumentException::class.java) { sourcePolicy(directory, baseline) }
        git(directory, "update-index", "--force-remove", "reference")
        val nested = directory.resolve("reference").createDirectories()
        git(nested, "init", "--quiet")
        nested.resolve("Copied.kt").writeText("package copied\n")
        git(nested, "add", ".")
        git(nested, "commit", "--quiet", "-m", "Record nested fixture")
        assertThrows(IllegalArgumentException::class.java) { sourcePolicy(directory, baseline) }
    }

    @Test fun rejectsMissingUnrelatedOrAmbiguousBaselineInsteadOfAuditingAnEmptyDiff() = repository { directory, baseline ->
        for (invalid in listOf("HEAD", baseline.take(7), baseline + "\n")) {
            assertThrows(IllegalArgumentException::class.java) { sourcePolicy(directory, invalid) }
        }
        assertThrows(CommandFailure::class.java) { sourcePolicy(directory, "0".repeat(40)) }
        val unrelated = git(directory, "commit-tree", "$baseline^{tree}", "-m", "Record unrelated fixture").trim()
        assertTrue(unrelated != baseline)
        assertThrows(CommandFailure::class.java) { sourcePolicy(directory, unrelated) }
    }
}
