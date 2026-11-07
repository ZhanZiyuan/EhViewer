package com.ehviewer.core.network.gallery

import com.ehviewer.core.domain.download.GalleryDownloadStorageException
import com.ehviewer.core.domain.download.GalleryImageCheckpoint
import com.ehviewer.core.network.session.SessionStorageException
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpSendPipeline
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray

internal const val MAX_HTML_BYTES = 8 * 1024 * 1024
internal const val MAX_IMAGE_BYTES = 32 * 1024 * 1024

private class FavoriteMutationCheck(val check: suspend (String) -> Unit)
private val mutationCheck = AttributeKey<FavoriteMutationCheck>("EhViewerFavoriteMutationCheck")
private val mutationCheckInstalled = AttributeKey<Unit>("EhViewerFavoriteMutationCheckInstalled")

/** Check the cookies actually attached by Ktor, after State and before the engine can send. */
private fun HttpClient.checkFavoriteMutations() {
    attributes.computeIfAbsent(mutationCheckInstalled) {
        sendPipeline.intercept(HttpSendPipeline.Monitoring) {
            context.attributes.getOrNull(mutationCheck)?.check?.invoke(context.headers[HttpHeaders.Cookie].orEmpty())
            proceed()
        }
    }
}

/** Bound both the stream and redirects before following them; never expose response bodies or URLs. */
internal class GalleryRequests(private val client: HttpClient, private val host: String, private val beforeRequest: suspend () -> Unit = {}) {
    suspend fun <T> document(url: Url, decode: (ByteArray) -> T): T = document(url, Url("https://$host/"), decode)

    suspend fun <T> document(url: Url, referer: Url, decode: (ByteArray) -> T): T = document(url, referer, decode, fresh = false)

    suspend fun <T> freshDocument(url: Url, referer: Url, decode: (ByteArray) -> T): T = document(url, referer, decode, fresh = true)

    private suspend fun <T> document(url: Url, referer: Url, decode: (ByteArray) -> T, fresh: Boolean): T = operation {
        validate(referer, image = false)
        val bytes = fetch(url, referer.toString(), MAX_HTML_BYTES, image = false, fresh = fresh)
        beforeRequest()
        try {
            withContext(Dispatchers.Default) { decode(bytes) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
        }
    }

    /** One mutation only. Redirects are validated but never replay the form or credentials. */
    suspend fun favoriteForm(url: Url, fields: Parameters, checkAttachedCookies: suspend (String) -> Unit): Unit = operation {
        validate(url, image = false)
        if (url.host != host || url.encodedPath != "/gallerypopups.php" || url.parameters["act"] != "addfav") invalid()
        if (fields.names() != setOf("favcat", "favnote", "apply", "update") || fields.names().any { fields.getAll(it)?.size != 1 }) invalid()
        if (fields["favcat"] !in (0..9).map(Int::toString) + "favdel" || fields["apply"] != "Apply Changes" || fields["update"] != "1" || fields["favnote"]!!.length > 4096) invalid()
        beforeRequest()
        client.checkFavoriteMutations()
        client.preparePost(url) {
            attributes.put(mutationCheck, FavoriteMutationCheck(checkAttachedCookies))
            header(HttpHeaders.Referrer, url.toString())
            header(HttpHeaders.Origin, "https://$host")
            setBody(FormDataContent(fields))
        }.execute { response ->
            val channel = response.bodyAsChannel()
            try {
                when (response.status.value) {
                    401 -> throw GalleryRequestException(GalleryFailure.AUTHENTICATION)
                    403 -> throw GalleryRequestException(GalleryFailure.CHALLENGE)
                    429 -> throw GalleryRequestException(GalleryFailure.RATE_LIMIT)
                    in 500..599 -> throw GalleryRequestException(GalleryFailure.SERVER)
                }
                if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                    validate(galleryRedirectUrl(url, response.headers[HttpHeaders.Location] ?: invalid()), image = false)
                    // The repository confirms the actual favorite with new bounded GETs.
                    return@execute
                }
                if (response.status.value !in 200..299) invalid()
                if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > MAX_HTML_BYTES) throw GalleryRequestException(GalleryFailure.RESPONSE_TOO_LARGE)
                if (channel.readRemaining(MAX_HTML_BYTES + 1L).readByteArray().size > MAX_HTML_BYTES) throw GalleryRequestException(GalleryFailure.RESPONSE_TOO_LARGE)
            } finally {
                channel.cancel(null)
            }
        }
        beforeRequest()
    }

    suspend fun image(url: Url, referer: Url): ByteArray = operation {
        fetch(url, referer.toString(), MAX_IMAGE_BYTES, image = true)
    }

    suspend fun partialImage(url: Url, referer: Url, pageToken: String, checkpoint: GalleryImageCheckpoint): ByteArray = operation {
        PartialGalleryImageRequest(client, beforeRequest, ::validate).fetch(url, referer, pageToken, checkpoint)
    }

    private suspend fun fetch(initial: Url, referer: String, maximum: Int, image: Boolean, fresh: Boolean = false): ByteArray {
        var url = initial
        repeat(6) { attempt ->
            beforeRequest()
            validate(url, image)
            var redirect: Url? = null
            val bytes = client.prepareGet(url) {
                header(HttpHeaders.Referrer, referer)
                if (fresh) header(HttpHeaders.CacheControl, "no-cache, no-store")
            }.execute { response ->
                when (response.status.value) {
                    401 -> throw GalleryRequestException(GalleryFailure.AUTHENTICATION)
                    403 -> throw GalleryRequestException(GalleryFailure.CHALLENGE)
                    429 -> throw GalleryRequestException(GalleryFailure.RATE_LIMIT)
                    in 500..599 -> throw GalleryRequestException(GalleryFailure.SERVER)
                }
                if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                    val location = response.headers[HttpHeaders.Location] ?: invalid()
                    if (attempt == 5) invalid()
                    redirect = galleryRedirectUrl(url, location)
                    return@execute null
                }
                if (response.status.value !in 200..299) invalid()
                if (image) {
                    val type = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
                    if (type != null && !type.startsWith("image/") && type != "application/octet-stream") invalid()
                }
                val channel = response.bodyAsChannel()
                val result = try {
                    if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > maximum) throw GalleryRequestException(GalleryFailure.RESPONSE_TOO_LARGE)
                    channel.readRemaining(maximum + 1L).readByteArray()
                } finally {
                    channel.cancel(null)
                }
                if (result.size > maximum) throw GalleryRequestException(GalleryFailure.RESPONSE_TOO_LARGE)
                if (result.isEmpty()) invalid()
                result
            }
            if (bytes != null) return bytes
            url = redirect ?: invalid()
        }
        invalid()
    }

    private fun validate(url: Url, image: Boolean) {
        if (url.protocol != URLProtocol.HTTPS || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) invalid()
        val hathImage = image && (url.host == "hath.network" || url.host.endsWith(".hath.network"))
        // H@H clients use 443 or a configured nonprivileged port; account/service
        // traffic stays on 443, and the cookie jar never sends identity to H@H.
        if (url.port != 443 && !(hathImage && url.port in 1024..65534)) invalid()
        val forumLogin = url.host == "forums.e-hentai.org" && url.encodedPath.equals("/index.php", ignoreCase = true) && url.parameters["act"].equals("Login", ignoreCase = true)
        if (forumLogin || url.host in setOf(host, "forums.e-hentai.org") && url.encodedPath.contains("login", ignoreCase = true)) throw GalleryRequestException(GalleryFailure.AUTHENTICATION)
        val allowed = if (image) isGalleryImageHost(url.host) else url.host in setOf(host, "forums.e-hentai.org")
        if (!allowed) invalid()
    }
}

internal fun isGalleryImageHost(host: String): Boolean = listOf("e-hentai.org", "exhentai.org", "ehgt.org", "hath.network").any { host == it || host.endsWith(".$it") }
internal fun invalid(): Nothing = throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)

internal fun galleryRedirectUrl(base: Url, location: String): Url = try {
    URLBuilder(base).apply {
        parameters.clear()
        takeFrom(location)
    }.build()
} catch (_: Exception) {
    invalid()
}

private suspend fun <T> operation(block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: GalleryRequestException) {
    throw failure
} catch (failure: GalleryDownloadStorageException) {
    throw failure
} catch (_: SessionStorageException) {
    throw GalleryRequestException(GalleryFailure.SESSION_STORAGE)
} catch (_: Exception) {
    throw GalleryRequestException(GalleryFailure.OFFLINE)
}
