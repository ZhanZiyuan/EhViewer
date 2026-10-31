package com.ehviewer.core.network.gallery

import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryPreview
import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath

/** Actual gallery metadata, preview-page cursors, image documents and bounded image downloads. */
class GalleryContentRepository(client: HttpClient, private val decoder: GalleryDocumentDecoder, restricted: Boolean = false) : GalleryContent {
    private val host = if (restricted) "exhentai.org" else "e-hentai.org"
    private val requests = GalleryRequests(client, host)

    override suspend fun details(gid: Long, token: String, previewPage: Int): GalleryDetail {
        require(gid > 0 && Regex("[a-f0-9]{10}").matches(token) && previewPage >= 0)
        val url = URLBuilder(URLProtocol.HTTPS, host).apply {
            encodedPath = "/g/$gid/$token/"
            if (previewPage > 0) parameters.append("p", previewPage.toString())
        }.build()
        return requests.document(url, decoder::decodeDetails).also { detail ->
            if (detail.gid != gid || detail.token != token || detail.pages <= 0 || detail.previewList.any { it.position !in 0 until detail.pages || !Regex("[a-f0-9]{10}").matches(it.pToken) }) invalid()
            if (detail.previewList.map { it.position }.toSet().size != detail.previewList.size) invalid()
        }
    }

    override fun pageUrl(gid: Long, pageToken: String, index: Int): Url {
        require(gid > 0 && Regex("[a-f0-9]{10}").matches(pageToken) && index >= 0)
        return Url("https://$host/s/$pageToken/$gid-${index.toLong() + 1}")
    }

    /** Links are untrusted parser output. Navigation stays within this gallery and service. */
    override fun pageIndex(url: String, gid: Long): Int {
        val parsed = try {
            Url(url)
        } catch (_: Exception) {
            invalid()
        }
        if (parsed.protocol != URLProtocol.HTTPS || parsed.host != host || !parsed.user.isNullOrEmpty() || !parsed.password.isNullOrEmpty() || parsed.port != 443 || !parsed.parameters.isEmpty() || parsed.fragment.isNotEmpty()) invalid()
        val match = Regex("/s/[a-f0-9]{10}/([0-9]+)-([0-9]+)").matchEntire(parsed.encodedPath) ?: invalid()
        if (match.groupValues[1].toLongOrNull() != gid) invalid()
        val number = match.groupValues[2].toIntOrNull() ?: invalid()
        if (number <= 0) invalid()
        return number - 1
    }

    override suspend fun page(url: String, gid: Long, reloadKey: String?): GalleryImagePage {
        pageIndex(url, gid)
        if (reloadKey != null && (reloadKey.length !in 1..256 || reloadKey.any { it !in '!'..'~' })) invalid()
        val request = URLBuilder(Url(url)).apply { if (reloadKey != null) parameters.append("nl", reloadKey) }.build()
        return requests.document(request, decoder::decodePage).also { page ->
            for (link in listOfNotNull(page.previousPageUrl, page.nextPageUrl)) pageIndex(link, gid)
            val image = try {
                Url(page.imageUrl)
            } catch (_: Exception) {
                invalid()
            }
            if (image.protocol != URLProtocol.HTTPS || !isGalleryImageHost(image.host) || !image.user.isNullOrEmpty() || !image.password.isNullOrEmpty()) invalid()
        }
    }

    override suspend fun image(page: GalleryImagePage, pageUrl: String, gid: Long): ByteArray {
        pageIndex(pageUrl, gid)
        return requests.image(
            try {
                Url(page.imageUrl)
            } catch (_: Exception) {
                invalid()
            },
            Url(pageUrl),
        )
    }

    /** Thumbnails and sprite sheets use the same bounded, validated image transport as reader pages. */
    override suspend fun preview(preview: GalleryPreview, gid: Long, token: String): ByteArray {
        require(gid > 0 && Regex("[a-f0-9]{10}").matches(token) && preview.position >= 0)
        val url = try {
            Url(preview.url)
        } catch (_: Exception) {
            invalid()
        }
        return requests.image(url, Url("https://$host/g/$gid/$token/"))
    }
}
