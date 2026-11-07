package com.ehviewer.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

actual fun platformNetworkEngine(): HttpClientEngine = Darwin.create {
    // SessionVault/HttpCookies owns identity; NSURLSession must not persist a second cookie jar.
    configureSession { HTTPShouldSetCookies = false }
}
