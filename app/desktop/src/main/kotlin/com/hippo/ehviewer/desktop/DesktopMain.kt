package com.hippo.ehviewer.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.ehviewer.core.network.gallery.RustGalleryDecoder
import com.ehviewer.shared.verifyAccountProduct
import com.ehviewer.shared.verifyAnimatedGalleryProduct
import com.ehviewer.shared.verifyGalleryProduct
import com.hippo.ehviewer.jni.sha1
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Normal shared product entry, with a separate measured packaged-runtime regression mode. */
fun main(arguments: Array<String>) {
    if (arguments.isEmpty() || arguments.first() == "--open") {
        galleryApplication(if (arguments.isEmpty()) null else desktopDocumentPath(arguments))
        return
    }
    val mac = System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") in setOf("x86_64", "amd64", "aarch64", "arm64")
    val linux = System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("aarch64", "amd64", "x86_64")
    val windows = System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") in setOf("aarch64", "amd64", "x86_64")
    check(mac || linux || windows) { "Packaged regression supports native macOS, Linux and Windows Intel/ARM64" }
    val report = when {
        arguments.isEmpty() -> null
        arguments.size == 2 && arguments[0] == "--m1-smoke" -> Path.of(arguments[1])
        arguments.size == 2 && arguments[0] == "--m1-smoke-base64" -> Path.of(Base64.getDecoder().decode(arguments[1]).decodeToString(throwOnInvalidSequence = true))
        else -> error("Expected --m1-smoke <fresh-report-directory>")
    }
    val nativeRoot = Path.of(checkNotNull(System.getProperty("compose.application.resources.dir")))
    val owned = if (windows) verifyOwnedCapi(nativeRoot.resolve("ehviewer_capi.dll")) else null
    val completed = owned?.completed ?: verifyUnixJni(nativeRoot, mac)
    val galleryChecks = verifyGalleryProduct(if (windows) WindowsGalleryDecoder(nativeRoot.resolve("ehviewer_capi.dll")) else RustGalleryDecoder) + verifyAnimatedGalleryProduct()
    val accountChecks = verifyAccountProduct(if (windows) WindowsGalleryDecoder(nativeRoot.resolve("ehviewer_capi.dll")) else RustGalleryDecoder)
    val historyChecks = verifyDesktopHistory()
    val browserLibrary = nativeRoot.resolve(if (windows) "ehviewer_capi.dll" else "libehviewer_rust.${if (mac) "dylib" else "so"}")
    val archiveChecks = verifyDesktopArchives(browserLibrary)
    val languageChecks = verifyDesktopLanguage()
    val browserProof = verifyBrowserBridge(browserLibrary, nativeRoot.resolve(if (windows) "ehviewer_browser.exe" else "ehviewer_browser"), windows)
    val frames = AtomicInteger()
    application {
        val icon = remember { BitmapPainter(desktopApplicationIcon()) }
        Window(onCloseRequest = ::exitApplication, title = "EhViewer · M1 desktop runtime", icon = icon) {
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
                        setProperty("gallery_product_checks", galleryChecks.toString())
                        setProperty("account_product_checks", accountChecks.toString())
                        setProperty("history_product_checks", historyChecks.toString())
                        setProperty("archive_product_checks", archiveChecks.toString())
                        setProperty("language_product_checks", languageChecks.toString())
                        if (linux) {
                            val digest = MessageDigest.getInstance("SHA-256")
                            Files.newInputStream(Path.of(System.getProperty("java.home"), "lib/server/libjvm.so")).use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    digest.update(buffer, 0, count)
                                }
                            }
                            setProperty("runtime_jvm_sha256", digest.digest().joinToString("") { "%02x".format(it) })
                            setProperty("runtime_file_store", Files.getFileStore(Path.of(System.getProperty("java.home"))).type())
                        }
                        if (owned != null) {
                            setProperty("windows_code_page", windowsProcessCodePage().toString())
                            setProperty("native_encoding", System.getProperty("native.encoding"))
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
                    Files.writeString(report.resolve("native-browser.json"), browserProof.toString())
                    delay(250)
                    exitApplication()
                }
            }
        }
    }
}

private fun verifyUnixJni(nativeRoot: Path, mac: Boolean): Int {
    System.load(nativeRoot.resolve("libehviewer_rust.${if (mac) "dylib" else "so"}").toAbsolutePath().toString())
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
