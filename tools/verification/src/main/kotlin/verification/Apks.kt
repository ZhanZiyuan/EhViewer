package verification

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeBytes

val jniExports = setOf("JNI_OnLoad", "Java_com_hippo_ehviewer_jni_HashKt_sha1") +
    listOf(
        "openArchive", "closeArchive", "extractToByteBuffer", "extractToFd", "releaseByteBuffer",
        "getExtension", "needPassword", "providePassword", "archiveFdBatch",
    ).map { "Java_com_hippo_ehviewer_jni_ArchiveKt_$it" } +
    listOf("isGif", "rewriteGifSource", "mmap", "munmap").map { "Java_com_hippo_ehviewer_jni_GifUtilsKt_$it" }

fun elfAlignment(data: ByteArray, abi: String): List<Long> {
    require(data.size >= 52 && data.take(4) == listOf<Byte>(0x7f, 69, 76, 70) && data[5] == 1.toByte()) { "Expected little-endian ELF" }
    val is64 = data[4] == 2.toByte()
    require(data[4].toInt() in 1..2 && is64 == (abi != "armeabi-v7a")) { "Incorrect ELF class" }
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    fun short(offset: Int): Int = buffer.getShort(offset).toInt() and 0xffff
    require(short(18) == abis[abi]) { "Incorrect ELF machine" }
    val phoff = if (is64) buffer.getLong(32) else buffer.getInt(28).toLong() and 0xffffffffL
    val size = short(if (is64) 54 else 42)
    val count = short(if (is64) 56 else 44)
    require(size >= if (is64) 56 else 32) { "Invalid program header size" }
    require(phoff >= 0 && phoff <= data.size && count.toLong() * size <= data.size - phoff) { "Truncated ELF program headers" }
    val loads = (0 until count).mapNotNull { index ->
        val offset = (phoff + index.toLong() * size).toInt()
        if (buffer.getInt(offset) != 1) return@mapNotNull null
        fun field(position: Int): Long = if (is64) buffer.getLong(offset + position) else buffer.getInt(offset + position).toLong() and 0xffffffffL
        val fileOffset = field(if (is64) 8 else 4)
        val address = field(if (is64) 16 else 8)
        val alignment = field(if (is64) 48 else 28)
        require(alignment >= 4096 && alignment and (alignment - 1) == 0L && (fileOffset - address) % alignment == 0L) { "Invalid LOAD alignment" }
        require(!is64 || alignment >= 16384) { "64-bit LOAD not 16KB aligned" }
        alignment
    }
    require(loads.isNotEmpty()) { "ELF has no LOAD segments" }
    return loads
}

data class ApkInput(val abi: String, val file: String, val version: String, val code: Int)

fun apkInputs(metadata: Map<String, Any?>, applicationId: String): List<ApkInput> {
    require(metadata["artifactType"].obj()["type"] == "APK" && metadata["applicationId"] == applicationId) { "Unexpected artifact/application ID" }
    val inputs = metadata["elements"].array().map { value ->
        val element = value.obj()
        val filters = element["filters"].array().map { it.obj() }
        require(filters.isEmpty() || filters.size == 1 && filters[0]["filterType"] == "ABI") { "Unexpected APK filters" }
        val abi = if (filters.isEmpty()) "universal" else filters[0].str("value")
        val file = element.str("outputFile")
        require(file.endsWith(".apk") && '/' !in file && '\\' !in file && Path.of(file).fileName.toString() == file) { "Unsafe APK filename" }
        val code = element["versionCode"]
        require(code is Int && code > 0) { "Invalid versionCode" }
        ApkInput(abi, file, element.str("versionName"), code)
    }
    require(inputs.size == 4 && inputs.map { it.abi }.toSet() == abis.keys + "universal") { "Expected three splits and universal APK" }
    require(inputs.map { it.file }.toSet().size == 4) { "Duplicate input filename" }
    require(inputs.map { it.code }.toSet().size == 1 && inputs.map { it.version }.toSet().size == 1) { "Inconsistent APK versions" }
    return inputs
}

fun verifyApks(directory: Path, tools: AndroidTools, applicationId: String = "moe.tarsin.ehviewer", release: Boolean = true, code: Int? = null): List<Map<String, Any?>> {
    val inputs = apkInputs(readJson(directory.resolve("output-metadata.json")), applicationId)
    require(code == null || inputs.first().code == code) { "Unexpected versionCode" }
    require(directory.listDirectoryEntries("*.apk").map { it.name }.toSet() == inputs.map { it.file }.toSet()) { "Unexpected/stale APK" }
    val signingCertificates = mutableSetOf<List<String>>()
    return inputs.map { input ->
        val apk = directory.resolve(input.file)
        require(!apk.isSymbolicLink() && apk.toRealPath().parent == directory.toRealPath()) { "APK escapes output directory" }
        val badging = command(tools.buildTools.resolve("aapt2"), "dump", "badging", apk)
        require(
            listOf(
                "package: name='$applicationId'",
                "minSdkVersion:'26'",
                "targetSdkVersion:'37'",
                "versionCode='${input.code}'",
                "versionName='${input.version}'",
            ).all(badging::contains),
        ) { "Packaged manifest identity/version/SDK mismatch" }
        val signing = command(tools.buildTools.resolve("apksigner"), "verify", "--verbose", "--print-certs", apk)
        val certificates = Regex("certificate SHA-256 digest: ([0-9a-f]+)").findAll(signing).map { it.groupValues[1] }.toList()
        require(certificates.size == 1 && (!release || certificates == listOf(CERTIFICATE))) { "APK certificate mismatch" }
        signingCertificates += certificates
        require(signingCertificates.size == 1) { "APK splits have different signing certificates" }
        command(tools.buildTools.resolve("zipalign"), "-c", "-P", "16", "-v", "4", apk)
        val libraries = mutableMapOf<String, List<Long>>()
        val actualAbis = mutableSetOf<String>()
        temporary { temp ->
            ZipFile(apk.toFile()).use { zip ->
                val entries = zip.entries().asSequence().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }.toList()
                require(entries.map { it.name }.toSet().size == entries.size) { "Duplicate native ZIP entry" }
                entries.forEach { entry ->
                    val parts = entry.name.split('/')
                    require(parts.size == 3 && parts[1] in abis) { "Unexpected packaged ABI" }
                    val abi = parts[1]
                    actualAbis += abi
                    val data = zip.getInputStream(entry).use { it.readBytes() }
                    libraries[entry.name] = elfAlignment(data, abi)
                    if (parts[2] == "libehviewer.so") {
                        val file = temp.resolve("$abi.so").apply { writeBytes(data) }
                        val exports = command(tools.llvm.resolve("llvm-nm"), "--dynamic", "--defined-only", file)
                            .lineSequence().filter { it.isNotBlank() }.map { it.trim().split(Regex("\\s+")).last() }.toList()
                        require(exports.size == exports.toSet().size && exports.containsAll(jniExports)) { "Missing/duplicate JNI exports" }
                        require(exports.any { "ParserKt_" in it }) { "Rust JNI parser exports missing" }
                    }
                }
            }
        }
        val expected = if (input.abi == "universal") abis.keys else setOf(input.abi)
        require(actualAbis == expected && expected.all { "lib/$it/libehviewer.so" in libraries }) { "Packaged native ABI set mismatch" }
        mapOf(
            "file" to input.file,
            "abi" to input.abi,
            "sha256" to sha256(apk),
            "versionCode" to input.code,
            "versionName" to input.version,
            "certificate" to certificates,
            "native" to libraries,
        )
    }
}

fun verifyDiagnostics(symbols: Path, apkDirectory: Path, tools: AndroidTools): List<Map<String, Any?>> = temporary { temp ->
    fun buildId(path: Path): String = Regex("Build ID: ([0-9a-f]+)").find(command(tools.llvm.resolve("llvm-readelf"), "--notes", path))?.groupValues?.get(1) ?: error("Native build ID missing")
    ZipFile(symbols.toFile()).use { zip ->
        val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
        require(names.size == 3 && names.toSet() == abis.keys.map { "$it/libehviewer.so.dbg" }.toSet()) { "Expected precisely three diagnostic ELFs" }
        abis.keys.map { abi ->
            val debug = temp.resolve("$abi.dbg").apply {
                writeBytes(zip.getInputStream(zip.getEntry("$abi/libehviewer.so.dbg")).use { it.readBytes() })
                setPosixFilePermissions(permissions600)
            }
            val lines = command(tools.llvm.resolve("llvm-dwarfdump"), "--debug-line", debug)
            require("archive.rs" in lines && "native.rs" in lines) { "Rust Core/JNI source information missing: $abi" }
            val packaged = temp.resolve("$abi.so")
            ZipFile(apkDirectory.resolve("app-$abi-release.apk").toFile()).use { apk ->
                packaged.writeBytes(apk.getInputStream(apk.getEntry("lib/$abi/libehviewer.so")).use { it.readBytes() })
            }
            require(".debug_info" !in command(tools.llvm.resolve("llvm-readelf"), "--sections", packaged)) { "Debug info shipped in APK" }
            val identifier = buildId(debug)
            require(buildId(packaged) == identifier) { "Diagnostic build ID differs: $abi" }
            mapOf("abi" to abi, "build_id" to identifier, "rust_core_lines" to true, "rust_jni_lines" to true, "apk_debug_info" to false)
        }
    }
}
