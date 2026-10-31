package com.ehviewer.core.network.session

import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.galleryHttpClient
import com.ehviewer.core.network.platformNetworkEngine
import io.ktor.client.engine.HttpClientEngine
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
import io.ktor.http.takeFrom
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray

/** Real forum login. Passwords are request-only; staged cookies persist once after server success. */
class GalleryAuthenticationRepository(
    private val cookies: PersistentCookieStorage,
    private val userAgent: String,
    private val engineFactory: () -> HttpClientEngine = ::platformNetworkEngine,
) {
    private val signInMutex = Mutex()

    suspend fun beginBrowserSignIn(): BrowserSignInTransaction = try {
        BrowserSignInTransaction(cookies, cookies.authenticationGeneration())
    } catch (_: SessionStorageException) {
        throw GalleryRequestException(GalleryFailure.SESSION_STORAGE)
    }

    suspend fun signIn(username: String, password: String) {
        require(username.isNotBlank() && username.length <= 256 && password.isNotEmpty() && password.length <= 4096) { "Enter a supported username and password" }
        signInMutex.withLock {
            try {
                val generation = cookies.authenticationGeneration()
                val jar = AuthenticationCookieJar()
                val engine = engineFactory()
                try {
                    val client = galleryHttpClient(engine, jar, userAgent)
                    try {
                        var url = Url("https://forums.e-hentai.org/index.php?act=Login&CODE=01")
                        repeat(6) { attempt ->
                            validate(url)
                            val request = if (attempt == 0) {
                                client.preparePost(url) {
                                    header(HttpHeaders.Referrer, LOGIN_PAGE)
                                    header(HttpHeaders.Origin, "https://forums.e-hentai.org")
                                    setBody(
                                        FormDataContent(
                                            Parameters.build {
                                                append("referer", LOGIN_PAGE)
                                                append("b", "")
                                                append("bt", "")
                                                append("UserName", username)
                                                append("PassWord", password)
                                                append("CookieDate", "1")
                                            },
                                        ),
                                    )
                                }
                            } else {
                                // Credentials are never replayed on a redirect.
                                client.prepareGet(url) { header(HttpHeaders.Referrer, LOGIN_PAGE) }
                            }
                            var redirect: Url? = null
                            val body = request.execute { response ->
                                when (response.status.value) {
                                    401 -> failure(GalleryFailure.AUTHENTICATION)
                                    403 -> failure(GalleryFailure.CHALLENGE)
                                    429 -> failure(GalleryFailure.RATE_LIMIT)
                                    in 500..599 -> failure(GalleryFailure.SERVER)
                                }
                                if (response.status.value in setOf(301, 302, 303)) {
                                    if (attempt == 5) failure(GalleryFailure.INVALID_RESPONSE)
                                    val location = response.headers[HttpHeaders.Location] ?: failure(GalleryFailure.INVALID_RESPONSE)
                                    redirect = URLBuilder(url).takeFrom(location).build().also(::validate)
                                    return@execute null
                                }
                                if (response.status.value !in 200..299) failure(GalleryFailure.INVALID_RESPONSE)
                                val maximum = 8 * 1024 * 1024
                                val channel = response.bodyAsChannel()
                                val bytes = try {
                                    if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > maximum) failure(GalleryFailure.RESPONSE_TOO_LARGE)
                                    channel.readRemaining(maximum + 1L).readByteArray()
                                } finally {
                                    channel.cancel(null)
                                }
                                if (bytes.size > maximum) failure(GalleryFailure.RESPONSE_TOO_LARGE)
                                bytes.decodeToString(throwOnInvalidSequence = true)
                            }
                            if (body != null) {
                                val identity = jar.get(Url("https://e-hentai.org/"))
                                if (!Regex("<p>\\s*You are now logged in as:\\s*[^<]{1,512}<", RegexOption.IGNORE_CASE).containsMatchIn(body) ||
                                    !listOf("ipb_member_id", "ipb_pass_hash").all { name -> identity.any { it.name == name && it.value.isNotEmpty() } }
                                ) {
                                    failure(GalleryFailure.AUTHENTICATION)
                                }
                                cookies.commitAuthentication(generation, jar.snapshot())
                                return@withLock
                            }
                            url = redirect ?: failure(GalleryFailure.INVALID_RESPONSE)
                        }
                        failure(GalleryFailure.INVALID_RESPONSE)
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
                failure(GalleryFailure.SESSION_STORAGE)
            } catch (_: IllegalArgumentException) {
                failure(GalleryFailure.INVALID_RESPONSE)
            } catch (_: Exception) {
                failure(GalleryFailure.OFFLINE)
            }
        }
    }

    private fun validate(url: Url) {
        if (url.protocol != URLProtocol.HTTPS || url.host !in setOf("forums.e-hentai.org", "e-hentai.org") || url.port != 443 || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() || url.fragment.isNotEmpty()) failure(GalleryFailure.INVALID_RESPONSE)
    }

    private fun failure(reason: GalleryFailure): Nothing = throw GalleryRequestException(reason)

    private companion object {
        const val LOGIN_PAGE = "https://forums.e-hentai.org/index.php?act=Login&CODE=00"
    }
}
