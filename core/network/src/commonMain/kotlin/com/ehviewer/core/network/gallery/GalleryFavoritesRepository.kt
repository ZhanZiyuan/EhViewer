package com.ehviewer.core.network.gallery

import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.network.session.SessionStorageException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodeCookieValue
import io.ktor.http.encodedPath
import io.ktor.http.parseClientCookiesHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer
import okio.ByteString.Companion.toByteString

/** A fetched editor is bound to its gallery, site and current account, never to a guessed default. */
class GalleryFavoriteState internal constructor(
    val gid: Long,
    val token: String,
    val category: Int?,
    val note: GalleryFavoriteNote,
    val categories: List<String>,
    internal val host: String,
    internal val identity: String,
    internal val epoch: Long,
)

/** Reuses the existing Ktor client and original form protocol; no automatic mutation retry. */
class GalleryFavoritesRepository(
    private val client: HttpClient,
    private val cookies: CookiesStorage,
    private val decoder: GalleryDocumentDecoder,
    private val restricted: Boolean = false,
    private val sessionEpoch: suspend () -> Long = { 0L },
) {
    private val mutex = Mutex()
    private val host = if (restricted) "exhentai.org" else "e-hentai.org"
    private data class Authority(val identity: String, val epoch: Long)

    suspend fun isSignedIn(): Boolean = operation { mutex.withLock { currentAuthority() != null } }

    /** Uses the same authentication boundary as the existing editor. Guests have no cloud choices. */
    suspend fun availableCategories(): List<String> = operation {
        mutex.withLock {
            val authority = currentAuthority() ?: return@withLock emptyList()
            val requests = GalleryRequests(client, host) { guard(authority) }
            readCategories(requests).also { guard(authority) }
        }
    }

    suspend fun inspect(gid: Long, token: String): GalleryFavoriteState {
        validateGallery(gid, token)
        return operation { mutex.withLock { read(gid, token, authority()) } }
    }

    suspend fun inspectIfSignedIn(gid: Long, token: String): GalleryFavoriteState? {
        validateGallery(gid, token)
        return operation { mutex.withLock { currentAuthority()?.let { read(gid, token, it) } } }
    }

    /** null removes the online favorite. Re-read server state before reporting success. */
    suspend fun update(state: GalleryFavoriteState, category: Int?, note: String): GalleryFavoriteState {
        validateGallery(state.gid, state.token)
        require(state.host == host && (category == null || category in 0..9))
        val normalized = note.replace("\r\n", "\n").replace('\r', '\n')
        require(normalized.length <= state.note.maximumCharacters && '\u0000' !in normalized)
        return operation {
            mutex.withLock {
                val authority = Authority(state.identity, state.epoch)
                guard(authority)
                val requests = GalleryRequests(client, host) { guard(authority) }
                requests.favoriteForm(
                    popup(state.gid, state.token),
                    Parameters.build {
                        append("favcat", category?.toString() ?: "favdel")
                        append("favnote", normalized)
                        append("apply", "Apply Changes")
                        append("update", "1")
                    },
                ) { attached ->
                    guard(authority)
                    val actual = attached.split(';').flatMap { part ->
                        parseClientCookiesHeader(part, false).map { (name, value) -> Cookie(name, value, encoding = CookieEncoding.RAW) }
                    }
                    if (identity(actual) != authority.identity) throw CancellationException("Session changed during favorite request")
                }
                read(state.gid, state.token, authority).also { actual ->
                    if (actual.category != category || category != null && actual.note.note != normalized) invalid()
                }
            }
        }
    }

    private suspend fun read(gid: Long, token: String, authority: Authority): GalleryFavoriteState {
        guard(authority)
        val requests = GalleryRequests(client, host) { guard(authority) }
        // Authenticated configuration distinguishes expired sessions from a public guest detail.
        val categories = readCategories(requests)
        val note = requests.freshDocument(popup(gid, token), Url("https://$host/g/$gid/$token/"), decoder::decodeFavoriteNote)
        try {
            note.validate()
        } catch (_: IllegalArgumentException) {
            invalid()
        }
        val detail = requests.freshDocument(Url("https://$host/g/$gid/$token/"), Url("https://$host/"), decoder::decodeDetails)
        if (detail.gid != gid || detail.token != token || detail.pages <= 0) invalid()
        val category = when (val slot = detail.favoriteSlot) {
            GalleryInfo.NOT_FAVORITED -> null
            in 0..9 -> slot
            else -> invalid()
        }
        guard(authority)
        return GalleryFavoriteState(gid, token, category, note, categories.toList(), host, authority.identity, authority.epoch)
    }

    private suspend fun readCategories(requests: GalleryRequests): List<String> {
        val categories = requests.freshDocument(Url("https://$host/uconfig.php"), Url("https://$host/"), decoder::decodeUserConfig)
        if (categories.size != 10 || categories.any { it.length > 256 }) invalid()
        return categories.toList()
    }

    private fun popup(gid: Long, token: String): Url = URLBuilder(URLProtocol.HTTPS, host).apply {
        encodedPath = "/gallerypopups.php"
        parameters.append("gid", gid.toString())
        parameters.append("t", token)
        parameters.append("act", "addfav")
    }.build()

    private fun validateGallery(gid: Long, token: String) {
        require(gid > 0 && Regex("[a-f0-9]{10}").matches(token))
    }

    private suspend fun identity(): String? = identity(cookies.get(Url("https://$host/")))

    private fun identity(records: List<Cookie>): String? {
        val encoded = Buffer()
        for (name in listOf("ipb_member_id", "ipb_pass_hash")) {
            val value = records.filter { it.name == name }.map { encodeCookieValue(it.value, it.encoding) }.distinct().singleOrNull() ?: return null
            if (value.isEmpty() || value.length > 16384) return null
            val bytes = value.encodeToByteArray()
            encoded.writeUtf8(name).writeInt(bytes.size).write(bytes)
        }
        return encoded.readByteArray().toByteString().sha256().hex()
    }

    private suspend fun currentAuthority(): Authority? {
        val epoch = sessionEpoch()
        val identity = identity()
        if (sessionEpoch() != epoch) throw CancellationException("Session changed during favorite request")
        return identity?.let { Authority(it, epoch) }
    }

    private suspend fun authority(): Authority = currentAuthority() ?: throw GalleryRequestException(GalleryFailure.AUTHENTICATION)

    private suspend fun guard(expected: Authority) {
        if (sessionEpoch() != expected.epoch || identity() != expected.identity) throw CancellationException("Session changed during favorite request")
    }

    private suspend fun <T> operation(block: suspend () -> T): T = try {
        block()
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
