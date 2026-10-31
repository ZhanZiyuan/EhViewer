import java.net.JarURLConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DesktopSqliteArchiveTest {
    private val libraryNames = listOf(
        "natives/linux_arm64/libsqliteJni.so",
        "natives/linux_x64/libsqliteJni.so",
        "natives/osx_arm64/libsqliteJni.dylib",
        "natives/osx_x64/libsqliteJni.dylib",
        "natives/windows_x64/sqliteJni.dll",
    )
    private fun native(target: String): ByteArray = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN).apply {
        val arm = target.endsWith("arm64")
        when {
            target.startsWith("linux") -> {
                putInt(0, 0x464c457f)
                put(4, 2)
                put(5, 1)
                putShort(16, 3)
                putShort(18, if (arm) 183 else 62)
            }
            target.startsWith("osx") -> {
                putInt(0, 0xfeedfacf.toInt())
                putInt(4, if (arm) 0x100000c else 0x1000007)
            }
            else -> {
                put(0, 'M'.code.toByte())
                put(1, 'Z'.code.toByte())
                putInt(0x3c, 128)
                putInt(128, 0x4550)
                putShort(132, 0x8664.toShort())
                putShort(152, 0x20b)
            }
        }
    }.array()

    private fun entries() = linkedMapOf(
        "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
        "META-INF/androidx/sqlite/sqlite-bundled/LICENSE.txt" to "Upstream license bytes\n".toByteArray(),
        "androidx/sqlite/driver/bundled/BundledSQLiteDriver.class" to byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte()),
    ).apply { libraryNames.forEach { put(it, native(it.split('/')[1])) } }

    private fun fixture(change: (MutableMap<String, ByteArray>) -> Unit = {}, test: (java.io.File, java.io.File, String, Map<String, ByteArray>) -> Unit) {
        val directory = Files.createTempDirectory("ehviewer-sqlite-archive-test").toFile()
        try {
            val entries = entries().also(change)
            val input = directory.resolve("upstream.jar")
            ZipOutputStream(input.outputStream()).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            val sha = MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it) }
            test(input, directory.resolve("selected.jar"), sha, entries)
        } finally {
            check(directory.deleteRecursively())
        }
    }

    @Test fun eachPublishedTargetPreservesClassesNoticesAndOnlyItsExactNativeBytes() = fixture { input, output, sha, original ->
        for (selected in libraryNames) {
            DesktopSqliteArchive.select(input, output, selected.split('/')[1], sha)
            ZipFile(output).use { zip ->
                val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
                val expected = original.keys.filter { !it.startsWith("natives/") || it == selected }.toSet()
                assertEquals(expected, names)
                names.forEach { assertArrayEquals(original.getValue(it), zip.getInputStream(zip.getEntry(it)).use { stream -> stream.readBytes() }) }
            }
            val first = output.readBytes()
            DesktopSqliteArchive.select(input, output, selected.split('/')[1], sha)
            assertArrayEquals(first, output.readBytes())
        }
    }

    @Test fun actualPinnedUpstreamArchiveSupportsEveryDistributionWithoutForeignNatives() {
        val resource = checkNotNull(javaClass.classLoader.getResource("natives/linux_arm64/libsqliteJni.so"))
        val connection = resource.openConnection() as JarURLConnection
        val input = java.io.File(connection.jarFileURL.toURI())
        val directory = Files.createTempDirectory("ehviewer-upstream-sqlite-test").toFile()
        try {
            ZipFile(input).use { upstream ->
                for (target in libraryNames.map { it.split('/')[1] } + "windows_arm64") {
                    val output = directory.resolve("$target.jar")
                    DesktopSqliteArchive.select(input, output, target, "e61efb0647288b486e595950724cc53c4f15eeeca03ce2184751d02de3cfaa17")
                    ZipFile(output).use { selected ->
                        val nativeNames = selected.entries().asSequence().filter { it.name.startsWith("natives/") }.map { it.name }.toList()
                        assertEquals(libraryNames.filter { it.startsWith("natives/$target/") }, nativeNames)
                        selected.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                            assertArrayEquals(
                                upstream.getInputStream(checkNotNull(upstream.getEntry(entry.name))).use { it.readBytes() },
                                selected.getInputStream(entry).use { it.readBytes() },
                            )
                        }
                    }
                }
            }
        } finally {
            check(directory.deleteRecursively())
        }
    }

    @Test fun windowsArm64KeepsClassesAndLicenseWithoutBorrowingTheX64Dll() = fixture { input, output, sha, original ->
        DesktopSqliteArchive.select(input, output, "windows_arm64", sha)
        ZipFile(output).use { zip ->
            assertEquals(original.keys.filterNot { it.startsWith("natives/") }.toSet(), zip.entries().asSequence().map { it.name }.toSet())
        }
    }

    @Test fun wrongSourceAndUnknownArchitectureFailBeforeWritingOutput() = fixture { input, output, sha, _ ->
        assertThrows(IllegalStateException::class.java) { DesktopSqliteArchive.select(input, output, "linux_arm64", "0".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { DesktopSqliteArchive.select(input, output, "linux_riscv64", sha) }
        check(!output.exists())
    }

    @Test fun renamedX64LibraryCannotPassAsArm64() = fixture({ it["natives/linux_arm64/libsqliteJni.so"] = native("linux_x64") }) { input, output, sha, _ ->
        assertThrows(IllegalStateException::class.java) { DesktopSqliteArchive.select(input, output, "linux_arm64", sha) }
        check(!output.exists())
    }

    @Test fun missingNativeAndMissingLicenseAreRejected() {
        for (removed in listOf(libraryNames.first(), "META-INF/androidx/sqlite/sqlite-bundled/LICENSE.txt")) {
            fixture({ it.remove(removed) }) { input, output, sha, _ ->
                assertThrows(IllegalStateException::class.java) { DesktopSqliteArchive.select(input, output, "linux_arm64", sha) }
                check(!output.exists())
            }
        }
    }

    @Test fun unexpectedNativeAndUnsafeArchivePathsAreRejected() {
        for (added in listOf("unexpected.so", "../outside", "/absolute", "natives\\foreign.dll")) {
            fixture({ it[added] = byteArrayOf(1) }) { input, output, sha, _ ->
                assertThrows(IllegalStateException::class.java) { DesktopSqliteArchive.select(input, output, "linux_arm64", sha) }
                check(!output.exists())
            }
        }
    }
}
