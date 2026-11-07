package verification

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.xml.sax.SAXParseException

class WindowsManifestTest {
    private val assembly = "urn:schemas-microsoft-com:asm.v1"
    private val application = "urn:schemas-microsoft-com:asm.v3"
    private val codePage = "http://schemas.microsoft.com/SMI/2019/WindowsSettings"
    private fun parse(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    @Test
    fun preservesPrivilegesDpiIdentityAndCompatibilityAndRejectsTamperedReadBack() {
        val original = """
            <assembly xmlns="$assembly" xmlns:a="$application" manifestVersion="1.0">
              <assemblyIdentity name="test.launcher" version="1.0.0.0" processorArchitecture="arm64" type="win32"/>
              <a:trustInfo><a:security><a:requestedPrivileges><a:requestedExecutionLevel level="asInvoker" uiAccess="false"/></a:requestedPrivileges></a:security></a:trustInfo>
              <a:application><a:windowsSettings><dpiAware xmlns="http://schemas.microsoft.com/SMI/2005/WindowsSettings">true/PM</dpiAware></a:windowsSettings></a:application>
              <compatibility xmlns="urn:schemas-microsoft-com:compatibility.v1"><application><supportedOS Id="preserve-os"/></application></compatibility>
            </assembly>
        """.trimIndent().toByteArray()
        val updated = windowsUtf8Manifest(original)
        verifyWindowsUtf8Manifest(original, updated)
        val document = parse(updated)
        assertEquals("UTF-8", document.getElementsByTagNameNS(codePage, "activeCodePage").item(0).textContent)
        assertEquals("asInvoker", document.getElementsByTagNameNS(application, "requestedExecutionLevel").item(0).attributes.getNamedItem("level").nodeValue)
        assertEquals("true/PM", document.getElementsByTagNameNS("http://schemas.microsoft.com/SMI/2005/WindowsSettings", "dpiAware").item(0).textContent)
        assertEquals("test.launcher", document.getElementsByTagNameNS(assembly, "assemblyIdentity").item(0).attributes.getNamedItem("name").nodeValue)
        assertEquals("preserve-os", document.getElementsByTagNameNS("urn:schemas-microsoft-com:compatibility.v1", "supportedOS").item(0).attributes.getNamedItem("Id").nodeValue)
        document.documentElement.appendChild(document.getElementsByTagNameNS(assembly, "assemblyIdentity").item(0))
        val reordered = ByteArrayOutputStream().use { output ->
            TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(output))
            output.toByteArray()
        }
        verifyWindowsUtf8Manifest(original, reordered)
        val tampered = updated.toString(Charsets.UTF_8).replace("asInvoker", "requireAdministrator").toByteArray()
        assertThrows(IllegalArgumentException::class.java) { verifyWindowsUtf8Manifest(original, tampered) }
        assertThrows(IllegalArgumentException::class.java) { verifyWindowsUtf8Manifest(original, original) }
    }

    @Test
    fun createsNamespacedSettingsExactlyOnce() {
        val original = """<assembly xmlns="$assembly" manifestVersion="1.0"/>""".toByteArray()
        val once = windowsUtf8Manifest(original)
        val twice = windowsUtf8Manifest(once)
        verifyWindowsUtf8Manifest(original, twice)
        val document = parse(twice)
        assertEquals(1, document.getElementsByTagNameNS(application, "application").length)
        assertEquals(1, document.getElementsByTagNameNS(application, "windowsSettings").length)
        assertEquals(1, document.getElementsByTagNameNS(codePage, "activeCodePage").length)
    }

    @Test
    fun rejectsExternalDoctypesAndAmbiguousApplicationSettings() {
        val external = """<!DOCTYPE assembly SYSTEM "file:///not-an-authorized-input"><assembly xmlns="$assembly" manifestVersion="1.0"/>""".toByteArray()
        assertThrows(SAXParseException::class.java) { windowsUtf8Manifest(external) }
        val duplicate = """<assembly xmlns="$assembly" xmlns:a="$application" manifestVersion="1.0"><a:application/><a:application/></assembly>""".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { windowsUtf8Manifest(duplicate) }
    }
}
