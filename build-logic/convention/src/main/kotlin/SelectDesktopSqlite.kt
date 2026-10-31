import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Preserve upstream classes/notices and select only the distribution's actual native CPU. */
abstract class SelectDesktopSqlite : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: ConfigurableFileCollection

    @get:Input abstract val target: Property<String>

    @get:Input abstract val sourceSha256: Property<String>

    @get:OutputFile abstract val destination: RegularFileProperty

    @TaskAction
    fun select() = DesktopSqliteArchive.select(source.singleFile, destination.get().asFile, target.get(), sourceSha256.get())
}

internal object DesktopSqliteArchive {
    private val libraries = mapOf(
        "linux_arm64" to "libsqliteJni.so",
        "linux_x64" to "libsqliteJni.so",
        "osx_arm64" to "libsqliteJni.dylib",
        "osx_x64" to "libsqliteJni.dylib",
        "windows_x64" to "sqliteJni.dll",
    )
    private const val LICENSE = "META-INF/androidx/sqlite/sqlite-bundled/LICENSE.txt"

    fun select(source: File, destination: File, target: String, expectedSha: String) {
        require(target in libraries || target == "windows_arm64") { "Unsupported SQLite distribution target" }
        require(source.length() in 1..32L * 1024 * 1024)
        val sourceSha = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
        check(sourceSha == expectedSha) { "Unexpected upstream SQLite archive" }
        ZipFile(source).use { zip ->
            val entries = zip.entries().asSequence().toList()
            check(entries.size <= 256 && entries.map { it.name }.distinct().size == entries.size)
            check(
                entries.all { entry ->
                    val name = entry.name.removeSuffix("/")
                    name.isNotEmpty() && '\\' !in name && name.split('/').none { it.isEmpty() || it == "." || it == ".." }
                },
            ) { "Unsafe SQLite archive entry" }
            val known = libraries.map { (platform, file) -> "natives/$platform/$file" }.toSet()
            val nativeEntries = entries.filter { !it.isDirectory && it.name.substringAfterLast('.') in setOf("so", "dylib", "jnilib", "dll") }
            check(nativeEntries.map { it.name }.toSet() == known) { "Unexpected upstream SQLite native inventory" }
            check(entries.any { it.name == LICENSE && it.size > 0 }) { "SQLite license must be retained" }
            val selected = libraries[target]?.let { "natives/$target/$it" }
            if (selected != null) {
                val bytes = zip.getInputStream(checkNotNull(zip.getEntry(selected))).use { it.readNBytes(16 * 1024 * 1024 + 1) }
                check(bytes.size <= 16 * 1024 * 1024)
                verifyNative(bytes, target)
            }
            // Windows ARM64's missing upstream JNI is supplied by core/data's pinned native build.
            val retained = entries.filter { !it.name.startsWith("natives/") || it.name == selected }
            check(retained.filterNot { it.isDirectory }.sumOf { it.size } <= 32L * 1024 * 1024)
            check(retained.any { it.name == "androidx/sqlite/driver/bundled/BundledSQLiteDriver.class" })
            destination.parentFile.mkdirs()
            ZipOutputStream(destination.outputStream()).use { output ->
                for (entry in retained) {
                    output.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                    if (!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
    }

    private fun verifyNative(bytes: ByteArray, target: String) {
        check(bytes.size >= 64) { "Truncated SQLite native library" }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val arm = target.endsWith("arm64")
        when {
            target.startsWith("linux_") -> check(
                header.getInt(0) == 0x464c457f && bytes[4] == 2.toByte() && bytes[5] == 1.toByte() &&
                    header.getShort(16).toInt() == 3 && header.getShort(18).toInt() == if (arm) 183 else 62,
            ) { "SQLite library has the wrong ELF architecture" }
            target.startsWith("osx_") -> check(header.getInt(0) == 0xfeedfacf.toInt() && header.getInt(4) == if (arm) 0x100000c else 0x1000007) { "SQLite library has the wrong Mach-O architecture" }
            else -> {
                val offset = header.getInt(0x3c)
                check(bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte() && offset in 64..bytes.size - 26)
                check(header.getInt(offset) == 0x4550 && (header.getShort(offset + 4).toInt() and 0xffff) == 0x8664 && header.getShort(offset + 24).toInt() == 0x20b) { "SQLite library has the wrong PE architecture" }
            }
        }
    }
}
