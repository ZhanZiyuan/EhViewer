package com.ehviewer.core.network.gallery

import com.ehviewer.core.model.BaseGalleryInfo
import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
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

/** Search and cursor pagination use the original service, Rust parser and shared gallery model. */
class GalleryRepository(private val client: HttpClient, private val decoder: GalleryListDecoder, private val restricted: Boolean = false) {
    private val host = if (restricted) "exhentai.org" else "e-hentai.org"
    private val requests = GalleryRequests(client, host)

    suspend fun search(query: String = "", cursor: String? = null, previous: Boolean = false): GallerySearchResult {
        require(query.length <= 4096 && (cursor == null || Regex("[0-9]+(?:-[0-9]+)?").matches(cursor)))
        val url = URLBuilder(URLProtocol.HTTPS, host).apply {
            encodedPath = "/"
            if (query.isNotBlank()) parameters.append("f_search", query)
            if (cursor != null) parameters.append(if (previous) "prev" else "next", cursor)
        }.build()
        return requests.document(url, decoder::decode)
    }

    fun galleryUrl(gid: Long, token: String): Url {
        require(gid > 0 && Regex("[a-f0-9]{10}").matches(token))
        return Url("https://$host/g/$gid/$token/")
    }
}
