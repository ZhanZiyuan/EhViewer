package verification

import groovy.json.JsonSlurper
import java.nio.file.Path

/** Same actual native store/rendering regression without a JVM parent, to isolate startup failures. */
fun verifyDesktopBrowserSelfTest(output: String, architecture: String) {
    val record = output.lineSequence().filter { it.startsWith('{') }.toList().singleOrNull()
        ?: error("Missing or ambiguous standalone native browser result")
    val proof = JsonSlurper().parseText(record).obj()
    require(proof["protocol"] == 1 && proof["status"] == "verified" && proof["checks"] == 4 && proof["architecture"] == architecture && proof["os"] == "linux") { "Standalone native browser did not verify this Linux CPU" }
    require(Regex("[0-9]+(?:\\.[0-9]+){1,3}").matches(proof.str("engine_version"))) { "Missing actual native engine version" }
}

fun verifyDesktopBrowserHeader(header: String) {
    val declarations = header.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("\\s+"), "")
    for (prototype in listOf(
        "uint32_tehv_browser_api_version(void);",
        "int32_tehv_browser_open(constuint8_t*binary,size_tbinary_length,constuint8_t*user_agent,size_tuser_agent_length,uint32_tmode,uint64_t*handle);",
        "int32_tehv_browser_cookies(uint64_thandle,uint64_t*output);",
        "int32_tehv_browser_close(uint64_thandle);",
        "int32_tehv_browser_process_id(uint64_thandle,uint64_t*output);",
        "int32_tehv_browser_running(uint64_thandle,uint32_t*output);",
        "int32_tehv_browser_active(size_t*output);",
    )) {
        require(Regex(Regex.escape(prototype)).findAll(declarations).count() == 1) { "Changed desktop browser C ABI signature" }
    }
}

/** Measured native-store transfer; no cookie values or account identity enter receipts. */
fun verifyDesktopBrowserReceipt(proof: Map<String, Any?>, architectures: Set<String>, bridge: String, librarySha256: String, browserSha256: String) {
    require(proof["result"] == "PASS" && proof["architecture"] in architectures && proof["bridge"] == bridge) { "Native browser caller identity differs" }
    require(proof["native_library_sha256"] == librarySha256 && proof["browser_executable_sha256"] == browserSha256) { "Native browser receipt differs from actual packaged bytes" }
    require(proof["processes"] == 2 && proof["native_metadata_checks_per_process"] == 4 && proof["reads_per_process"] == 2) { "Incomplete native browser regression" }
    val pids = listOf("first_pid", "second_pid").map { (proof[it] as Number).toLong() }
    require(pids.all { it > 0 } && pids.distinct().size == 2) { "Fresh native browser processes did not execute" }
    require(proof["active_after_close"] == 0 && proof["stale_handles"] == "REJECTED" && proof["real_account_sign_in"] == "NOT_VERIFIED") { "Incomplete browser ownership proof" }
    require(proof["uncomposed_cleanup"] == "PASS") { "Abandoned composition did not release its browser owner" }
}

fun desktopBrowserReceipt(report: Path, library: Path, browser: Path, architectures: Set<String>, bridge: String): Map<String, Any?> = readJson(report.resolve("native-browser.json")).also {
    verifyDesktopBrowserReceipt(it, architectures, bridge, sha256(library), sha256(browser))
}
