package com.ehviewer.core.network.gallery

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable

/** The existing Rust torrent document, without application-specific Android classes. */
@Serializable
data class GalleryTorrent(
    val outdated: Boolean,
    val posted: String,
    val size: String,
    val seeds: Int,
    val peers: Int,
    val downloads: Int,
    val uploader: String,
    val url: String,
    val name: String,
) {
    /** Construct the original public tracker magnet; never forward URL credentials or query data. */
    fun magnet(gid: Long): String {
        require(gid > 0)
        if (name.isBlank() || name.length > 16384 || uploader.length > 256 || posted.length > 32 || size.length > 64 ||
            seeds < 0 || peers < 0 || downloads < 0 || url.length > 4096 ||
            listOf(name, uploader, posted, size).any { value -> value.any { it.code < 32 || it.code == 127 } }
        ) {
            invalid()
        }
        val parsed = try {
            Url(url)
        } catch (_: Exception) {
            invalid()
        }
        if (parsed.protocol != URLProtocol.HTTPS || parsed.port != 443 ||
            !parsed.user.isNullOrEmpty() || !parsed.password.isNullOrEmpty() ||
            parsed.host !in setOf("ehtracker.org", "e-hentai.org", "exhentai.org") ||
            parsed.parameters.names().isNotEmpty() || parsed.fragment.isNotEmpty()
        ) {
            invalid()
        }
        val hash = Regex("([a-fA-F0-9]{40})\\.torrent$").find(parsed.encodedPath)?.groupValues?.get(1)?.lowercase() ?: invalid()
        return "magnet:?xt=urn:btih:$hash&dn=${name.encodeURLParameter()}&tr=${"https://ehtracker.org/$gid/announce".encodeURLParameter()}"
    }
}
