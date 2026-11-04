package verification

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Structural ZIP/Mach-O fixtures only; these tests never claim a compiled or installed app. */
class IosIpaTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source = "a".repeat(40)
    private val version = "1.15.10"
    private val code = 180074
    private val app = "Payload/EhViewer.app/"

    private fun executable(cpu: Int = 0x0100000c, platform: Int = 2): ByteArray = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0, 0xfeedfacf.toInt())
        putInt(4, cpu)
        putInt(12, 2)
        putInt(16, 2)
        putInt(20, 48)
        putInt(32, 0x32)
        putInt(36, 24)
        putInt(40, platform)
        putInt(44, 15 shl 16)
        putInt(48, 26 shl 16)
        putInt(56, 0x80000028.toInt())
        putInt(60, 24)
        putLong(64, 80)
    }.array()

    private fun png(size: Int, transparent: Boolean = false): ByteArray = ByteArrayOutputStream().use { output ->
        val image = BufferedImage(size, size, if (transparent) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        check(ImageIO.write(image, "png", output))
        output.toByteArray()
    }

    private fun sourceInfo(dirty: Boolean = false, sha: String = source, release: String = version) = json(
        mapOf(
            "schema_version" to 1,
            "source_commit" to sha,
            "source_dirty" to dirty,
            "version" to release,
            "android_version_code" to code,
            "build_type" to "RELEASE",
            "signing" to "UNSIGNED_NOT_DIRECTLY_INSTALLABLE",
        ),
    ).toByteArray()

    private fun fixture(): MutableMap<String, ByteArray> {
        val files = linkedMapOf(
            app + "EhViewer" to executable(),
            app + "EhViewerBuild.json" to sourceInfo(),
            app + "Assets.car" to ("BOMStore".toByteArray() + ByteArray(64)),
            app + "AppIcon60x60@2x.png" to png(120),
            app + "AppIcon76x76@2x~ipad.png" to png(152),
            app + "Info.plist" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict>
                <key>CFBundleIdentifier</key><string>moe.tarsin.ehviewer</string>
                <key>CFBundleExecutable</key><string>EhViewer</string><key>CFBundlePackageType</key><string>APPL</string>
                <key>CFBundleShortVersionString</key><string>$version</string><key>CFBundleVersion</key><string>$code</string>
                <key>MinimumOSVersion</key><string>15.0</string><key>LSRequiresIPhoneOS</key><true/>
                <key>UIDeviceFamily</key><array><integer>1</integer><integer>2</integer></array>
                <key>CFBundleSupportedPlatforms</key><array><string>iPhoneOS</string></array><key>DTPlatformName</key><string>iphoneos</string>
                <key>CFBundleIcons</key><dict><key>CFBundlePrimaryIcon</key><dict><key>CFBundleIconName</key><string>AppIcon</string><key>CFBundleIconFiles</key><array><string>AppIcon60x60</string></array></dict></dict>
                <key>CFBundleIcons~ipad</key><dict><key>CFBundlePrimaryIcon</key><dict><key>CFBundleIconName</key><string>AppIcon</string><key>CFBundleIconFiles</key><array><string>AppIcon76x76</string></array></dict></dict>
                </dict></plist>
            """.trimIndent().toByteArray(),
            app + "licenses/EhViewer/LICENSE" to Files.readAllBytes(root.resolve("LICENSE")),
        )
        galleryResourceAssetSpec().forEach { (name, values) ->
            val strings = values.strings.entries.map { (key, value) -> "string|$key|${Base64.getEncoder().encodeToString(value.toByteArray())}" }
            val plurals = values.plurals.entries.map { (key, forms) -> "plurals|$key|" + forms.entries.joinToString(",") { (quantity, value) -> "$quantity:${Base64.getEncoder().encodeToString(value.toByteArray())}" } }
            files[app + "compose-resources/" + name.removePrefix("assets/")] = ("version:0\n" + (strings + plurals).joinToString("\n", postfix = "\n")).toByteArray()
        }
        val notices = root.resolve("app/desktop/licenses/session")
        Files.walk(notices).use { paths -> paths.filter(Files::isRegularFile).forEach { file -> files[app + "licenses/session/" + notices.relativize(file).toString().replace('\\', '/')] = Files.readAllBytes(file) } }
        for (name in listOf("libarchive/COPYING", "libarchive/ehviewer-origin.toml", "xz/COPYING", "xz/COPYING.0BSD", "xz/ehviewer-origin.toml")) files[app + "licenses/archive/$name"] = "owned structural notice fixture".toByteArray()
        return files
    }

    private fun archive(files: Map<String, ByteArray>, modes: Map<String, Int> = emptyMap()): Path {
        val path = temporary.newFolder().toPath().resolve("EhViewer-$version-arm64-unsigned.ipa")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val bytes = Files.readAllBytes(path)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val end = (bytes.size - 22 downTo 0).first { b.getInt(it) == 0x06054b50 }
        var cursor = b.getInt(end + 16)
        repeat(files.size) {
            check(b.getInt(cursor) == 0x02014b50)
            val size = b.getShort(cursor + 28).toInt() and 65535
            val name = bytes.copyOfRange(cursor + 46, cursor + 46 + size).toString(Charsets.UTF_8)
            b.putShort(cursor + 4, 0x0314)
            b.putInt(cursor + 38, (modes[name] ?: if (name == app + "EhViewer") 0x81ed else 0x81a4) shl 16)
            cursor += 46 + size + (b.getShort(cursor + 30).toInt() and 65535) + (b.getShort(cursor + 32).toInt() and 65535)
        }
        Files.write(path, bytes)
        return path
    }

    private fun checked(path: Path, allowDirty: Boolean = false) = verifyUnsignedIosIpa(path, source, version, code, allowDirty)
    private fun rejected(change: (MutableMap<String, ByteArray>) -> Unit) {
        val files = fixture().also(change)
        assertThrows(Exception::class.java) { checked(archive(files)) }
    }

    @Test fun packageInspectionKeepsDeviceAndPublicationAcceptanceExplicitlyOpen() {
        val result = checked(archive(fixture()))
        assertEquals("PASS", result["result"])
        assertEquals("IOS", result["native"].obj()["platform"])
        assertEquals(listOf(1, 2), result["uid_device_family"])
        assertEquals("NOT_EXECUTED", result["device_execution"])
        assertEquals(false, result["all_eleven_assets_verified"])
        assertEquals(false, result["publish_enabled"])
    }

    @Test fun wrongCpuSimulatorMacAndTruncatedExecutableAreRejected() {
        for (bytes in listOf(executable(cpu = 0x01000007), executable(platform = 7), executable(platform = 1), executable().copyOf(40), "renamed archive".toByteArray())) rejected { it[app + "EhViewer"] = bytes }
    }

    @Test fun signedOrMissingEntrypointExecutablesAreRejected() {
        for (command in listOf(0x1d, 0x19)) {
            rejected {
                it[app + "EhViewer"] = executable().also { bytes -> ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(56, command) }
            }
        }
        rejected { it[app + "embedded.mobileprovision"] = "not allowed".toByteArray() }
        rejected { it[app + "_CodeSignature/CodeResources"] = "not allowed".toByteArray() }
    }

    @Test fun executablePermissionsLinksWritableEntriesAndCaseCollisionsAreRejected() {
        for (mode in listOf(0x81a4, 0xa1ed, 0x81ff)) assertThrows(Exception::class.java) { checked(archive(fixture(), mapOf(app + "EhViewer" to mode))) }
        rejected { it[app + "info.plist"] = it.getValue(app + "Info.plist") }
        rejected { it[app + "../outside"] = "invalid".toByteArray() }
    }

    @Test fun localAndCentralNamesCannotDisagree() {
        val path = archive(fixture())
        val bytes = Files.readAllBytes(path)
        check(bytes[30] == 'P'.code.toByte())
        bytes[30] = 'Q'.code.toByte()
        Files.write(path, bytes)
        assertThrows(Exception::class.java) { checked(path) }
    }

    @Test fun aCorruptDeclaredChecksumCannotBeAcceptedAsValidPackageBytes() {
        val path = archive(fixture())
        val bytes = Files.readAllBytes(path)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val end = (bytes.size - 22 downTo 0).first { b.getInt(it) == 0x06054b50 }
        val central = b.getInt(end + 16)
        b.putInt(central + 16, b.getInt(central + 16) xor 1)
        Files.write(path, bytes)
        assertThrows(Exception::class.java) { checked(path) }
    }

    @Test fun sourceVersionAndDirtyStateAreValidatedWithoutClaimingCleanLocalOutput() {
        rejected { it[app + "EhViewerBuild.json"] = sourceInfo(sha = "b".repeat(40)) }
        rejected { it[app + "EhViewerBuild.json"] = sourceInfo(release = "1.16.0") }
        val dirty = archive(fixture().apply { this[app + "EhViewerBuild.json"] = sourceInfo(dirty = true) })
        assertThrows(Exception::class.java) { checked(dirty) }
        assertEquals(true, checked(dirty, allowDirty = true)["source_dirty"])
    }

    @Test fun missingIpadSupportDuplicatePlistKeysAndEntitiesCannotPass() {
        rejected { it[app + "Info.plist"] = it.getValue(app + "Info.plist").toString(Charsets.UTF_8).replace("<integer>2</integer>", "").toByteArray() }
        rejected { it[app + "Info.plist"] = it.getValue(app + "Info.plist").toString(Charsets.UTF_8).replace("<key>CFBundleExecutable</key>", "<key>CFBundleIdentifier</key><string>duplicate</string><key>CFBundleExecutable</key>").toByteArray() }
        val entity = """<!DOCTYPE plist [<!ENTITY x SYSTEM "file:///never-read">]><plist version="1.0"><dict><key>owned</key><string>&x;</string></dict></plist>"""
        assertThrows(Exception::class.java) { iosPlist(entity.toByteArray()) }
    }

    @Test fun missingOrChangedResourcesAndNoticesAreRejected() {
        rejected { files -> files.remove(files.keys.first { it.endsWith(".cvr") }) }
        rejected { files -> files[files.keys.first { it.endsWith(".cvr") }] = "version:0\nstring|wrong|eA==\n".toByteArray() }
        rejected { it.remove(app + "licenses/EhViewer/LICENSE") }
        rejected { files -> files.remove(files.keys.first { it.startsWith(app + "licenses/session/") }) }
    }

    @Test fun invalidCatalogMissingIconAndTransparentIconAreRejected() {
        rejected { it[app + "Assets.car"] = "not an asset catalog".toByteArray() }
        rejected { it.remove(app + "AppIcon76x76@2x~ipad.png") }
        rejected { it[app + "AppIcon60x60@2x.png"] = png(120, transparent = true) }
    }
}
