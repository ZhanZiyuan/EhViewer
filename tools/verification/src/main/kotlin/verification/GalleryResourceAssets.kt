package verification

import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readBytes
import org.w3c.dom.Element

/** Compare APK assets with same-source XML, without depending on another runner's generated files. */
fun galleryResourceAssetSpec(repository: Path = root): Map<String, Map<String, String>> = listOf("values", "values-zh", "values-zh-rTW", "values-ja").associate { locale ->
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
    val strings = document.getElementsByTagName("string")
    val values = linkedMapOf<String, String>()
    for (index in 0 until strings.length) {
        val item = strings.item(index) as Element
        val key = item.getAttribute("name")
        require(key.isNotBlank() && values.put(key, item.textContent) == null) { "Duplicate or empty source string key" }
    }
    require(values.isNotEmpty())
    "assets/composeResources/ehviewer.app.shared.generated.resources/$locale/strings.commonMain.cvr" to values
}

fun checkedGalleryResourceAssets(zip: ZipFile, expected: Map<String, Map<String, String>>): Map<String, String> {
    require(expected.isNotEmpty())
    return expected.mapValues { (name, strings) ->
        val entries = zip.entries().asSequence().filter { it.name == name }.toList()
        require(entries.size == 1 && !entries.single().isDirectory && entries.single().size in 1..1_048_576) { "Missing, duplicate or invalid shared resource asset: $name" }
        val bytes = zip.getInputStream(entries.single()).use { it.readNBytes(1_048_577) }
        require(bytes.size <= 1_048_576)
        val lines = bytes.decodeToString(throwOnInvalidSequence = true).lineSequence().filter(String::isNotBlank).toList()
        require(lines.firstOrNull() == "version:0") { "Unsupported compiled string format: $name" }
        val actual = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val fields = line.split('|', limit = 3)
            require(fields.size == 3 && fields[0] == "string") { "Invalid compiled string record: $name" }
            val value = Base64.getDecoder().decode(fields[2]).decodeToString(throwOnInvalidSequence = true)
            require(actual.put(fields[1], value) == null) { "Duplicate compiled string: $name" }
        }
        require(actual == strings) { "Packaged shared strings differ from this source: $name" }
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
