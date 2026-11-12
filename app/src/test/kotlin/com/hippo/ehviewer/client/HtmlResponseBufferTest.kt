package com.hippo.ehviewer.client

import com.hippo.ehviewer.client.parser.unmarshalParsingAs
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlResponseBufferTest {
    @Test
    fun exactCapacityIsAccepted() = runBlocking {
        val bytes = ByteArray(16) { it.toByte() }
        val buffer = ByteBuffer.allocateDirect(bytes.size)
        ByteReadChannel(bytes).readBoundedHtml(buffer)
        assertArrayEquals(bytes, ByteArray(bytes.size).also { buffer.get(it) })
    }

    @Test
    fun oversizedResponseIsRejected() = runBlocking {
        val failure = runCatching {
            ByteReadChannel(ByteArray(17)).readBoundedHtml(ByteBuffer.allocateDirect(16))
        }.exceptionOrNull()
        assertTrue(failure is IOException && failure.message?.contains("16 byte limit") == true)
    }

    @Test
    fun cancellationWhileWaitingForContent() = runBlocking {
        val channel = ByteChannel()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            channel.readBoundedHtml(ByteBuffer.allocateDirect(16))
            error("A read on an empty open channel must suspend")
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test(expected = SerializationException::class)
    fun rejectsNativeOutputLargerThanDirectBuffer() {
        unmarshalParsingAs<String>(ByteBuffer.allocateDirect(16)) { _, _ -> 17 }
    }
}
