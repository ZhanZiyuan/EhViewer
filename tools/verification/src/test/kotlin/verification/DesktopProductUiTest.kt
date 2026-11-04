package verification

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopProductUiTest {
    @Test
    fun windowLeaseUsesTheInspectedRuntimeAndSeparateOwnedArguments() {
        val java = Path.of("Owned runtime 资料", "java.exe")
        val launcher = Path.of("Owned application 资料", "EhViewer.exe")
        val command = ownedWindowsWindowCommand(java, "libs with spaces/*", 2345, launcher, "promote")
        assertEquals(listOf(java.toString(), "--enable-native-access=ALL-UNNAMED", "-cp", "libs with spaces/*", OwnedWindowsWindow::class.java.name, "2345", launcher.toString(), "promote"), command)
        assertEquals("restore", ownedWindowsWindowCommand(java, "cp", 2345, launcher, "restore").last())
        for (invalid in listOf(0L, -1L, Int.MAX_VALUE.toLong() + 1)) {
            assertThrows(IllegalArgumentException::class.java) { ownedWindowsWindowCommand(java, "cp", invalid, launcher, "promote") }
        }
        assertThrows(IllegalArgumentException::class.java) { ownedWindowsWindowCommand(java, "cp", 2345, launcher, "dismiss-privacy") }
    }

    @Test
    fun activationCandidatesIncludeARealRestartChildButExcludeAnUnrelatedSameExecutable() = temporary { directory ->
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath()
        val classpath = listOf(CommandTimeoutProbe::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()) }.distinct().joinToString(File.pathSeparator)
        val marker = directory.resolve("child.pid")
        fun start(name: String, argument: String) = ProcessBuilder(java.toString(), "-cp", classpath, CommandTimeoutProbe::class.java.name, argument)
            .redirectErrorStream(true).redirectOutput(directory.resolve("$name.log").toFile()).start()
        val owner = start("owner", marker.toString())
        val unrelated = start("unrelated", "leaf")
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (!Files.exists(marker) && owner.isAlive && System.nanoTime() < deadline) Thread.sleep(25)
            val child = Files.readString(marker).trim().toLong()
            val candidates = ownedDesktopWindowProcesses(owner.toHandle(), java).map { it.pid() }
            assertEquals(setOf(owner.pid(), child), candidates.toSet())
            assertFalse(candidates.contains(unrelated.pid()))
            assertTrue(ownedDesktopWindowProcesses(owner.toHandle(), root.resolve("gradlew")).isEmpty())
        } finally {
            owner.descendants().use { it.toList().asReversed().forEach { child -> child.destroyForcibly() } }
            owner.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            unrelated.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun activationUsesOnlyTheOwnedNumericPidWithoutATitleOrAnotherProcess() {
        val command = windowsProductActivation(12345)
        assertEquals("powershell.exe", command.first())
        assertEquals("(New-Object -ComObject WScript.Shell).AppActivate(12345)", command.last())
        for (invalid in listOf(0L, -1L, Int.MAX_VALUE.toLong() + 1)) {
            assertThrows(IllegalArgumentException::class.java) { windowsProductActivation(invalid) }
        }
    }

    @Test
    fun ownedComicContainsThreeDistinctRealDecodablePagesInNaturalOrder() = temporary { directory ->
        val file = directory.resolve("Owned offline comic 资料.cbz")
        writeDesktopComic(file)
        ZipFile(file.toFile()).use { zip ->
            val entries = zip.entries().asSequence().toList()
            assertEquals(listOf("1.png", "2.png", "3.png"), entries.map { it.name })
            entries.forEachIndexed { index, entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val page = checkNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
                assertEquals(640, page.width)
                assertEquals(960, page.height)
                assertEquals(desktopComicColors[index], page.getRGB(320, 480))
                assertTrue(bytes.size > 1000)
            }
        }
        assertTrue(Files.size(file) > 3000)
    }
}
