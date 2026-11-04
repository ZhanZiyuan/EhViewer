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
        val forms = if (it == "values") mapOf("ONE" to "%1\$s page", "OTHER" to "%1\$s pages") else mapOf("OTHER" to "%1\$s 頁-$it")
        "assets/composeResources/ehviewer.app.shared.generated.resources/$it/strings.commonMain.cvr" to GalleryResourceValues(mapOf("gallery_back" to "返回-$it", "gallery_page_count" to "%1\$s pages-$it"), mapOf("gallery_detail_page_count" to forms))
    }

    private fun compiled() = spec.mapValues { (_, values) ->
        val strings = values.strings.entries.map { (key, value) -> "string|$key|${Base64.getEncoder().encodeToString(value.toByteArray())}" }
        val plurals = values.plurals.entries.map { (key, forms) -> "plurals|$key|" + forms.entries.joinToString(",") { (quantity, value) -> "$quantity:${Base64.getEncoder().encodeToString(value.toByteArray())}" } }
        "version:0\n" + (strings + plurals).joinToString("\n", postfix = "\n")
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

    @Test fun missingChangedUnknownOrDuplicatePluralFormsCannotPass() {
        val valid = compiled()
        val first = valid.keys.first()
        val body = valid.getValue(first)
        val plural = body.lineSequence().single { it.startsWith("plurals|") }
        val one = plural.substringAfterLast('|').substringBefore(',')
        val other = plural.substringAfterLast(',')
        for (bad in listOf(
            body.replace("$one,", ""),
            body.replace(",$other", ""),
            body.replace(one, one.replace("ONE:", "ZERO:")),
            body.replace(other, other.replace("OTHER:", "UNKNOWN:")),
            body.replace(other, "$other,$other"),
            body.replace(other, "OTHER:???"),
            body.replace(plural, plural.substringBeforeLast('|') + "|OTHER:" + Base64.getEncoder().encodeToString("stale translation".toByteArray())),
            body + "$plural\n",
            body.replace(plural, ""),
        )) {
            assertThrows(IllegalArgumentException::class.java) { check(valid + (first to bad)) }
        }
    }

    @Test fun sourcePluralQuantitiesMustBeCompleteAndUnique() {
        val directory = Files.createTempDirectory("ehviewer-owned-plural-source-")
        try {
            val prefix = directory.resolve("app/shared/src/commonMain/composeResources")
            val valid = """<resources><string name="gallery_back">Back</string><plurals name="gallery_detail_page_count"><item quantity="one">%1${'$'}s page</item><item quantity="other">%1${'$'}s pages</item></plurals></resources>"""
            for (locale in listOf("values", "values-ja", "values-zh", "values-zh-rTW")) {
                val file = prefix.resolve("$locale/strings.xml")
                Files.createDirectories(file.parent)
                Files.writeString(file, valid)
            }
            val parsed = galleryResourceAssetSpec(directory)
            assertEquals(mapOf("ONE" to "%1\$s page", "OTHER" to "%1\$s pages"), parsed.values.first().plurals.getValue("gallery_detail_page_count"))
            val file = prefix.resolve("values/strings.xml")
            for (bad in listOf(
                valid.replace("quantity=\"other\"", "quantity=\"one\""),
                valid.replace("quantity=\"other\"", "quantity=\"invalid\""),
                valid.replace("<item quantity=\"other\">%1${'$'}s pages</item>", ""),
                valid.replace("</resources>", "<plurals name=\"gallery_detail_page_count\"><item quantity=\"other\">duplicate</item></plurals></resources>"),
            )) {
                Files.writeString(file, bad)
                assertThrows(IllegalArgumentException::class.java) { galleryResourceAssetSpec(directory) }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
