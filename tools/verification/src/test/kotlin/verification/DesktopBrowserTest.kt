package verification

import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopBrowserTest {
    @Test fun rejectsChangedBrowserAbiWidthsOwnershipAndMissingDeclarations() {
        val header = """
            uint32_t ehv_browser_api_version(void);
            int32_t ehv_browser_open(const uint8_t *binary, size_t binary_length, const uint8_t *user_agent, size_t user_agent_length, uint32_t mode, uint64_t *handle);
            int32_t ehv_browser_cookies(uint64_t handle, uint64_t *output);
            int32_t ehv_browser_close(uint64_t handle);
            int32_t ehv_browser_process_id(uint64_t handle, uint64_t *output);
            int32_t ehv_browser_running(uint64_t handle, uint32_t *output);
            int32_t ehv_browser_active(size_t *output);
        """.trimIndent()
        verifyDesktopBrowserHeader(header)
        for (line in header.lines()) {
            assertTrue(runCatching { verifyDesktopBrowserHeader(header.replace(line, "")) }.isFailure)
            assertTrue(runCatching { verifyDesktopBrowserHeader(header + line) }.isFailure)
        }
        for ((before, after) in listOf("uint64_t *handle" to "void **handle", "uint32_t mode" to "uint64_t mode", "size_t *output" to "uint32_t *output")) {
            assertTrue(runCatching { verifyDesktopBrowserHeader(header.replace(before, after)) }.isFailure)
        }
    }

    private val library = "a".repeat(64)
    private val browser = "b".repeat(64)
    private fun receipt(): Map<String, Any?> = mapOf(
        "result" to "PASS", "architecture" to "aarch64", "bridge" to "JNI",
        "native_library_sha256" to library, "browser_executable_sha256" to browser,
        "processes" to 2, "native_metadata_checks_per_process" to 4, "reads_per_process" to 2,
        "first_pid" to 123, "second_pid" to 456, "active_after_close" to 0,
        "stale_handles" to "REJECTED", "real_account_sign_in" to "NOT_VERIFIED", "uncomposed_cleanup" to "PASS",
    )

    @Test fun rejectsMissingChecksForeignBytesAndInventedAccountSuccess() {
        val proof = receipt()
        verifyDesktopBrowserReceipt(proof, setOf("aarch64"), "JNI", library, browser)
        for (key in proof.keys) {
            assertTrue("Missing $key", runCatching { verifyDesktopBrowserReceipt(proof - key, setOf("aarch64"), "JNI", library, browser) }.isFailure)
        }
        for ((key, value) in mapOf(
            "result" to "SKIPPED", "architecture" to "amd64", "bridge" to "C_ABI", "native_library_sha256" to browser,
            "browser_executable_sha256" to library, "processes" to 1, "native_metadata_checks_per_process" to 3,
            "reads_per_process" to 1, "first_pid" to 0, "second_pid" to 123, "active_after_close" to 1,
            "stale_handles" to "PASS", "real_account_sign_in" to "PASS", "uncomposed_cleanup" to "SKIPPED",
        )) {
            assertTrue("Changed $key", runCatching { verifyDesktopBrowserReceipt(proof + (key to value), setOf("aarch64"), "JNI", library, browser) }.isFailure)
        }
    }
}
