package verification

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactArchiveTest {
    private val head = "1".repeat(40)
    private val compiled get() = javaClass.getResourceAsStream("ArtifactArchiveTest.class")!!.use { it.readAllBytes() }

    private fun zip(root: Path, entries: Map<String, ByteArray> = mapOf("compiled.class" to compiled)): Path {
        val archive = Files.createTempFile(root, "actual-", ".zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return archive
    }

    private fun artifact(archive: Path): Map<String, Any?> = mapOf(
        "id" to 23,
        "name" to "actual-compiler-artifact",
        "size_in_bytes" to Files.size(archive),
        "digest" to "sha256:${sha256(archive)}",
        "expired" to false,
        "workflow_run" to mapOf("id" to 17, "head_sha" to head),
    )

    private fun catalog(archive: Path, changes: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf("total_count" to 1, "artifacts" to listOf(artifact(archive) + changes))
    private fun inspect(catalog: Map<String, Any?>, archive: Path, output: Path) = inspectArtifactArchive(catalog, archive, 23, 17, head, output)
    private fun rejects(block: () -> Unit) = assertNotNull(runCatching(block).exceptionOrNull())

    @Test fun actualCompilerHiddenAndUnicodeBytesAreReadBackWithTransportOnlyEvidence() = temporary { root ->
        val resource = "Actual UTF-8 resource 资料\n".toByteArray()
        val archive = zip(root, mapOf("compiled.class" to compiled, "app/.jpackage.xml" to resource, "资源/notice.txt" to resource, "empty" to byteArrayOf()))
        val before = sha256(archive)
        val output = root.resolve("output")
        val result = inspect(catalog(archive), archive, output)
        assertEquals("PASS", result["result"])
        assertEquals(4, result["payload_file_count"])
        assertEquals(before, result["archive_sha256"])
        assertEquals(before, sha256(archive))
        assertTrue(output.resolve("compiled.class").readBytes().contentEquals(compiled))
        assertTrue(output.resolve("app/.jpackage.xml").readBytes().contentEquals(resource))
        assertTrue(output.resolve("资源/notice.txt").readBytes().contentEquals(resource))
        result["files"].array().forEach { value ->
            val file = value.obj()
            assertEquals(file["sha256"], sha256(output.resolve(file.str("path"))))
        }
        assertEquals("GITHUB_ARTIFACT_TRANSPORT_ONLY", result["verification_scope"])
        for (key in listOf("native_execution", "product_package_validation")) assertEquals("NOT_EXECUTED", result[key])
        for (key in listOf("product_p0_verified", "all_eleven_assets_verified", "publish_enabled")) assertEquals(false, result[key])
    }

    @Test fun crossRunWrongHeadExpiredAndMissingOfficialDigestFailBeforeWriting() = temporary { root ->
        val archive = zip(root)
        val mutations = listOf(
            mapOf("workflow_run" to mapOf("id" to 18, "head_sha" to head)),
            mapOf("workflow_run" to mapOf("id" to 17, "head_sha" to "2".repeat(40))),
            mapOf("expired" to true),
            mapOf("digest" to ""),
            mapOf("digest" to "md5:${"a".repeat(32)}"),
            mapOf("name" to "../escape"),
        )
        mutations.forEachIndexed { index, changes ->
            val output = root.resolve("output-$index")
            rejects { inspect(catalog(archive, changes), archive, output) }
            assertFalse(output.exists())
        }
    }

    @Test fun incompleteDuplicateAndAmbiguousCatalogsFailBeforeWriting() = temporary { root ->
        val archive = zip(root)
        val entry = artifact(archive)
        val catalogs = listOf(
            mapOf("total_count" to 2, "artifacts" to listOf(entry)),
            mapOf("total_count" to 2, "artifacts" to listOf(entry, entry)),
            mapOf("total_count" to 2, "artifacts" to listOf(entry, entry + ("id" to 24))),
            mapOf("total_count" to 1, "artifacts" to listOf(entry + ("id" to 24))),
        )
        catalogs.forEachIndexed { index, input ->
            val output = root.resolve("output-$index")
            rejects { inspect(input, archive, output) }
            assertFalse(output.exists())
        }
    }

    @Test fun changedSizeAndSameSizeDigestCorruptionCannotCreateOutput() = temporary { root ->
        val archive = zip(root)
        val original = archive.readBytes()
        val input = catalog(archive)
        for (bytes in listOf(original + byteArrayOf(0), original.clone().apply { this[10] = (this[10].toInt() xor 1).toByte() })) {
            archive.writeBytes(bytes)
            val output = root.resolve("output")
            rejects { inspect(input, archive, output) }
            assertFalse(output.exists())
        }
    }

    @Test fun allUnsafeAndCollidingPathsFailBeforeCreatingAnExtractionRoot() = temporary { root ->
        val paths = listOf("", "../escape", "/absolute", "C:/drive", "back\\slash", "a//b", "a/./b", "a/../b", "control\u0001name")
        val entries = paths.map { mapOf(it to compiled) } + listOf(mapOf("A" to compiled, "a" to compiled), mapOf("parent" to compiled, "parent/child" to compiled), mapOf("parent" to compiled, "Parent/child" to compiled), mapOf("only-directory/" to byteArrayOf()))
        entries.forEachIndexed { index, names ->
            val archive = zip(root, names)
            val output = root.resolve("output-$index")
            rejects { inspect(catalog(archive), archive, output) }
            assertFalse(output.exists())
        }
        assertFalse(root.resolve("escape").exists())
    }

    @Test fun existingUserDataAndDirectoryAliasesCannotBeOverwritten() = temporary { root ->
        val archive = zip(root)
        val user = Files.createDirectory(root.resolve("user"))
        val output = Files.createDirectory(user.resolve("output"))
        val sentinel = output.resolve("compiled.class").apply { writeText("Existing user file\n") }
        val before = sha256(sentinel)
        val alias = Files.createSymbolicLink(root.resolve("alias"), user)
        for (destination in listOf(output, alias.resolve("output"), sentinel)) {
            rejects { inspect(catalog(archive), archive, destination) }
            assertEquals(before, sha256(sentinel))
        }
    }

    @Test fun unixLinkAttributesAreExtractedOnlyAsOrdinaryBytes() = temporary { root ->
        val sentinel = root.resolve("user-data").apply { writeText("Preserve this file\n") }
        val before = sha256(sentinel)
        val archive = zip(root, mapOf("pointer" to sentinel.toString().toByteArray()))
        val bytes = archive.readBytes()
        val central = bytes.indices.first { i -> i + 4 <= bytes.size && bytes.copyOfRange(i, i + 4).contentEquals(byteArrayOf(0x50, 0x4b, 1, 2)) }
        bytes[central + 5] = 3 // Unix creator; external attributes describe a symlink.
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(central + 38, 0xa1ff0000.toInt())
        archive.writeBytes(bytes)
        val output = root.resolve("output")
        inspect(catalog(archive), archive, output)
        assertFalse(Files.isSymbolicLink(output.resolve("pointer")))
        assertEquals(sentinel.toString(), output.resolve("pointer").readText())
        assertEquals(before, sha256(sentinel))
    }

    @Test fun matchingOfficialDigestDoesNotBypassInnerCrcVerification() = temporary { root ->
        val archive = zip(root)
        val bytes = archive.readBytes()
        val central = bytes.indices.first { i -> i + 4 <= bytes.size && bytes.copyOfRange(i, i + 4).contentEquals(byteArrayOf(0x50, 0x4b, 1, 2)) }
        bytes[central + 16] = (bytes[central + 16].toInt() xor 1).toByte()
        archive.writeBytes(bytes)
        rejects { inspect(catalog(archive), archive, root.resolve("output")) }
    }

    @Test fun excessiveDeclaredExpansionFailsBeforeAnyOutputIsCreated() = temporary { root ->
        val archive = zip(root)
        val bytes = archive.readBytes()
        val central = bytes.indices.first { i -> i + 4 <= bytes.size && bytes.copyOfRange(i, i + 4).contentEquals(byteArrayOf(0x50, 0x4b, 1, 2)) }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(central + 24, 768 * 1024 * 1024)
        archive.writeBytes(bytes)
        val output = root.resolve("output")
        rejects { inspect(catalog(archive), archive, output) }
        assertFalse(output.exists())
    }
}
