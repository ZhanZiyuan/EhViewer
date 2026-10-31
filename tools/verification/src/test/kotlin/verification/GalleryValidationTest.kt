package verification

import org.junit.Assert.assertThrows
import org.junit.Test

class GalleryValidationTest {
    @Test fun actualCookieAndRepositorySuitesCannotBeSkippedOrOmitted() {
        val good = mapOf("status" to "PASS", "executed" to true, "tests" to 94, "suites" to mapOf("GalleryRepositoryTest[desktop]" to 10, "PersistentCookieStorageTest[desktop]" to 12, "GalleryContentRepositoryTest[desktop]" to 10, "GalleryAuthenticationRepositoryTest[desktop]" to 8, "BrowserSignInTest[desktop]" to 7, "GalleryAccountRepositoryTest[desktop]" to 13, "GallerySearchFiltersTest[desktop]" to 6, "GalleryPreviewRepositoryTest[desktop]" to 5, "GalleryDownloadQueueTest[desktop]" to 10, "NetworkGalleryDownloadSourceTest[desktop]" to 9, "DownloadedGalleryContentTest[desktop]" to 4))
        verifyNetworkTests(good)
        verifyNetworkTests(good + mapOf("tests" to 96, "suites" to (good["suites"].obj() + ("AdditionalNetworkTest[desktop]" to 2))))
        assertThrows(IllegalArgumentException::class.java) { verifyNetworkTests(good + mapOf("tests" to 90, "suites" to (good["suites"].obj() + ("NetworkGalleryDownloadSourceTest[desktop]" to 5)))) }
        for (bad in listOf(good + ("executed" to false), good + ("status" to "SKIPPED"), good + ("tests" to 0), good + ("suites" to mapOf("GalleryRepositoryTest" to 18)))) {
            assertThrows(IllegalArgumentException::class.java) { verifyNetworkTests(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { verifyNetworkTests(good + ("suites" to good["suites"].obj().minus("GalleryAccountRepositoryTest[desktop]"))) }
        assertThrows(IllegalArgumentException::class.java) { verifyNetworkTests(good + ("suites" to good["suites"].obj().minus("GallerySearchFiltersTest[desktop]"))) }
        assertThrows(IllegalArgumentException::class.java) { verifyNetworkTests(good + ("suites" to good["suites"].obj().minus("GalleryPreviewRepositoryTest[desktop]"))) }
        for (suite in listOf("GalleryDownloadQueueTest", "NetworkGalleryDownloadSourceTest", "DownloadedGalleryContentTest")) {
            assertThrows(IllegalArgumentException::class.java) { verifyNetworkTests(good + ("suites" to good["suites"].obj().minus("$suite[desktop]"))) }
        }
    }

    @Test fun generatedHeaderRetainsOriginalOwnershipAndNewSessionWidths() {
        val header = """
            uint32_t ehv_abi_version(void);
            int32_t ehv_hash_sha1(const uint8_t *input, size_t length, uint64_t *handle);
            int32_t ehv_buffer_copy(uint64_t handle, uint8_t *output, size_t capacity, size_t *written);
            int32_t ehv_buffer_free(uint64_t handle);
            int32_t ehv_active_buffers(size_t *count);
            int32_t ehv_parse_gallery_list(const uint8_t *input, size_t length, uint64_t *handle);
            int32_t ehv_parse_gallery_detail(const uint8_t *input, size_t length, uint64_t *handle);
            int32_t ehv_parse_gallery_page(const uint8_t *input, size_t length, uint64_t *handle);
            int32_t ehv_parse_profile_link(const uint8_t *input, size_t length, uint64_t *handle);
            int32_t ehv_parse_account_profile(const uint8_t *input, size_t length, uint64_t *handle);
            int32_t ehv_parse_account_config(const uint8_t *input, size_t length, uint64_t *handle);
            int32_t ehv_session_read(const uint8_t *directory, size_t length, uint64_t *handle);
            int32_t ehv_session_write(const uint8_t *directory, size_t length, const uint8_t *input, size_t size);
            int32_t ehv_session_clear(const uint8_t *directory, size_t length);
        """.trimIndent() + "\n" + listOf("OK", "INVALID_ARGUMENT", "INVALID_HANDLE", "BUFFER_TOO_SMALL", "LIMIT", "INTERNAL", "PANIC", "PARSE", "STORAGE", "NO_SESSION").mapIndexed { index, name -> "#define EHV_$name $index\n" }.joinToString("")
        verifyGalleryCapiHeader(header)
        for (bad in listOf(header.replace("uint64_t *handle", "uint32_t *handle"), header.replace("EHV_OK 0", "EHV_OK 1"), header.replace("int32_t ehv_buffer_free(uint64_t handle);", ""))) {
            assertThrows(IllegalArgumentException::class.java) { verifyGalleryCapiHeader(bad) }
        }
    }
}
