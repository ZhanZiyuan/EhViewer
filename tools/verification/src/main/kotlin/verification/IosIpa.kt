package verification

import groovy.json.JsonSlurper
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.w3c.dom.Element
import org.w3c.dom.Node

private const val IPA_APP = "Payload/EhViewer.app/"
private const val IPA_ENTRY_LIMIT = 256 * 1024 * 1024
private const val IPA_TOTAL_LIMIT = 1024L * 1024 * 1024

private fun ipaHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Inspect Unix mode/type in the real central directory; never extract an archive-provided path. */
fun iosZipModes(archive: Path): Map<String, Int> = RandomAccessFile(archive.toFile(), "r").use { input ->
    require(input.length() in 22..IPA_TOTAL_LIMIT)
    val tail = ByteArray(minOf(input.length(), 65_557).toInt())
    input.seek(input.length() - tail.size)
    input.readFully(tail)
    val end = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
    val offset = (tail.size - 22 downTo 0).firstOrNull {
        end.getInt(it) == 0x06054b50 && it + 22 + (end.getShort(it + 20).toInt() and 65535) == tail.size
    } ?: error("Missing canonical ZIP directory")
    require(end.getShort(offset + 4).toInt() == 0 && end.getShort(offset + 6).toInt() == 0)
    val count = end.getShort(offset + 10).toInt() and 65535
    require(count in 1..20_000 && count == (end.getShort(offset + 8).toInt() and 65535))
    val size = end.getInt(offset + 12).toLong() and 0xffffffffL
    val start = end.getInt(offset + 16).toLong() and 0xffffffffL
    require(size in 46..(16 * 1024 * 1024) && start + size == input.length() - tail.size + offset) { "Unsupported ZIP64, split or trailing directory" }
    val bytes = ByteArray(size.toInt())
    input.seek(start)
    input.readFully(bytes)
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val modes = linkedMapOf<String, Int>()
    var cursor = 0
    repeat(count) {
        require(cursor <= bytes.size - 46 && b.getInt(cursor) == 0x02014b50)
        val flags = b.getShort(cursor + 8).toInt() and 65535
        val method = b.getShort(cursor + 10).toInt() and 65535
        require(flags and 1 == 0 && method in setOf(0, 8)) { "Encrypted or unsupported IPA entry" }
        val nameSize = b.getShort(cursor + 28).toInt() and 65535
        val extra = b.getShort(cursor + 30).toInt() and 65535
        val comment = b.getShort(cursor + 32).toInt() and 65535
        val next = cursor + 46 + nameSize + extra + comment
        require(nameSize > 0 && next <= bytes.size && b.getShort(cursor + 34).toInt() == 0)
        val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, cursor + 46, nameSize)).toString()
        stageZipName(name.removeSuffix("/"))
        require(name in setOf("Payload/", IPA_APP) || name.startsWith(IPA_APP)) { "Unexpected IPA root" }
        require((b.getShort(cursor + 4).toInt() ushr 8) == 3) { "IPA must retain actual Unix permissions" }
        val mode = b.getInt(cursor + 38) ushr 16
        require(mode and 0xf000 == if (name.endsWith('/')) 0x4000 else 0x8000) { "Links and special IPA entries are forbidden" }
        require(mode and 0xe12 == 0) { "Special or group/other-writable IPA permissions" }
        require(mode and 0x1ff == if (name.endsWith('/') || name == IPA_APP + "EhViewer") 0x1ed else 0x1a4) { "Unexpected IPA file/directory permissions" }
        val localOffset = b.getInt(cursor + 42).toLong() and 0xffffffffL
        require(localOffset + 30 <= start)
        val local = ByteArray(30)
        input.seek(localOffset)
        input.readFully(local)
        val header = ByteBuffer.wrap(local).order(ByteOrder.LITTLE_ENDIAN)
        require(header.getInt(0) == 0x04034b50 && (header.getShort(6).toInt() and 65535) == flags && (header.getShort(8).toInt() and 65535) == method)
        val localNameSize = header.getShort(26).toInt() and 65535
        require(localNameSize == nameSize && localOffset + 30 + localNameSize + (header.getShort(28).toInt() and 65535) <= start)
        val localName = ByteArray(localNameSize)
        input.readFully(localName)
        require(localName.contentEquals(bytes.copyOfRange(cursor + 46, cursor + 46 + nameSize))) { "Ambiguous local/central IPA path" }
        require(modes.put(name, mode) == null)
        cursor = next
    }
    require(cursor == bytes.size && modes.keys.map { it.lowercase(Locale.ROOT) }.toSet().size == modes.size) { "Duplicate or case-colliding IPA paths" }
    modes.keys.forEach { name ->
        var parent = name.removeSuffix("/").substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            require(parent !in modes) { "IPA file collides with a directory" }
            parent = parent.substringBeforeLast('/', "")
        }
    }
    modes
}

/** Bound XML and disable entity/DTD resolution while accepting Apple's standard plist declaration. */
fun iosPlist(bytes: ByteArray): Map<String, Any?> {
    require(bytes.size in 1..65_536)
    val text = bytes.decodeToString(throwOnInvalidSequence = true)
    require(!text.contains("<!ENTITY", ignoreCase = true)) { "Plist entities are forbidden" }
    val document = DocumentBuilderFactory.newInstance().apply {
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    require(document.doctype?.internalSubset.isNullOrBlank())
    fun children(element: Element): List<Element> = (0 until element.childNodes.length).map(element.childNodes::item)
        .filter { it.nodeType == Node.ELEMENT_NODE }.map { it as Element }
    fun value(element: Element, depth: Int): Any? {
        require(depth <= 12)
        return when (element.tagName) {
            "dict" -> {
                val items = children(element)
                require(items.size <= 2048 && items.size % 2 == 0)
                val result = linkedMapOf<String, Any?>()
                items.chunked(2).forEach { (key, item) ->
                    require(key.tagName == "key" && key.textContent.isNotEmpty() && key.textContent !in result)
                    result[key.textContent] = value(item, depth + 1)
                }
                result
            }
            "array" -> children(element).also { require(it.size <= 1024) }.map { value(it, depth + 1) }
            "string" -> element.textContent
            "integer" -> element.textContent.toInt()
            "true" -> true
            "false" -> false
            else -> error("Unsupported plist value")
        }
    }
    val plist = document.documentElement
    require(plist.tagName == "plist" && plist.getAttribute("version") == "1.0" && children(plist).size == 1)
    return value(children(plist).single(), 0).obj()
}

/** Portable package inspection only. An unsigned device executable is not a device execution test. */
fun verifyUnsignedIosIpa(archive: Path, source: String, version: String, code: Int, allowDirty: Boolean = false): Map<String, Any?> {
    require(Regex("[0-9a-f]{40}").matches(source) && source != "0".repeat(40) && code > 0)
    require(versionFromTag(version) == version)
    require(archive.fileName.toString() == releaseTargets.single { it.id == "ios-arm64" }.fileName(version))
    require(Files.isRegularFile(archive, NOFOLLOW_LINKS))
    val modes = iosZipModes(archive)
    require(modes[IPA_APP + "EhViewer"]?.and(0x1ff) == 0x1ed) { "Missing executable 0755 entry" }
    require(modes.keys.none { it.contains("_CodeSignature") || it.endsWith(".mobileprovision") || it.endsWith(".entitlements") || it.endsWith(".p12") || it.endsWith(".jks") }) { "Unsigned IPA contains signing material" }
    val files = linkedMapOf<String, ByteArray>()
    val inventory = mutableListOf<Map<String, Any?>>()
    ZipFile(archive.toFile()).use { zip ->
        val entries = zip.entries().asSequence().toList()
        require(entries.size == modes.size && entries.map { it.name }.toSet() == modes.keys)
        var total = 0L
        var retainedBytes = 0L
        var iconCount = 0
        for (entry in entries) {
            require(entry.size in 0..IPA_ENTRY_LIMIT)
            total += entry.size
            require(total <= IPA_TOTAL_LIMIT)
            if (entry.isDirectory) {
                require(entry.size == 0L)
                continue
            }
            val icon = entry.name.removePrefix(IPA_APP).matches(Regex("AppIcon[^/]+\\.png"))
            if (icon) require(++iconCount <= 16 && entry.size <= 16 * 1024 * 1024)
            if (entry.name == IPA_APP + "Info.plist") require(entry.size <= 65_536)
            if (entry.name == IPA_APP + "EhViewerBuild.json") require(entry.size <= 16_384)
            if (entry.name == IPA_APP + "Assets.car") require(entry.size <= 32 * 1024 * 1024)
            val bytes = zip.getInputStream(entry).use { it.readNBytes(IPA_ENTRY_LIMIT + 1) }
            require(bytes.size.toLong() == entry.size && bytes.size <= IPA_ENTRY_LIMIT && CRC32().apply { update(bytes) }.value == entry.crc)
            inventory += mapOf("path" to entry.name, "bytes" to bytes.size, "sha256" to ipaHash(bytes), "unix_mode" to modes.getValue(entry.name))
            if (entry.name in setOf(IPA_APP + "EhViewer", IPA_APP + "Info.plist", IPA_APP + "EhViewerBuild.json", IPA_APP + "Assets.car") || icon) {
                retainedBytes += bytes.size
                require(retainedBytes <= 384L * 1024 * 1024)
                files[entry.name] = bytes
            }
        }
        fun required(name: String) = requireNotNull(files[IPA_APP + name]) { "Missing IPA file: $name" }
        val sourceBytes = required("EhViewerBuild.json").also { require(it.size in 1..16_384) }
        val metadata = JsonSlurper().parseText(sourceBytes.decodeToString(throwOnInvalidSequence = true)).obj()
        require(metadata.keys == setOf("schema_version", "source_commit", "source_dirty", "version", "android_version_code", "build_type", "signing"))
        require(metadata["schema_version"] == 1 && metadata["source_commit"] == source && metadata["version"] == version && metadata["android_version_code"] == code && metadata["build_type"] == "RELEASE" && metadata["signing"] == "UNSIGNED_NOT_DIRECTLY_INSTALLABLE")
        require(metadata["source_dirty"] is Boolean && (metadata["source_dirty"] == false || allowDirty)) { "A clean-source IPA is required" }
        val info = iosPlist(required("Info.plist"))
        require(info["CFBundleIdentifier"] == "moe.tarsin.ehviewer" && info["CFBundleExecutable"] == "EhViewer" && info["CFBundlePackageType"] == "APPL")
        require(info["CFBundleShortVersionString"] == version && info["CFBundleVersion"] == code.toString() && info["MinimumOSVersion"] == "15.0")
        require(info["UIDeviceFamily"] == listOf(1, 2) && info["LSRequiresIPhoneOS"] == true && info["CFBundleSupportedPlatforms"] == listOf("iPhoneOS") && info["DTPlatformName"] == "iphoneos")
        val executable = required("EhViewer")
        val native = verifyAppleArm64Executable(executable, simulator = false)
        require(native["declared_minimum_ios"] == "15.0.0")
        val b = ByteBuffer.wrap(executable).order(ByteOrder.LITTLE_ENDIAN)
        var cursor = 32
        var entryPoint = false
        repeat(b.getInt(16)) {
            require(b.getInt(cursor) != 0x1d) { "Unsigned executable contains a code signature" }
            if (b.getInt(cursor) == 0x80000028.toInt()) {
                require(!entryPoint && b.getInt(cursor + 4) >= 24 && b.getLong(cursor + 8) in (32L + b.getInt(20)) until executable.size.toLong())
                entryPoint = true
            }
            cursor += b.getInt(cursor + 4)
        }
        require(entryPoint) { "Missing actual executable entry point" }
        val catalog = required("Assets.car")
        require(catalog.size in 64..(32 * 1024 * 1024) && catalog.copyOfRange(0, 8).contentEquals("BOMStore".toByteArray(Charsets.US_ASCII))) { "Missing compiled Apple asset catalog" }
        for (key in listOf("CFBundleIcons", "CFBundleIcons~ipad")) {
            val primary = info.getValue(key).obj()["CFBundlePrimaryIcon"].obj()
            require(primary["CFBundleIconName"] == "AppIcon")
            val names = primary["CFBundleIconFiles"].array().map { it as String }
            require(names.isNotEmpty() && names.all { it.startsWith("AppIcon") && it.none { ch -> ch in "/\\:" } })
            for (name in names) {
                val icons = files.filterKeys { it.startsWith(IPA_APP + name) && it.endsWith(".png") }
                require(icons.isNotEmpty())
                icons.values.forEach { bytes ->
                    require(bytes.size in 32..(16 * 1024 * 1024))
                    val png = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                    require(png.getLong(0) == 0x89504e470d0a1a0aUL.toLong() && png.getInt(12) == 0x49484452 && png.getInt(16) in 60..1024 && png.getInt(20) == png.getInt(16))
                    val image = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes))) { "Invalid actual icon PNG" }
                    require(image.width in 60..1024 && image.height == image.width)
                    for (y in 0 until image.height) for (x in 0 until image.width) require(image.getRGB(x, y) ushr 24 == 255) { "iOS icons must be opaque" }
                }
            }
        }
        val resourceSpec = galleryResourceAssetSpec().mapKeys { (name, _) -> IPA_APP + "compose-resources/" + name.removePrefix("assets/") }
        val resources = checkedGalleryResourceAssets(zip, resourceSpec)
        require(inventory.single { it["path"] == IPA_APP + "licenses/EhViewer/LICENSE" }["sha256"] == sha256(root.resolve("LICENSE")))
        Files.walk(root.resolve("app/desktop/licenses/session")).use { paths ->
            paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.forEach { notice ->
                val name = root.resolve("app/desktop/licenses/session").relativize(notice).toString().replace('\\', '/')
                require(inventory.single { it["path"] == IPA_APP + "licenses/session/$name" }["sha256"] == sha256(notice)) { "Missing or altered session notice" }
            }
        }
        for (name in listOf("libarchive/COPYING", "libarchive/ehviewer-origin.toml", "xz/COPYING", "xz/COPYING.0BSD", "xz/ehviewer-origin.toml")) {
            require(inventory.any { it["path"] == IPA_APP + "licenses/archive/$name" && (it["bytes"] as Number).toLong() > 0 })
        }
        return mapOf(
            "schema_version" to 1, "verification_scope" to "UNSIGNED_IOS_IPA_PACKAGE_ONLY", "source_commit" to source, "source_dirty" to metadata["source_dirty"],
            "version" to version, "android_version_code" to code, "file" to archive.fileName.toString(), "bytes" to Files.size(archive), "target" to "ios-arm64",
            "native" to native, "uid_device_family" to listOf(1, 2), "compiled_resources" to resources, "asset_catalog_sha256" to ipaHash(catalog),
            "files" to inventory.sortedBy { it.str("path") }, "signing" to "UNSIGNED_NOT_DIRECTLY_INSTALLABLE", "device_execution" to "NOT_EXECUTED",
            "minimum_system_runtime" to "NOT_VERIFIED", "complete_license_sbom" to "NOT_VERIFIED", "product_p0_verified" to false, "all_eleven_assets_verified" to false, "publish_enabled" to false, "result" to "PASS",
        )
    }
}

/** Copy once into a fresh owned transport directory, then inspect exactly the bytes to be uploaded. */
fun stageUnsignedIosIpa(archive: Path, output: Path, allowDirty: Boolean = false): Map<String, Any?> {
    require(!allowDirty || System.getenv("GITHUB_ACTIONS") != "true") { "Dirty-source permission is local-only" }
    val source = command("git", "rev-parse", "HEAD").trim()
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(!dirty || allowDirty)
    val contract = releaseContract(root.resolve("app/build.gradle.kts").readText(), source)
    val version = contract.str("version")
    val code = (contract["android_version_code"] as Number).toInt()
    require(archive.fileName.toString() == releaseTargets.single { it.id == "ios-arm64" }.fileName(version))
    require(Files.isRegularFile(archive, NOFOLLOW_LINKS) && Files.size(archive) in 1..IPA_TOTAL_LIMIT)
    val destination = output.toAbsolutePath().normalize()
    require(!destination.exists(NOFOLLOW_LINKS) && !archive.toRealPath().startsWith(destination)) { "Use a fresh owned IPA output directory" }
    Files.createDirectories(requireNotNull(destination.parent))
    require(destination.parent.toRealPath() == destination.parent) { "IPA output parent must not traverse links" }
    Files.createDirectory(destination)
    val retained = destination.resolve(archive.fileName)
    Files.copy(archive, retained, COPY_ATTRIBUTES)
    val proof = verifyUnsignedIosIpa(retained, source, version, code, allowDirty)
    require(proof["source_dirty"] == dirty) { "Package source state differs from its build checkout" }
    val result = proof + mapOf("archive_sha256" to sha256(retained))
    destination.resolve("ipa.json").writeText(json(result))
    return result
}
