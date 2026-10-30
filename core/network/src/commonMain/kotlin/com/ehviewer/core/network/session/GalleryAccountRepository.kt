package com.ehviewer.core.network.session

import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.gallery.GalleryRequests
import com.ehviewer.core.network.gallery.isGalleryImageHost
import com.ehviewer.core.network.galleryHttpClient
import com.ehviewer.core.network.platformNetworkEngine
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Real post-login initialization. Failed or stale work never replaces a durable session. */
class GalleryAccountRepository(
    private val cookies: PersistentCookieStorage,
    private val decoder: GalleryAccountDecoder,
    private val userAgent: String,
    private val engineFactory: () -> HttpClientEngine = ::platformNetworkEngine,
) {
    private val mutex = Mutex()

    suspend fun refresh(): GalleryAccountOverview = mutex.withLock {
        try {
            val (generation, snapshot) = cookies.accountSnapshot()
            val jar = AuthenticationCookieJar(snapshot)
            val engine = engineFactory()
            try {
                val client = galleryHttpClient(engine, jar, userAgent)
                try {
                    val currentSession: suspend () -> Unit = { cookies.requireGeneration(generation) }
                    val forum = GalleryRequests(client, "forums.e-hentai.org", currentSession)
                    val link = forum.document(Url("https://forums.e-hentai.org/"), decoder::decodeProfileLink)
                        ?: throw GalleryRequestException(GalleryFailure.AUTHENTICATION)
                    val profileUrl = validatedProfileUrl(link)
                    val profile = forum.document(profileUrl, decoder::decodeProfile).also {
                        if (it.displayName.isBlank() || it.displayName.length > 256) invalid()
                    }.let { it.copy(avatar = it.avatar?.takeIf(::safeAvatar)) }
                    val publicSite = GalleryRequests(client, "e-hentai.org", currentSession)
                    val categories = publicSite.document(Url("https://e-hentai.org/uconfig.php"), decoder::decodeUserConfig).also(::validateCategories)
                    val newsInitialized = try {
                        publicSite.document(Url("https://e-hentai.org/news.php")) { Unit }
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: GalleryRequestException) {
                        false
                    }
                    val beforeRestricted = jar.snapshot()
                    var access = RestrictedSiteAccess.UNKNOWN
                    var restrictedFailure: GalleryFailure? = null
                    try {
                        val restricted = GalleryRequests(client, "exhentai.org", currentSession)
                        // Preserve the original first-access initialization retry,
                        // bounded to one retry and only a malformed document.
                        try {
                            restricted.document(Url("https://exhentai.org/uconfig.php"), decoder::decodeUserConfig).also(::validateCategories)
                        } catch (failure: GalleryRequestException) {
                            if (failure.failure != GalleryFailure.INVALID_RESPONSE) throw failure
                            restricted.document(Url("https://exhentai.org/uconfig.php"), decoder::decodeUserConfig).also(::validateCategories)
                        }
                        val restrictedIdentity = jar.get(Url("https://exhentai.org/"))
                        if (!listOf("ipb_member_id", "ipb_pass_hash").all { name -> restrictedIdentity.any { it.name == name && it.value.isNotEmpty() } }) {
                            throw GalleryRequestException(GalleryFailure.AUTHENTICATION)
                        }
                        access = RestrictedSiteAccess.AVAILABLE
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: GalleryRequestException) {
                        jar.restore(beforeRestricted)
                        restrictedFailure = failure.failure
                        if (failure.failure in setOf(GalleryFailure.AUTHENTICATION, GalleryFailure.INVALID_RESPONSE)) access = RestrictedSiteAccess.UNAVAILABLE
                    }
                    cookies.commitAuthentication(generation, jar.snapshot())
                    GalleryAccountOverview(profile, categories, access, restrictedFailure, newsInitialized)
                } finally {
                    client.close()
                }
            } finally {
                engine.close()
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
}

private fun invalid(): Nothing = throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)

private fun validatedProfileUrl(value: String): Url {
    val url = try {
        Url(value)
    } catch (_: Exception) {
        invalid()
    }
    if (url.protocol != URLProtocol.HTTPS || url.host != "forums.e-hentai.org" || url.port != 443 || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() || url.fragment.isNotEmpty() || url.encodedPath != "/index.php") invalid()
    val id = url.parameters["showuser"] ?: if (url.parameters["act"] == "Profile") url.parameters["MID"] else null
    if (id == null || !Regex("[1-9][0-9]*").matches(id) || id.length > 20) invalid()
    return url
}

private fun safeAvatar(value: String): Boolean = runCatching {
    val url = Url(value)
    value.length <= 8192 && url.protocol == URLProtocol.HTTPS && url.port == 443 && isGalleryImageHost(url.host) && url.user.isNullOrEmpty() && url.password.isNullOrEmpty()
}.getOrDefault(false)

private fun validateCategories(value: List<String>) {
    if (value.size != 10 || value.any { it.length > 256 }) invalid()
}
