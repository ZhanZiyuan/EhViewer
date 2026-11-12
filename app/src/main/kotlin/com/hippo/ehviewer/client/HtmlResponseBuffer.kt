package com.hippo.ehviewer.client

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.rethrowCloseCauseIfNeeded
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

// The direct buffer also holds the CBOR output from the in-place native parser.
internal const val HTML_RESPONSE_LIMIT_BYTES = 512 * 1024

/** Reads a whole response, accepting an exact fit only after checking for EOF. */
@OptIn(InternalAPI::class)
internal suspend fun ByteReadChannel.readBoundedHtml(buffer: ByteBuffer) {
    buffer.clear()
    while (true) {
        coroutineContext.ensureActive()
        if (!awaitContent()) break
        if (!buffer.hasRemaining()) {
            throw IOException("HTML response exceeds the ${buffer.capacity()} byte limit")
        }
        val read = readAvailable(buffer)
        if (read < 0) break
        if (read == 0) throw IOException("HTML response read made no progress")
    }
    rethrowCloseCauseIfNeeded()
    coroutineContext.ensureActive()
    buffer.flip()
}
