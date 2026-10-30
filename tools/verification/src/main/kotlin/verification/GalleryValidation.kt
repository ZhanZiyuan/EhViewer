package verification

/** The shared cookie and repository suites must execute on both Android Host and desktop JVM. */
fun verifyNetworkTests(evidence: Map<String, Any?>) {
    require(evidence["status"] == "PASS" && evidence["executed"] == true && (evidence["tests"] as Number).toInt() >= 28)
    val suites = evidence["suites"].obj().mapKeys { (name, _) -> name.substringAfterLast('.').substringBefore('[') }
    require(suites.keys == setOf("GalleryRepositoryTest", "PersistentCookieStorageTest", "GalleryContentRepositoryTest")) { "Missing actual shared network test suites" }
    require((suites.getValue("GalleryRepositoryTest") as Number).toInt() >= 10 && (suites.getValue("PersistentCookieStorageTest") as Number).toInt() >= 9) { "Incomplete network/session regression" }
    require((suites.getValue("GalleryContentRepositoryTest") as Number).toInt() >= 9) { "Incomplete gallery/reader transport regression" }
}

/** ABI additions preserve all original widths, ownership operations and status codes. */
fun verifyGalleryCapiHeader(header: String) {
    val declarations = header.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("\\s+"), "")
    val prototypes = listOf(
        "uint32_tehv_abi_version(void);",
        "int32_tehv_hash_sha1(constuint8_t*input,size_tlength,uint64_t*handle);",
        "int32_tehv_buffer_copy(uint64_thandle,uint8_t*output,size_tcapacity,size_t*written);",
        "int32_tehv_buffer_free(uint64_thandle);",
        "int32_tehv_active_buffers(size_t*count);",
        "int32_tehv_parse_gallery_list(constuint8_t*input,size_tlength,uint64_t*handle);",
        "int32_tehv_parse_gallery_detail(constuint8_t*input,size_tlength,uint64_t*handle);",
        "int32_tehv_parse_gallery_page(constuint8_t*input,size_tlength,uint64_t*handle);",
        "int32_tehv_session_read(constuint8_t*directory,size_tlength,uint64_t*handle);",
        "int32_tehv_session_write(constuint8_t*directory,size_tlength,constuint8_t*input,size_tsize);",
        "int32_tehv_session_clear(constuint8_t*directory,size_tlength);",
    )
    prototypes.forEach { prototype -> require(Regex(Regex.escape(prototype)).findAll(declarations).count() == 1) { "Missing, duplicated or changed Rust C ABI signature" } }
    listOf("OK", "INVALID_ARGUMENT", "INVALID_HANDLE", "BUFFER_TOO_SMALL", "LIMIT", "INTERNAL", "PANIC", "PARSE", "STORAGE", "NO_SESSION").forEachIndexed { code, name ->
        require(Regex("(?m)^#define EHV_$name $code\\s*$").findAll(header).count() == 1) { "Changed Rust C ABI status code" }
    }
}

const val MINIMUM_NATIVE_SESSION_PASSES = 52
