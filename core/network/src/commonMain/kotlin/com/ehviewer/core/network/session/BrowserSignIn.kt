package com.ehviewer.core.network.session

import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native browser metadata, including HttpOnly cookies. Never log or serialize this transfer. */
class BrowserSessionCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val expiresAt: Long?,
) {
    override fun toString(): String = "BrowserSessionCookie(REDACTED)"
}

/** Main-frame navigation policy shared with native browsers; no external schemes or popups. */
fun isGalleryLoginNavigation(url: String): Boolean = try {
    val parsed = Url(url)
    parsed.protocol.name == "https" && parsed.port == 443 && parsed.host in setOf("e-hentai.org", "exhentai.org", "forums.e-hentai.org") && parsed.user.isNullOrEmpty() && parsed.password.isNullOrEmpty()
} catch (_: Exception) {
    false
}

/** Captured before opening a browser, so logout or a newer login invalidates its eventual result. */
class BrowserSignInTransaction internal constructor(private val cookies: PersistentCookieStorage, private val generation: Long) {
    private val mutex = Mutex()
    private var committed = false

    suspend fun complete(incoming: List<BrowserSessionCookie>) = mutex.withLock {
        if (committed) throw CancellationException("Browser sign-in already completed")
        try {
            require(incoming.size <= 128)
            val jar = AuthenticationCookieJar()
            for (item in incoming) {
                val host = item.domain.trimStart('.').lowercase()
                // Ignore unrelated browser subresource cookies; never widen a host-only cookie.
                if (host !in setOf("e-hentai.org", "exhentai.org", "forums.e-hentai.org")) continue
                require(item.domain == item.domain.trim() && !item.domain.startsWith(".."))
                require(item.name.isNotEmpty() && item.name.all { it.code in 0x21..0x7e && it !in "()<>@,;:\\\"/[]?={} " })
                require(item.value.none { it.code < 0x20 || it.code == 0x7f || it == ';' })
                require(item.path.startsWith('/') && item.path.length <= 2048 && item.path.none { it.code < 0x20 || it.code == 0x7f })
                jar.addCookie(
                    Url("https://$host/"),
                    Cookie(item.name, item.value, encoding = CookieEncoding.RAW, domain = item.domain.takeIf { it.startsWith('.') }, path = item.path, secure = item.secure, httpOnly = item.httpOnly, expires = item.expiresAt?.let(::GMTDate)),
                )
            }
            val identity = jar.get(Url("https://e-hentai.org/"))
            if (!listOf("ipb_member_id", "ipb_pass_hash").all { name -> identity.any { it.name == name && it.value.isNotEmpty() } }) {
                throw GalleryRequestException(GalleryFailure.AUTHENTICATION)
            }
            cookies.commitAuthentication(generation, jar.snapshot())
            committed = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: GalleryRequestException) {
            throw failure
        } catch (_: SessionStorageException) {
            throw GalleryRequestException(GalleryFailure.SESSION_STORAGE)
        } catch (_: Exception) {
            throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
        }
    }
}
