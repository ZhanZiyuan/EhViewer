package com.ehviewer.core.network.gallery

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.network.session.SessionStorageException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable

/** The original Rust HTML parser supplies this CBOR-compatible response on every platform. */
@Serializable
data class GallerySearchResult(val prev: String?, val next: String?, val galleryInfoList: List<BaseGalleryInfo>)

fun interface GalleryListDecoder {
    fun decode(html: ByteArray): GallerySearchResult
}

enum class GalleryFailure { AUTHENTICATION, CHALLENGE, RATE_LIMIT, OFFLINE, SERVER, INVALID_RESPONSE, RESPONSE_TOO_LARGE, SESSION_STORAGE }

class GalleryRequestException(val failure: GalleryFailure) : Exception("Gallery request failed: $failure")

/** Search and cursor pagination use the original service, Rust parser and shared gallery model. */
class GalleryRepository(private val client: HttpClient, private val decoder: GalleryListDecoder, private val restricted: Boolean = false) {
    private val host = if (restricted) "exhentai.org" else "e-hentai.org"

    suspend fun search(query: String = "", cursor: String? = null, previous: Boolean = false): GallerySearchResult {
        require(query.length <= 4096 && (cursor == null || Regex("[0-9]+(?:-[0-9]+)?").matches(cursor)))
        val url = URLBuilder(URLProtocol.HTTPS, host).apply {
            encodedPath = "/"
            if (query.isNotBlank()) parameters.append("f_search", query)
            if (cursor != null) parameters.append(if (previous) "prev" else "next", cursor)
        }.build()
        return try {
            client.prepareGet(url) { header(HttpHeaders.Referrer, "https://$host/") }.execute { response ->
                when (response.status.value) {
                    401 -> throw GalleryRequestException(GalleryFailure.AUTHENTICATION)
                    403 -> throw GalleryRequestException(GalleryFailure.CHALLENGE)
                    429 -> throw GalleryRequestException(GalleryFailure.RATE_LIMIT)
                    in 500..599 -> throw GalleryRequestException(GalleryFailure.SERVER)
                }
                if (response.status.value !in 200..299) throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
                val destination = response.call.request.url
                if (destination.protocol != URLProtocol.HTTPS || destination.host !in setOf(host, "forums.e-hentai.org")) throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
                if (destination.encodedPath.contains("login")) throw GalleryRequestException(GalleryFailure.AUTHENTICATION)
                val channel = response.bodyAsChannel()
                val bytes = try {
                    channel.readRemaining(MAX_HTML_BYTES + 1L).readByteArray()
                } finally {
                    channel.cancel(null)
                }
                if (bytes.size > MAX_HTML_BYTES) throw GalleryRequestException(GalleryFailure.RESPONSE_TOO_LARGE)
                try {
                    withContext(Dispatchers.Default) { decoder.decode(bytes) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: GalleryRequestException) {
            throw failure
        } catch (_: SessionStorageException) {
            throw GalleryRequestException(GalleryFailure.SESSION_STORAGE)
        } catch (_: Exception) {
            throw GalleryRequestException(GalleryFailure.OFFLINE)
        }
    }

    fun galleryUrl(gid: Long, token: String): Url {
        require(gid > 0 && Regex("[a-f0-9]{10}").matches(token))
        return Url("https://$host/g/$gid/$token/")
    }

    private companion object {
        const val MAX_HTML_BYTES = 8 * 1024 * 1024
    }
}
