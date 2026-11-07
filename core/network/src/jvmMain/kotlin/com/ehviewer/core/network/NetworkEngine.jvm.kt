package com.ehviewer.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

actual fun platformNetworkEngine(): HttpClientEngine = OkHttp.create()
