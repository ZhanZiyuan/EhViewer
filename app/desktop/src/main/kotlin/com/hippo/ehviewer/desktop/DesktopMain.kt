package com.hippo.ehviewer.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.hippo.ehviewer.jni.sha1
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** A real M1 window/native runtime probe. Shared reader and desktop P0 arrive in M5. */
fun main(arguments: Array<String>) {
    val macIntel = System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") in setOf("x86_64", "amd64")
    val linuxArm = System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") == "aarch64"
    val windowsArm = System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") == "aarch64"
    check(macIntel || linuxArm || windowsArm) { "This M1 probe supports macOS Intel, Linux ARM64 and Windows ARM64" }
    val report = when {
        arguments.isEmpty() -> null
        arguments.size == 2 && arguments[0] == "--m1-smoke" -> Path.of(arguments[1])
        arguments.size == 2 && arguments[0] == "--m1-smoke-base64" -> Path.of(Base64.getDecoder().decode(arguments[1]).decodeToString(throwOnInvalidSequence = true))
        else -> error("Expected --m1-smoke <fresh-report-directory>")
    }
    val nativeRoot = Path.of(checkNotNull(System.getProperty("compose.application.resources.dir")))
    val owned = if (windowsArm) verifyOwnedCapi(nativeRoot.resolve("ehviewer_capi.dll")) else null
    val completed = owned?.completed ?: verifyUnixJni(nativeRoot, macIntel)
    val frames = AtomicInteger()
    application {
        Window(onCloseRequest = ::exitApplication, title = "EhViewer · M1 desktop runtime") {
            Box(
                Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    frames.incrementAndGet()
                },
                contentAlignment = Alignment.Center,
            ) {
                BasicText("EhViewer · Compose Desktop runtime")
            }
            if (report != null) {
                LaunchedEffect(Unit) {
                    withTimeout(30_000) { while (frames.get() == 0) delay(16) }
                    check(window.isShowing && window.width > 0 && window.height > 0)
                    Files.createDirectories(report)
                    check(!Files.exists(report.resolve("launcher.properties")))
                    val proof = Properties().apply {
                        setProperty("architecture", System.getProperty("os.arch"))
                        setProperty("java_version", System.getProperty("java.version"))
                        setProperty("java_home", System.getProperty("java.home"))
                        setProperty("application_version", System.getProperty("jpackage.app-version"))
                        setProperty("rust_hash_results", completed.toString())
                        if (owned != null) {
                            setProperty("rust_library", owned.loadedPath.toString())
                            setProperty("rust_active_results", "0")
                            setProperty("rust_ownership_errors", "PASS")
                            setProperty("rust_library_closed", "PASS")
                        }
                        setProperty("compose_frames", frames.get().toString())
                        setProperty("window_showing", window.isShowing.toString())
                        setProperty("render_api", window.renderApi.name)
                    }
                    Files.newOutputStream(report.resolve("launcher.properties")).use { proof.store(it, "Measured M1 packaged launcher evidence") }
                    delay(250)
                    exitApplication()
                }
            }
        }
    }
}

private fun verifyUnixJni(nativeRoot: Path, macIntel: Boolean): Int {
    System.load(nativeRoot.resolve("libehviewer_rust.${if (macIntel) "dylib" else "so"}").toAbsolutePath().toString())
    val sample = Files.createTempFile("ehviewer-m1-core-", ".txt")
    var completed = 0
    try {
        Files.writeString(sample, "abc")
        FileInputStream(sample.toFile()).use { input ->
            // M1 reuses the original JNI fd surface; M3 introduces product file interfaces.
            val field = FileDescriptor::class.java.getDeclaredField("fd").apply { isAccessible = true }
            repeat(100) {
                input.channel.position(0)
                check(sha1(field.getInt(input.fd)) == "a9993e364706816aba3e25717850c26c9cd0d89d")
                completed++
            }
        }
    } finally {
        Files.delete(sample)
    }
    return completed
}
