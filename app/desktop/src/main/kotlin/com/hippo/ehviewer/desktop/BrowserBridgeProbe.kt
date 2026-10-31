package com.hippo.ehviewer.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Explicit desktop regression: real native WebKit/WebView2 store through JNI/C ABI. */
fun main(arguments: Array<String>) {
    require(arguments.size == 3 || arguments.size == 4 && arguments[3] == "--c-api") { "Expected native library, browser executable, fresh evidence file and optional --c-api" }
    val library = Path.of(arguments[0]).toRealPath()
    val binary = Path.of(arguments[1]).toRealPath()
    val output = Path.of(arguments[2]).toAbsolutePath()
    require(!Files.exists(output))
    val cApi = System.getProperty("os.name").startsWith("Windows") || arguments.size == 4
    val proof = verifyBrowserBridge(library, binary, cApi)
    output.parent?.let(Files::createDirectories)
    Files.writeString(output, proof.toString())
}

internal fun verifyBrowserBridge(library: Path, binary: Path, cApi: Boolean): JsonObject {
    check(Files.isRegularFile(binary) && (cApi || Files.isExecutable(binary))) { "The packaged native browser must be executable" }
    // The owner remains on this main thread. Every Windows method additionally has
    // its own confined arena/loader on its actual calling thread.
    val owner = if (cApi) openCapi(library) else null
    if (!cApi) System.load(library.toString())
    val calls: BrowserCalls = if (cApi) WindowsBrowserCalls(library) else NativeBrowserBridge()
    try {
        check(calls.protocolVersion() == 1 && calls.active() == 0)
        val serviceSupported = !cApi || System.getProperty("os.name").startsWith("Windows")
        if (serviceSupported) {
            // Exercise the real service's abandoned-composition cleanup: no
            // browser starts, no deferred opening signal can stall app shutdown.
            repeat(64) {
                val service = DesktopBrowserService(library, binary)
                service.use {
                    repeat(16) {
                        service.create("EhViewer/uncomposed-browser-regression").close()
                    }
                }
                // Shutdown is idempotent and cannot admit new owners afterwards.
                service.close()
                check(runCatching { service.create("EhViewer/closed-browser-regression") }.isFailure)
            }
            check(calls.active() == 0)
        }
        val pids = mutableListOf<Long>()
        repeat(2) {
            val handle = calls.open(binary.toString(), "EhViewer/native-browser-regression", regression = true)
            check(handle > (1L shl 62) && calls.active() == 1)
            try {
                val pid = calls.processId(handle)
                check(pid > 0 && pid !in pids)
                pids += pid
                repeat(2) {
                    val records = decodeBrowserCookies(calls.cookies(handle))
                    check(records.size == 2)
                    val domain = records.single { it.name == "ehviewer-domain" }
                    val host = records.single { it.name == "ehviewer-host" }
                    check(domain.domain == ".e-hentai.org" && domain.path == "/private")
                    check(host.domain == "e-hentai.org" && host.path == "/")
                    check(records.all { it.secure && it.httpOnly && it.expiresAt != null && it.value == "synthetic-only" })
                }
            } finally {
                calls.close(handle)
            }
            check(calls.active() == 0)
            check(runCatching { calls.cookies(handle) }.isFailure)
            check(runCatching { calls.close(handle) }.isFailure)
        }
        fun sha256(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        return buildJsonObject {
            put("result", "PASS")
            put("bridge", if (cApi) "C_ABI" else "JNI")
            put("architecture", System.getProperty("os.arch"))
            put("processes", pids.size)
            put("first_pid", pids[0])
            put("second_pid", pids[1])
            put("native_metadata_checks_per_process", 4)
            put("reads_per_process", 2)
            put("active_after_close", calls.active())
            put("stale_handles", "REJECTED")
            put("native_library_sha256", sha256(library))
            put("browser_executable_sha256", sha256(binary))
            put("real_account_sign_in", "NOT_VERIFIED")
            put("uncomposed_cleanup", if (serviceSupported) "PASS" else "NOT_VERIFIED")
        }
    } finally {
        owner?.close()
    }
}
