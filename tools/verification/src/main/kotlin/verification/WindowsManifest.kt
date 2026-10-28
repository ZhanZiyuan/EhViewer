package verification

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import org.w3c.dom.Element
import org.w3c.dom.Node

private const val ASSEMBLY = "urn:schemas-microsoft-com:asm.v1"
private const val APPLICATION = "urn:schemas-microsoft-com:asm.v3"
private const val CODE_PAGE = "http://schemas.microsoft.com/SMI/2019/WindowsSettings"

private fun manifest(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
}.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).also {
    require(it.documentElement.namespaceURI == ASSEMBLY && it.documentElement.localName == "assembly" && it.documentElement.getAttribute("manifestVersion") == "1.0") { "Expected a Win32 application manifest" }
}

/** Preserve the vendor's identity, permissions, DPI and compatibility settings. */
fun windowsUtf8Manifest(original: ByteArray): ByteArray {
    val document = manifest(original)
    fun child(parent: Element, namespace: String, name: String): Element {
        val matches = (0 until parent.childNodes.length).map { parent.childNodes.item(it) }
            .filterIsInstance<Element>().filter { it.namespaceURI == namespace && it.localName == name }
        require(matches.size <= 1) { "Duplicate manifest setting: $name" }
        return matches.singleOrNull() ?: document.createElementNS(namespace, name).also { parent.appendChild(it) }
    }
    val application = child(document.documentElement, APPLICATION, "application")
    val settings = child(application, APPLICATION, "windowsSettings")
    child(settings, CODE_PAGE, "activeCodePage").textContent = "UTF-8"
    return ByteArrayOutputStream().use { output ->
        TransformerFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        }.newTransformer().apply { setOutputProperty(OutputKeys.ENCODING, "UTF-8") }
            .transform(DOMSource(document), StreamResult(output))
        output.toByteArray()
    }
}

private fun canonical(node: Node): Any? = when (node.nodeType) {
    Node.ELEMENT_NODE -> {
        val attributes = (0 until node.attributes.length).map { node.attributes.item(it) }
            .filter { it.namespaceURI != XMLConstants.XMLNS_ATTRIBUTE_NS_URI }
            .map { Triple(it.namespaceURI.orEmpty(), it.localName, it.nodeValue) }.sortedWith(compareBy({ it.first }, { it.second }))
        val children = (0 until node.childNodes.length).mapNotNull { canonical(node.childNodes.item(it)) }
        // Fusion settings are named entries; SDK serialization may reorder equivalent entries.
        listOf(node.namespaceURI.orEmpty(), node.localName, attributes, children.sortedBy { it.toString() })
    }
    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> node.nodeValue.trim().takeIf { it.isNotEmpty() }
    else -> null
}

/** SDK resource read-back must preserve every original semantic setting and the UTF-8 addition. */
fun verifyWindowsUtf8Manifest(original: ByteArray, embedded: ByteArray) {
    require(canonical(manifest(windowsUtf8Manifest(original)).documentElement) == canonical(manifest(embedded).documentElement)) { "Embedded launcher manifest changed settings or lost UTF-8" }
}

fun windowsManifestTool(): Path {
    val sdk = System.getenv("WindowsSdkDir")?.let(Path::of)
        ?: Path.of(checkNotNull(System.getenv("ProgramFiles(x86)")), "Windows Kits", "10")
    val versions = Files.list(sdk.resolve("bin")).use { paths ->
        paths.filter { it.isDirectory() && Regex("[0-9]+(\\.[0-9]+){3}").matches(it.name) }.toList()
            .sortedByDescending { path -> path.name.split('.').joinToString(".") { it.padStart(10, '0') } }
    }
    for (architecture in listOf("arm64", "x64")) {
        versions.map { it.resolve("$architecture/mt.exe") }.firstOrNull { it.isRegularFile() }?.let { return it }
    }
    error("Windows SDK manifest tool is required")
}
