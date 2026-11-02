package verification

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.inputStream

/** Official fixed release assets; no mutable continuous URL or architecture substitution. */
data class AppImageRuntime(val architecture: String, val machine: Int, val size: Long, val sha256: String) {
    val url: String get() = "https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-$architecture"
}

val appImageRuntimes = listOf(
    AppImageRuntime("aarch64", 183, 936456, "00cbdfcf917cc6c0ff6d3347d59e0ca1f7f45a6df1a428a0d6d8a78664d87444"),
    AppImageRuntime("x86_64", 62, 944632, "2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d"),
).associateBy { it.architecture }

fun verifyAppImageHeader(header: ByteArray, runtime: AppImageRuntime) {
    require(header.size >= 64) { "Truncated AppImage ELF header" }
    val elf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    require(elf.getInt(0) == 0x464c457f && header[4] == 2.toByte() && header[5] == 1.toByte() && header[6] == 1.toByte()) { "Expected ELF64 little-endian AppImage runtime" }
    require(header[8] == 'A'.code.toByte() && header[9] == 'I'.code.toByte() && header[10] == 2.toByte()) { "Expected actual type-2 AppImage marker" }
    require(elf.getShort(16).toInt() in setOf(2, 3) && elf.getShort(18).toInt() == runtime.machine && elf.getInt(20) == 1 && elf.getLong(24) != 0L) { "Expected executable matching-CPU Linux runtime" }
}

/** Portable format inspection; execution, payload inventory and provenance remain separate gates. */
fun inspectLinuxAppImage(file: Path, architecture: String): Map<String, Any?> {
    val runtime = appImageRuntimes.getValue(architecture)
    require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Expected a regular AppImage file" }
    val total = Files.size(file)
    require(total >= runtime.size + 96) { "Missing SquashFS payload" }
    val superblock = file.inputStream().use { input ->
        val header = input.readNBytes(64)
        verifyAppImageHeader(header, runtime)
        val digest = MessageDigest.getInstance("SHA-256").apply { update(header) }
        var remaining = runtime.size - header.size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            require(count > 0) { "Truncated AppImage runtime" }
            digest.update(buffer, 0, count)
            remaining -= count
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == runtime.sha256) { "AppImage runtime differs from pinned upstream bytes" }
        input.readNBytes(96).also { require(it.size == 96) }
    }
    val fs = ByteBuffer.wrap(superblock).order(ByteOrder.LITTLE_ENDIAN)
    val block = fs.getInt(12)
    val used = fs.getLong(40)
    val padding = total - runtime.size - used
    require(fs.getInt(0) == 0x73717368 && fs.getInt(4) > 0) { "Expected a real nonempty SquashFS filesystem" }
    require(block in 4096..1048576 && (block and (block - 1)) == 0 && fs.getShort(20).toInt() == 6 && fs.getShort(28).toInt() == 4 && fs.getShort(30).toInt() == 0) { "Expected SquashFS4 with bounded zstd blocks" }
    require(used >= 96 && padding in 0L..4095L) { "Truncated payload or unexpected appended data" }
    file.inputStream().use { input ->
        input.skipNBytes(runtime.size + used)
        val tail = input.readNBytes(padding.toInt())
        require(tail.size == padding.toInt() && tail.all { it == 0.toByte() } && input.read() == -1) { "Unexpected SquashFS padding/trailing bytes" }
    }
    return mapOf(
        "format" to "APPIMAGE_TYPE_2", "architecture" to architecture, "file_bytes" to total,
        "runtime_version" to "20251108", "runtime_commit" to "dd6cebedcbddde9c82f89b011e8e1d40b6e43868", "runtime_sha256" to runtime.sha256,
        "filesystem_offset" to runtime.size, "filesystem_bytes_used" to used,
        "filesystem_created_epoch" to Integer.toUnsignedLong(fs.getInt(8)), "filesystem_compression" to "zstd",
    )
}
