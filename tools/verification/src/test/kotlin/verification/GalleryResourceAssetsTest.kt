package verification

import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GalleryResourceAssetsTest {
    private val spec = listOf("values", "values-ja", "values-zh", "values-zh-rTW").associate {
        "assets/composeResources/ehviewer.app.shared.generated.resources/$it/strings.commonMain.cvr" to mapOf("gallery_back" to "返回-$it", "gallery_page_count" to "%1\$s pages-$it")
    }

    private fun compiled() = spec.mapValues { (_, values) ->
        "version:0\n" + values.entries.joinToString("\n", postfix = "\n") { (key, value) -> "string|$key|${Base64.getEncoder().encodeToString(value.toByteArray())}" }
    }

    private fun check(entries: Map<String, String>): Map<String, String> {
        val file = Files.createTempFile("ehviewer-owned-resource-assets-", ".apk")
        try {
            ZipOutputStream(Files.newOutputStream(file)).use { zip ->
                entries.forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
            }
            return ZipFile(file.toFile()).use { checkedGalleryResourceAssets(it, spec) }
        } finally {
            Files.delete(file)
        }
    }

    @Test fun allFourActualCompiledPayloadsMustMatchTheirSourceAndHaveRetainedHashes() {
        val hashes = check(compiled())
        assertEquals(spec.keys, hashes.keys)
        assertEquals(4, hashes.values.toSet().size)
        require(hashes.values.all { Regex("[0-9a-f]{64}").matches(it) })
    }

    @Test fun missingStaleMalformedAndDuplicatePayloadsCannotPassAsPackagedResources() {
        val valid = compiled()
        val first = valid.keys.first()
        val body = valid.getValue(first)
        for (bad in listOf(
            emptyMap(),
            valid - first,
            valid + (first to ""),
            valid + (first to body.replace("version:0", "version:1")),
            valid + (first to body.replace("string|", "invalid|")),
            valid + (first to body.replace("gallery_back", "stale_key")),
            valid + (first to "$body${body.lineSequence().drop(1).first()}\n"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { check(bad) }
        }
    }
}
