import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Compile unchanged, hash-pinned third-party AndroidX/SQLite sources for its missing JVM CPU. */
@DisableCachingByDefault(because = "Uses the installed native MSVC/Windows SDK; provenance is retained with the output")
abstract class BuildWindowsSqliteJni : DefaultTask() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        check(System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") in setOf("aarch64", "arm64")) { "SQLite JNI must be built and tested on Windows ARM64" }
        val work = temporaryDir
        val pin = "fe30df161d480829efb21f37ff67a9f8cac9c620"
        val jniSha = "f9f4747111a6635dffd5991126247ca0f2f6c851de1eaf4add3866a0518ac2e0"
        val archiveSha = "41716b44ac8777188c4c3f1f370f01c9cb9e3b6428eb5c981d086c35de2d9d3f"
        val archive = verified(
            work.resolve("sqlite-amalgamation-3500100.zip"),
            "https://www.sqlite.org/2025/sqlite-amalgamation-3500100.zip",
            archiveSha,
            4 * 1024 * 1024,
        )
        val jni = verified(
            work.resolve("sqlite_bindings.cpp"),
            "https://android.googlesource.com/platform/frameworks/support/+/$pin/sqlite/sqlite-bundled/src/jvmAndroidMain/jni/sqlite_bindings.cpp?format=TEXT",
            jniSha,
            1024 * 1024,
            base64 = true,
        )
        ZipFile(archive).use { zip ->
            for (name in listOf("sqlite3.c", "sqlite3.h")) {
                val entry = checkNotNull(zip.getEntry("sqlite-amalgamation-3500100/$name"))
                val bytes = zip.getInputStream(entry).use { it.readNBytes(16 * 1024 * 1024 + 1) }
                check(bytes.size <= 16 * 1024 * 1024)
                work.resolve(name).writeBytes(bytes)
            }
        }
        check(work.resolve("sqlite3.h").readText().contains("#define SQLITE_VERSION        \"3.50.1\""))
        val installer = File(checkNotNull(System.getenv("ProgramFiles(x86)") ?: System.getenv("ProgramFiles")), "Microsoft Visual Studio/Installer/vswhere.exe")
        check(installer.isFile) { "Visual Studio Installer discovery is unavailable" }
        val installation = run(work, "vswhere.log", listOf(installer.path, "-latest", "-products", "*", "-requires", "Microsoft.VisualStudio.Component.VC.Tools.ARM64", "-property", "installationPath")).trim()
        check(installation.isNotEmpty() && !installation.contains('\n')) { "A single ARM64 MSVC installation is required" }
        val devCmd = File(installation, "Common7/Tools/VsDevCmd.bat")
        check(devCmd.isFile && '"' !in devCmd.path)
        // Invoke the vendor's build environment script, retaining only compiler search variables.
        val environmentText = run(
            work,
            "msvc-environment.log",
            listOf("cmd.exe", "/d", "/c", "call \"${devCmd.path}\" -no_logo -arch=arm64 -host_arch=arm64 >nul && set INCLUDE && set LIB && set PATH"),
        )
        val environment = environmentText.lineSequence().mapNotNull { line ->
            val pair = line.split('=', limit = 2)
            if (pair.size == 2 && pair[0].uppercase() in setOf("INCLUDE", "LIB", "LIBPATH", "PATH")) pair[0].uppercase() to pair[1] else null
        }.toMap()
        check(environment.keys.containsAll(setOf("INCLUDE", "LIB", "PATH"))) { "MSVC environment is incomplete" }
        val compiler = environment.getValue("PATH").split(';').map { File(it, "cl.exe") }.firstOrNull(File::isFile) ?: error("ARM64 MSVC compiler was not found")
        val javaHome = File(System.getProperty("java.home"))
        val includes = listOf("/I${work.path}", "/I${javaHome.resolve("include").path}", "/I${javaHome.resolve("include/win32").path}")
        check(javaHome.resolve("include/win32/jni_md.h").isFile) { "An actual Windows JDK with JNI headers is required" }
        val flags = listOf(
            "HAVE_USLEEP=1", "SQLITE_DEFAULT_AUTOVACUUM=1", "SQLITE_DEFAULT_MEMSTATUS=0", "SQLITE_DEFAULT_WAL_SYNCHRONOUS=1",
            "SQLITE_ENABLE_COLUMN_METADATA", "SQLITE_ENABLE_FTS3", "SQLITE_ENABLE_FTS3_PARENTHESIS", "SQLITE_ENABLE_FTS4", "SQLITE_ENABLE_FTS5",
            "SQLITE_ENABLE_JSON1", "SQLITE_ENABLE_NORMALIZE", "SQLITE_ENABLE_RTREE", "SQLITE_ENABLE_STAT4", "SQLITE_HAVE_ISNAN",
            "SQLITE_OMIT_BUILTIN_TEST", "SQLITE_OMIT_DEPRECATED", "SQLITE_OMIT_PROGRESS_CALLBACK", "SQLITE_OMIT_SHARED_CACHE",
            "SQLITE_SECURE_DELETE", "SQLITE_TEMP_STORE=3", "SQLITE_THREADSAFE=2",
        ).map { "/D$it" }
        val common = listOf(compiler.path, "/nologo", "/O2", "/MT") + includes
        run(work, "sqlite-compile.log", common + flags + listOf("/TC", "/c", "sqlite3.c", "/Fosqlite3.obj"), environment)
        run(work, "jni-compile.log", common + listOf("/TP", "/EHsc", "/c", jni.path, "/Fobindings.obj"), environment)
        val directory = outputDirectory.get().asFile.resolve("natives/windows_arm64").apply { check(isDirectory || mkdirs()) }
        val library = directory.resolve("sqliteJni.dll")
        run(work, "sqlite-link.log", listOf(compiler.path, "/nologo", "/LD", "/MT", "sqlite3.obj", "bindings.obj", "/link", "/MACHINE:ARM64", "/Brepro", "/OUT:${library.path}", "advapi32.lib"), environment)
        val bytes = library.readBytes()
        check(bytes.size in 256..16 * 1024 * 1024 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte())
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val offset = header.getInt(0x3c)
        check(offset in 64..bytes.size - 26 && header.getInt(offset) == 0x4550 && (header.getShort(offset + 4).toInt() and 0xffff) == 0xaa64 && header.getShort(offset + 24).toInt() == 0x20b) { "SQLite JNI output is not a real ARM64 PE DLL" }
        directory.resolve("origin.toml").writeText(
            "androidx_version = \"2.6.2\"\nandroidx_commit = \"$pin\"\nsqlite_version = \"3.50.1\"\n" +
                "jni_sha256 = \"$jniSha\"\narchive_sha256 = \"$archiveSha\"\ndll_sha256 = \"${digest(bytes)}\"\narchitecture = \"aarch64\"\n",
        )
    }

    private fun verified(file: File, url: String, expected: String, limit: Int, base64: Boolean = false): File {
        if (file.isFile && digest(file.readBytes()) == expected) return file
        val connection = URI(url).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 30_000
        }
        val transport = connection.getInputStream().use { it.readNBytes(limit + 1) }
        check(transport.size <= limit) { "Pinned source download exceeded its limit" }
        val bytes = if (base64) Base64.getMimeDecoder().decode(transport) else transport
        check(digest(bytes) == expected) { "Pinned third-party source checksum mismatch" }
        file.writeBytes(bytes)
        return file
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun run(directory: File, log: String, arguments: List<String>, environment: Map<String, String> = emptyMap()): String {
        val builder = ProcessBuilder(arguments).directory(directory).redirectErrorStream(true).redirectOutput(directory.resolve(log))
        environment.forEach { (key, value) ->
            builder.environment().keys.filter { it.equals(key, ignoreCase = true) }.toList().forEach(builder.environment()::remove)
            builder.environment()[key] = value
        }
        val process = builder.start()
        if (!process.waitFor(Duration.ofMinutes(5).toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            error("Native build command timed out; see $log")
        }
        check(process.exitValue() == 0) { "Native build command failed; see $log" }
        return directory.resolve(log).readText()
    }
}
