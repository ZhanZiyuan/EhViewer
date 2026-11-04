package verification

import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readBytes
import org.w3c.dom.Element

data class GalleryResourceValues(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>> = emptyMap())

private val pluralQuantities = setOf("ZERO", "ONE", "TWO", "FEW", "MANY", "OTHER")

/** Compare APK/IPA assets with same-source XML, including every plural form. */
fun galleryResourceAssetSpec(repository: Path = root): Map<String, GalleryResourceValues> = listOf("values", "values-zh", "values-zh-rTW", "values-ja").associate { locale ->
    val file = repository.resolve("app/shared/src/commonMain/composeResources/$locale/strings.xml")
    val document = DocumentBuilderFactory.newInstance().apply {
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }.newDocumentBuilder().parse(ByteArrayInputStream(file.readBytes())).documentElement
    require(document.tagName == "resources")
    val values = linkedMapOf<String, String>()
    val plurals = linkedMapOf<String, Map<String, String>>()
    for (index in 0 until document.childNodes.length) {
        val item = document.childNodes.item(index) as? Element ?: continue
        val key = item.getAttribute("name")
        require(key.isNotBlank()) { "Empty source resource key" }
        when (item.tagName) {
            "string" -> require(values.put(key, item.textContent) == null) { "Duplicate source string key" }
            "plurals" -> {
                val forms = linkedMapOf<String, String>()
                for (child in 0 until item.childNodes.length) {
                    val form = item.childNodes.item(child) as? Element ?: continue
                    val quantity = form.getAttribute("quantity")
                    require(form.tagName == "item" && quantity.uppercase(Locale.ROOT) in pluralQuantities && quantity == quantity.lowercase(Locale.ROOT)) { "Invalid source plural quantity" }
                    require(forms.put(quantity.uppercase(Locale.ROOT), form.textContent) == null) { "Duplicate source plural quantity" }
                }
                require("OTHER" in forms && plurals.put(key, forms) == null) { "Missing other form or duplicate source plural key" }
            }
            else -> throw IllegalArgumentException("Unsupported source resource type: ${item.tagName}")
        }
    }
    require(values.isNotEmpty())
    "assets/composeResources/ehviewer.app.shared.generated.resources/$locale/strings.commonMain.cvr" to GalleryResourceValues(values, plurals)
}

fun checkedGalleryResourceAssets(zip: ZipFile, expected: Map<String, GalleryResourceValues>): Map<String, String> {
    require(expected.isNotEmpty())
    return expected.mapValues { (name, source) ->
        val entries = zip.entries().asSequence().filter { it.name == name }.toList()
        require(entries.size == 1 && !entries.single().isDirectory && entries.single().size in 1..1_048_576) { "Missing, duplicate or invalid shared resource asset: $name" }
        val bytes = zip.getInputStream(entries.single()).use { it.readNBytes(1_048_577) }
        require(bytes.size <= 1_048_576)
        val lines = bytes.decodeToString(throwOnInvalidSequence = true).lineSequence().filter(String::isNotBlank).toList()
        require(lines.firstOrNull() == "version:0") { "Unsupported compiled string format: $name" }
        val actual = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, Map<String, String>>()
        fun decode(value: String): String {
            val decoded = Base64.getDecoder().decode(value)
            require(Base64.getEncoder().encodeToString(decoded) == value) { "Noncanonical compiled resource encoding: $name" }
            return decoded.decodeToString(throwOnInvalidSequence = true)
        }
        for (line in lines.drop(1)) {
            val fields = line.split('|', limit = 3)
            require(fields.size == 3 && fields[1].isNotBlank()) { "Invalid compiled resource record: $name" }
            when (fields[0]) {
                "string" -> require(actual.put(fields[1], decode(fields[2])) == null) { "Duplicate compiled string: $name" }
                "plurals" -> {
                    val forms = linkedMapOf<String, String>()
                    for (encoded in fields[2].split(',')) {
                        val form = encoded.split(':', limit = 2)
                        require(form.size == 2 && form[0] in pluralQuantities) { "Invalid compiled plural quantity: $name" }
                        require(forms.put(form[0], decode(form[1])) == null) { "Duplicate compiled plural quantity: $name" }
                    }
                    require("OTHER" in forms && plurals.put(fields[1], forms) == null) { "Missing other form or duplicate compiled plural: $name" }
                }
                else -> throw IllegalArgumentException("Unsupported compiled resource type: $name")
            }
        }
        require(GalleryResourceValues(actual, plurals) == source) { "Packaged shared strings or plurals differ from this source: $name" }
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
