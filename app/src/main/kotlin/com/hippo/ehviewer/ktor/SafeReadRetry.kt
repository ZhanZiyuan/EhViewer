package com.hippo.ehviewer.ktor

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import io.ktor.util.AttributeKey
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

private val safeReadRetry = AttributeKey<Unit>("SafeReadTransportRetry")

internal class RetryableConnectionFailure(cause: IOException) : IOException("Connection closed before the response", cause)

internal fun HttpRequestBuilder.allowReadTransportRetry() {
    attributes.put(safeReadRetry, Unit)
}

internal fun HttpClientConfig<*>.installSafeReadRetry() = install(HttpRequestRetry) {
    noRetry()
    retryOnExceptionIf(maxRetries = 1) { request, failure ->
        request.method == HttpMethod.Get && request.attributes.contains(safeReadRetry) &&
            (failure is ConnectException || failure is UnknownHostException || failure is RetryableConnectionFailure)
    }
    constantDelay(millis = 200, randomizationMs = 100, respectRetryAfterHeader = false)
}
