package com.ehviewer.core.network.gallery

import com.ehviewer.core.domain.download.GalleryImageCheckpoint
import com.ehviewer.core.domain.download.GalleryImageIdentity
import com.ehviewer.core.domain.download.GalleryImageProgress.Companion.CHUNK_BYTES
import com.ehviewer.core.domain.download.isStrongImageEtag
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import okio.ByteString.Companion.encodeUtf8

/** Strict single-range transport; RFC 9110 sections 13.1.5 and 15.3.7. */
internal class PartialGalleryImageRequest(
    private val client: HttpClient,
    private val beforeRequest: suspend () -> Unit,
    private val validate: (Url, Boolean) -> Unit,
) {
    suspend fun fetch(initial: Url, referer: Url, pageToken: String, checkpoint: GalleryImageCheckpoint): ByteArray {
        val source = initial.toString().encodeUtf8().sha256().hex()
        var saved = checkpoint.progress()
        saved.validate()
        if (saved.identity?.let { it.pageToken != pageToken || it.sourceSha256 != source } == true || saved.bytes > 0 && saved.bytes == saved.identity?.totalBytes && !saved.complete) {
            checkpoint.reset(null)
            saved = checkpoint.progress()
        }
        beforeRequest()
        validate(initial, true)
        if (saved.complete) return checkpoint.readComplete()
        val resume = saved.identity?.takeIf { saved.bytes > 0 }
        var url = initial
        repeat(6) { attempt ->
            beforeRequest()
            validate(url, true)
            var redirect: Url? = null
            val result = client.prepareGet(url) {
                header(HttpHeaders.Referrer, referer.toString())
                header(HttpHeaders.AcceptEncoding, "identity")
                if (resume != null) {
                    header(HttpHeaders.Range, "bytes=${saved.bytes}-")
                    header(HttpHeaders.IfRange, resume.etag)
                }
            }.execute { response ->
                beforeRequest()
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
                suspend fun rejected(): Nothing {
                    checkpoint.reset(null)
                    invalid()
                }
                val status = response.status.value
                if (status !in setOf(200, 206)) rejected()
                val encoding = response.headers[HttpHeaders.ContentEncoding]
                if (encoding != null && !encoding.equals("identity", ignoreCase = true)) rejected()
                val type = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
                if (type != null && !type.startsWith("image/") && type != "application/octet-stream") rejected()
                val lengthHeader = response.headers[HttpHeaders.ContentLength]
                val length = lengthHeader?.toLongOrNull()
                if (lengthHeader != null && (length == null || length < 0)) rejected()
                if (length != null && length > MAX_IMAGE_BYTES) throw GalleryRequestException(GalleryFailure.RESPONSE_TOO_LARGE)
                val etag = response.headers[HttpHeaders.ETag]
                val target = url.toString().encodeUtf8().sha256().hex()
                val identity = if (status == 206) {
                    val previous = resume ?: rejected()
                    val range = response.headers[HttpHeaders.ContentRange]?.let { Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(it) } ?: rejected()
                    val start = range.groupValues[1].toLongOrNull() ?: rejected()
                    val end = range.groupValues[2].toLongOrNull() ?: rejected()
                    val total = range.groupValues[3].toLongOrNull() ?: rejected()
                    // A server may shorten a range, but this client accepts only the complete suffix.
                    if (start != saved.bytes || total != previous.totalBytes || end != total - 1 || length != null && length != total - start || previous.targetSha256 != target || etag != null && etag != previous.etag) rejected()
                    previous
                } else {
                    if (response.headers[HttpHeaders.ContentRange] != null) rejected()
                    val next = if (etag != null && isStrongImageEtag(etag) && length != null && length > 0) GalleryImageIdentity(pageToken, source, target, etag, length).also { it.validate() } else null
                    // 200 always replaces the old prefix, including a changed validator.
                    checkpoint.reset(next)
                    next
                }
                val channel = response.bodyAsChannel()
                try {
                    if (identity == null) {
                        val bytes = channel.readRemaining(MAX_IMAGE_BYTES + 1L).readByteArray()
                        if (bytes.size > MAX_IMAGE_BYTES) throw GalleryRequestException(GalleryFailure.RESPONSE_TOO_LARGE)
                        if (bytes.isEmpty() || length != null && bytes.size.toLong() != length) invalid()
                        beforeRequest()
                        return@execute bytes
                    }
                    var received = if (status == 206) saved.bytes else 0L
                    val pending = ByteArray(CHUNK_BYTES)
                    var count = 0
                    suspend fun spill() {
                        if (count == 0) return
                        val chunk = pending.copyOf(count)
                        count = 0 // Publication can finish even if cancellation wins the return dispatch.
                        withContext(NonCancellable) { checkpoint.append(chunk) }
                    }
                    try {
                        while (true) {
                            beforeRequest()
                            val read = channel.readAvailable(pending, count, pending.size - count)
                            if (read == -1) break
                            received += read
                            if (received > identity.totalBytes) {
                                count = 0
                                rejected()
                            }
                            count += read
                            if (count == pending.size) spill()
                        }
                    } catch (cancelled: CancellationException) {
                        spill()
                        throw cancelled
                    } catch (failure: GalleryRequestException) {
                        throw failure
                    } catch (failure: Exception) {
                        spill()
                        throw failure
                    }
                    spill()
                    if (received != identity.totalBytes) invalid()
                    beforeRequest()
                    checkpoint.finish()
                    checkpoint.readComplete()
                } finally {
                    channel.cancel(null)
                }
            }
            if (result != null) return result
            url = redirect ?: invalid()
        }
        invalid()
    }
}
