package verification

import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal val desktopComicColors = listOf(0xff285f93.toInt(), 0xffa44760.toInt(), 0xff417856.toInt())

/** Activate only the product process started by this check, never a title-matched user window. */
internal fun windowsProductActivation(processId: Long): List<String> {
    require(processId in 1..Int.MAX_VALUE.toLong())
    return listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "(New-Object -ComObject WScript.Shell).AppActivate($processId)")
}

/** jpackage may restart itself: only this owner's live, same-executable descendants qualify. */
internal fun ownedDesktopWindowProcesses(owner: ProcessHandle, launcher: Path): List<ProcessHandle> {
    val executable = launcher.toRealPath()
    val owned = listOf(owner) + owner.descendants().use { it.toList() }
    return owned.distinctBy { it.pid() }.filter { candidate ->
        candidate.isAlive && candidate.info().command().orElse(null)?.let { path ->
            runCatching { Path.of(path).toRealPath() == executable }.getOrDefault(false)
        } == true
    }
}

/** Generated, explicitly labeled owned input; no downloaded comic or user's private profile. */
internal fun writeDesktopComic(path: Path) {
    Files.newOutputStream(path, CREATE_NEW).use { stream ->
        ZipOutputStream(stream).use { zip ->
            desktopComicColors.forEachIndexed { index, color ->
                val page = BufferedImage(640, 960, BufferedImage.TYPE_INT_RGB)
                val graphics = page.createGraphics()
                try {
                    graphics.color = Color(color)
                    graphics.fillRect(0, 0, page.width, page.height)
                    graphics.color = Color.WHITE
                    graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 40)
                    graphics.drawString("EhViewer", 48, 100)
                    graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 26)
                    graphics.drawString("Shared comic reader", 48, 160)
                    graphics.drawString("Owned offline test document", 48, 210)
                    graphics.drawString("Page ${index + 1} / 3", 48, 880)
                    graphics.drawRect(48, 280, 544, 480)
                } finally {
                    graphics.dispose()
                }
                val bytes = ByteArrayOutputStream().also { check(ImageIO.write(page, "png", it)) }.toByteArray()
                zip.putNextEntry(ZipEntry("${index + 1}.png"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}

/** Actual packaged normal entry, native OS keyboard events and raw whole-display captures on CI. */
fun desktopProductUi(image: Path, runtimeReport: Path, output: Path): Map<String, Any?> {
    val windows = System.getProperty("os.name").startsWith("Windows")
    require(windows || System.getProperty("os.name") == "Linux") { "Normal GUI automation is limited to owned Windows/Linux CI displays" }
    require(System.getenv("GITHUB_ACTIONS") == "true" && !GraphicsEnvironment.isHeadless()) { "Use the task's actual interactive CI display" }
    val source = command("git", "rev-parse", "HEAD").trim()
    val report = readJson(runtimeReport)
    require(report["source_commit"] == source && report["source_dirty"] == false && report["result"] == "PASS") { "A successful runtime receipt for this exact clean source is required" }
    val arm = System.getProperty("os.arch") in setOf("aarch64", "arm64")
    val target = "${if (arm) "aarch64" else "x86_64"}-${if (windows) "pc-windows-msvc" else "unknown-linux-gnu"}"
    require(report["target"] == target) { "Normal product and measured runtime CPU must match" }
    val launcher = image.toRealPath().resolve(if (windows) "EhViewer.exe" else "bin/EhViewer")
    val measured = if (windows) report else report["checks"].obj()
    require(sha256(launcher) == measured["launcher_sha256"]) { "Normal entry must use the inspected launcher" }
    launcher.inputStream().use {
        if (windows) verifyWindowsPe(it.readNBytes(1 shl 20), if (arm) WindowsPlatform.ARM64 else WindowsPlatform.X64) else verifyLinuxElf(it.readNBytes(32), arm)
    }
    require(!output.exists()) { "Use a fresh owned product UI directory" }
    output.createDirectories()
    val absolute = output.toRealPath()
    val profile = absolute.resolve("fresh-profile").also { it.createDirectories() }
    val comic = absolute.resolve("Owned offline comic 资料.cbz")
    writeDesktopComic(comic)
    val log = absolute.resolve("normal-entry.log")
    val builder = ProcessBuilder(launcher.toString(), "--open", comic.toString()).redirectErrorStream(true).redirectOutput(log.toFile())
    require('"' !in profile.toString() && profile.toString().none { it == '\n' || it == '\r' })
    builder.environment().putAll(
        mapOf(
            "JAVA_TOOL_OPTIONS" to "-Duser.home=\"$profile\" -Duser.language=en -Duser.country=US",
            "LOCALAPPDATA" to profile.resolve("AppData/Local").toString(),
            "XDG_DATA_HOME" to profile.resolve(".local/share").toString(),
            "XDG_CONFIG_HOME" to profile.resolve(".config").toString(),
            "SKIKO_RENDER_API" to "SOFTWARE_COMPAT",
        ),
    )
    val manager = if (windows) null else ProcessBuilder("openbox", "--sm-disable").redirectErrorStream(true).redirectOutput(absolute.resolve("window-manager.log").toFile()).start()
    val process = try {
        builder.start()
    } catch (failure: Exception) {
        manager?.destroy()
        throw failure
    }
    try {
        val robot = Robot().apply { autoDelay = 50 }
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }.reduce(Rectangle::union)
        fun capture(name: String): BufferedImage = robot.createScreenCapture(screen).also { check(ImageIO.write(it, "png", absolute.resolve(name).toFile())) }
        if (windows) {
            // The ARM runner's first-logon privacy experience can cover a live product window.
            // Activate the owned PID before judging pixels; activation alone never passes the check.
            capture("before-product-activation.png")
            val activation = StringBuilder("owned_product_pid=${process.pid()}\n")
            val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
            var activated = false
            var activatedPid: Long? = null
            while (System.nanoTime() < deadline && !activated) {
                check(process.isAlive) { "Normal product exited before activation: ${log.readText()}" }
                for (candidate in ownedDesktopWindowProcesses(process.toHandle(), launcher)) {
                    if (System.nanoTime() >= deadline) break
                    val observed = command(*windowsProductActivation(candidate.pid()).toTypedArray(), timeout = Duration.ofSeconds(10)).trim()
                    activation.append("candidate_pid=").append(candidate.pid()).append(" AppActivate=").append(observed).append('\n')
                    absolute.resolve("window-activation.log").writeText(activation.toString())
                    require(observed in setOf("True", "False")) { "Unexpected Windows activation result: $observed" }
                    if (observed == "True") {
                        activated = true
                        activatedPid = candidate.pid()
                        break
                    }
                }
                if (!activated) Thread.sleep(100)
            }
            activation.append("activated_window_pid=").append(activatedPid).append('\n')
            absolute.resolve("window-activation.log").writeText(activation.toString())
            if (!activated) capture("failed-product-activation.png")
            check(activated) { "Owned product window could not be activated; raw display retained" }
        }
        fun colorPoint(image: BufferedImage, color: Int): Point? {
            var count = 0
            var first: Point? = null
            for (y in 0 until image.height step 4) {
                for (x in 0 until image.width step 4) {
                    if (image.getRGB(x, y) == color) {
                        if (first == null) first = Point(x + screen.x, y + screen.y)
                        count++
                    }
                }
            }
            return first.takeIf { count >= 400 }
        }
        fun awaitPage(index: Int, name: String): Point {
            val deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos()
            var observed = 0
            while (System.nanoTime() < deadline) {
                require(process.isAlive) { "Normal product exited before page${index + 1}: ${log.readText()}" }
                val image = robot.createScreenCapture(screen)
                val point = colorPoint(image, desktopComicColors[index])
                if (point != null) {
                    observed++
                    if (observed == 2) {
                        check(ImageIO.write(image, "png", absolute.resolve(name).toFile()))
                        return point
                    }
                } else {
                    observed = 0
                }
                Thread.sleep(100)
            }
            capture("failed-$name")
            error("Normal reader did not decode page${index + 1}; raw display and launch log retained")
        }
        fun key(code: Int) {
            robot.keyPress(code)
            robot.keyRelease(code)
        }
        val first = awaitPage(0, "reader-page1.png")
        robot.mouseMove(first.x + 8, first.y + 8)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        key(KeyEvent.VK_RIGHT)
        awaitPage(1, "reader-page2.png")
        key(KeyEvent.VK_LEFT)
        awaitPage(0, "reader-return-page1.png")
        key(KeyEvent.VK_ESCAPE)
        // This capture is kept for visual review; absence of a page is not called semantic acceptance.
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (System.nanoTime() < deadline && colorPoint(robot.createScreenCapture(screen), desktopComicColors[0]) != null) Thread.sleep(100)
        capture("library-after-reader.png")
        val result = mapOf(
            "source_commit" to source, "source_dirty" to false, "target" to target, "result" to "PASS",
            "normal_entry" to "--open", "launcher_sha256" to measured["launcher_sha256"], "runtime_report_sha256" to sha256(runtimeReport),
            "fixture" to "GENERATED_OWNED_COMIC_NOT_REMOTE_GALLERY", "fixture_sha256" to sha256(comic),
            "decoded_keyboard_pages" to listOf(1, 2, 1), "fresh_profile" to true, "raw_full_display_captures" to true,
            "windows_activation" to if (windows) "OWNED_SAME_EXECUTABLE_PROCESS_TREE_APP_ACTIVATE" else "NOT_APPLICABLE",
            "screenshots" to listOf("reader-page1.png", "reader-page2.png", "reader-return-page1.png", "library-after-reader.png").associateWith { sha256(absolute.resolve(it)) },
            "library_return" to "CAPTURED_MANUAL_REVIEW_REQUIRED", "full_visual_parity" to "NOT_VERIFIED", "desktop_p0" to "NOT_VERIFIED", "publish_enabled" to false,
        )
        absolute.resolve("product-ui.json").writeText(json(result))
        return result
    } finally {
        // Restrict termination to the process and display manager started by this verification.
        process.destroy()
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
        manager?.destroy()
        manager?.waitFor(10, TimeUnit.SECONDS)
    }
}
