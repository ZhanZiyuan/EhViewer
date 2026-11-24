package com.hippo.ehviewer.client

import com.ehviewer.core.network.IdentityCookies
import com.hippo.ehviewer.client.exception.ParseException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.ContentType
import io.ktor.http.Url
import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

internal enum class SiteAccess {
    MissingIdentity,
    Unverified,
    Accessible,
    TransientFailure,
    ChallengeRequired,
    AuthenticationRequired,
    AccessDenied,
    UnexpectedResponse,
}

internal fun identityAccess(identity: IdentityCookies) = when (identity) {
    IdentityCookies.Missing, IdentityCookies.Incomplete -> SiteAccess.MissingIdentity
    IdentityCookies.Present -> SiteAccess.Unverified
}

internal fun classifySiteHeaders(
    expectedHost: String,
    url: Url,
    status: Int,
    cloudflareChallenge: Boolean,
    contentType: ContentType?,
): SiteAccess = when {
    cloudflareChallenge -> SiteAccess.ChallengeRequired
    url.segments.firstOrNull() == "bounce_login.php" ||
        url.host == "forums.e-hentai.org" && url.parameters["act"].equals("Login", ignoreCase = true) ||
        status == 401 -> SiteAccess.AuthenticationRequired
    status == 403 -> SiteAccess.AccessDenied
    status in 500..599 -> SiteAccess.TransientFailure
    url.host != expectedHost || status != 200 -> SiteAccess.UnexpectedResponse
    expectedHost == EhUrl.DOMAIN_EX && contentType?.match(ContentType.Image.GIF) == true -> SiteAccess.AccessDenied
    else -> SiteAccess.Unverified
}

internal fun classifySiteFailure(failure: Exception) = when (failure) {
    is CancellationException -> throw failure
    is ParseException, is SSLException, is HtmlResponseTooLargeException -> SiteAccess.UnexpectedResponse
    is HttpRequestTimeoutException, is IOException -> SiteAccess.TransientFailure
    else -> SiteAccess.UnexpectedResponse
}
