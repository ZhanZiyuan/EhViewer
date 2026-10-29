package verification

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageZipTest {
    private val metadata = mapOf("schema_version" to 1, "source_commit" to "1".repeat(40), "publish_enabled" to false)

    private fun fixture(root: Path): Path {
        val image = Files.createDirectory(root.resolve("image"))
        image.resolve("compiled.class").writeBytes(javaClass.getResourceAsStream("StageZipTest.class")!!.use { it.readAllBytes() })
        Files.createDirectories(image.resolve("resources"))
        image.resolve("resources/资料.txt").writeText("Actual UTF-8 fixture resource\n")
        return image
    }

    private fun rejects(block: () -> Unit) = assertNotNull(runCatching(block).exceptionOrNull())

    private fun contents(file: Path): Map<String, ByteArray> = ZipFile(file.toFile()).use { zip ->
        zip.entries().asSequence().associate { entry -> entry.name to zip.getInputStream(entry).use { it.readAllBytes() } }
    }

    private fun rewrite(file: Path, values: Map<String, ByteArray>) {
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            values.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    @Test fun entireActualCompilerAndUnicodeResourceBytesSurviveZip() = temporary { root ->
        val image = fixture(root)
        val file = root.resolve("stage.zip")
        val manifest = writeStageZip(image, file, metadata)
        val proof = verifyStageZip(file, manifest)
        assertEquals("PASS", proof["result"])
        assertEquals(2, proof["file_count"])
        assertEquals(sha256(file), proof["sha256"])
        val entries = contents(file)
        assertTrue(entries.getValue("EhViewer/compiled.class").contentEquals(image.resolve("compiled.class").readBytes()))
        assertTrue(entries.getValue("EhViewer/resources/资料.txt").contentEquals(image.resolve("resources/资料.txt").readBytes()))
        assertTrue("EhViewer/ehviewer-stage-source.json" in entries)
    }

    @Test fun changedMissingExtraAndRenamedPayloadFilesFail() = temporary { root ->
        val file = root.resolve("original.zip")
        val manifest = writeStageZip(fixture(root), file, metadata)
        val entries = contents(file)
        val name = "EhViewer/resources/资料.txt"
        val modified = listOf(
            entries + (name to "Substituted".toByteArray()),
            entries - name,
            entries + ("EhViewer/unmeasured.dll" to byteArrayOf(1, 2)),
            (entries - name) + ("EhViewer/renamed.txt" to entries.getValue(name)),
        )
        modified.forEachIndexed { index, values ->
            val copy = root.resolve("modified-$index.zip")
            rewrite(copy, values)
            rejects { verifyStageZip(copy, manifest) }
        }
    }

    @Test fun substitutedSourceReceiptAndInventoryCannotPass() = temporary { root ->
        val file = root.resolve("original.zip")
        val manifest = writeStageZip(fixture(root), file, metadata)
        val modified = root.resolve("modified.zip")
        rewrite(modified, contents(file) + ("EhViewer/ehviewer-stage-source.json" to json(manifest + ("source_commit" to "2".repeat(40))).toByteArray()))
        rejects { verifyStageZip(modified, manifest) }
        rejects { verifyStageZip(file, manifest + ("source_commit" to "2".repeat(40))) }
        rejects { verifyStageZip(file, manifest + ("files" to manifest["files"].array().let { it + it.first() })) }
    }

    @Test fun traversalAndNoncanonicalZipEntriesFailWithoutExtraction() = temporary { root ->
        val file = root.resolve("original.zip")
        val manifest = writeStageZip(fixture(root), file, metadata)
        for ((index, name) in listOf("../outside", "/absolute", "C:/drive", "EhViewer\\outside", "EhViewer/./file", "EhViewer//file", "EhViewer/control\nfile").withIndex()) {
            val modified = root.resolve("unsafe-$index.zip")
            rewrite(modified, contents(file) + (name to byteArrayOf(1)))
            rejects { verifyStageZip(modified, manifest) }
        }
        assertTrue(!Files.exists(root.resolve("outside")))
    }

    @Test fun existingEvidenceNestedOutputAndSourceReceiptCollisionFail() = temporary { root ->
        val image = fixture(root)
        val file = root.resolve("stage.zip")
        writeStageZip(image, file, metadata)
        val original = sha256(file)
        rejects { writeStageZip(image, file, metadata) }
        assertEquals(original, sha256(file))
        rejects { writeStageZip(image, image.resolve("nested.zip"), metadata) }
        image.resolve("ehviewer-stage-source.json").writeText("collision")
        rejects { writeStageZip(image, root.resolve("collision.zip"), metadata) }
    }

    @Test fun caseInsensitivePayloadCannotOverwriteEmbeddedSourceReceipt() = temporary { root ->
        val payload = root.resolve("payload")
        payload.writeText("Would overwrite the source receipt on Windows")
        val name = "EhViewer/EHVIEWER-STAGE-SOURCE.JSON"
        val manifest = metadata + ("files" to listOf(mapOf("path" to name, "bytes" to Files.size(payload), "sha256" to sha256(payload))))
        val file = root.resolve("collision.zip")
        rewrite(file, mapOf(name to payload.readBytes(), "EhViewer/ehviewer-stage-source.json" to json(manifest).toByteArray()))
        rejects { verifyStageZip(file, manifest) }
    }

    @Test fun outputParentAliasCannotWriteZipBackIntoInputImage() = temporary { root ->
        val image = fixture(root)
        val alias = Files.createSymbolicLink(root.resolve("alias"), image)
        rejects { writeStageZip(image, alias.resolve("nested.zip"), metadata) }
        assertTrue(!Files.exists(image.resolve("nested.zip")))
    }
}
