package com.ehviewer.core.network.gallery

import com.ehviewer.core.model.BaseGalleryInfo
import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.encodedPath
import kotlinx.serialization.Serializable

/** The original Rust HTML parser supplies this CBOR-compatible response on every platform. */
@Serializable
data class GallerySearchResult(val prev: String?, val next: String?, val galleryInfoList: List<BaseGalleryInfo>)

fun interface GalleryListDecoder {
    fun decode(html: ByteArray): GallerySearchResult
}

enum class GalleryFailure { AUTHENTICATION, CHALLENGE, RATE_LIMIT, OFFLINE, SERVER, INVALID_RESPONSE, RESPONSE_TOO_LARGE, SESSION_STORAGE, LOCAL_STORAGE }

class GalleryRequestException(val failure: GalleryFailure) : Exception("Gallery request failed: $failure")

private val searchCursor = Regex("[0-9]+(?:-[0-9]+)?")
private fun validSearchCursor(cursor: String?) = cursor == null || cursor.length <= 64 && searchCursor.matches(cursor)

/** Search and cursor pagination use the original service, Rust parser and shared gallery model. */
class GalleryRepository(private val client: HttpClient, private val decoder: GalleryListDecoder, private val restricted: Boolean = false) {
    private val host = if (restricted) "exhentai.org" else "e-hentai.org"
    private val requests = GalleryRequests(client, host)

    suspend fun search(query: String = "", cursor: String? = null, previous: Boolean = false, filters: GallerySearchFilters = GallerySearchFilters()): GallerySearchResult {
        require(query.length <= 4096 && validSearchCursor(cursor))
        val url = URLBuilder(URLProtocol.HTTPS, host).apply {
            encodedPath = "/"
            if (query.isNotBlank()) parameters.append("f_search", query)
            if (cursor != null) parameters.append(if (previous) "prev" else "next", cursor)
            filters.appendTo(this)
        }.build()
        return requests.document(url, decoder::decode).also {
            if (!validSearchCursor(it.prev) || !validSearchCursor(it.next)) invalid()
        }
    }

    /** Match Android's uploader route, including names that cannot be expressed by search operators. */
    suspend fun uploader(name: String, cursor: String? = null, previous: Boolean = false, filters: GallerySearchFilters = GallerySearchFilters()): GallerySearchResult {
        if (name.isBlank() || name.length > 256 || name in setOf(".", "..") || name.any { it.code < 32 || it.code == 127 } || !validSearchCursor(cursor)) invalid()
        val url = URLBuilder(URLProtocol.HTTPS, host).apply {
            appendPathSegments("uploader", name, encodeSlash = true)
            if (cursor != null) parameters.append(if (previous) "prev" else "next", cursor)
            filters.appendTo(this)
        }.build()
        return requests.document(url, decoder::decode).also {
            if (!validSearchCursor(it.prev) || !validSearchCursor(it.next)) invalid()
        }
    }

    fun galleryUrl(gid: Long, token: String): Url {
        require(gid > 0 && Regex("[a-f0-9]{10}").matches(token))
        return Url("https://$host/g/$gid/$token/")
    }

    fun coverUrl(thumbKey: String): Url = galleryCoverUrl(thumbKey, restricted)

    /** Covers use the same bounded transport and actual cookie jar as gallery previews. */
    suspend fun cover(gid: Long, token: String, thumbKey: String): ByteArray {
        val referer = try {
            galleryUrl(gid, token)
        } catch (_: IllegalArgumentException) {
            invalid()
        }
        return requests.image(coverUrl(thumbKey), referer)
    }
}

/** Preserve the original Android E/Ex v1/v2 cover mapping without platform/global settings. */
internal fun galleryCoverUrl(key: String, restricted: Boolean): Url {
    if (key.length !in 1..4096 || key.any { it.isWhitespace() || it.code < 32 }) invalid()
    val address = if (key.startsWith("https://")) {
        key
    } else {
        val path = key.substringBefore('?')
        if (':' in path || '\\' in key || '#' in key || path.split('/').any { it.isEmpty() || it == "." || it == ".." } || Regex("%2e|%2f|%5c", RegexOption.IGNORE_CASE).containsMatchIn(path)) invalid()
        val base = if (!restricted) {
            "https://ehgt.org/"
        } else if (path.endsWith(".webp", ignoreCase = true)) {
            "https://s.exhentai.org/"
        } else {
            "https://s.exhentai.org/t/"
        }
        base + key
    }
    val url = try {
        Url(address)
    } catch (_: Exception) {
        invalid()
    }
    if (url.protocol != URLProtocol.HTTPS || url.host !in setOf("ehgt.org", "s.exhentai.org") || url.port != 443 ||
        !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() || url.fragment.isNotEmpty() || url.encodedPath == "/"
    ) {
        invalid()
    }
    return url
}
