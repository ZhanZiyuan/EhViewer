@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hippo.ehviewer.desktop

import com.ehviewer.core.network.GallerySession
import com.ehviewer.core.network.gallery.GalleryListDecoder
import com.ehviewer.core.network.gallery.GallerySearchResult
import com.ehviewer.core.network.session.SessionStorageException
import com.ehviewer.core.network.session.SessionVault
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray

/** Each call owns its DLL reference and confined arena on the calling thread, including coroutine IO. */
private class NativeCall(private val symbols: CapiSymbols, val arena: Arena) {
    fun function(name: String, descriptor: FunctionDescriptor) = Linker.nativeLinker().downcallHandle(symbols.find(name), descriptor)
    fun bytes(value: ByteArray): MemorySegment = if (value.isEmpty()) MemorySegment.NULL else arena.allocate(value.size.toLong()).also { it.copyFrom(MemorySegment.ofArray(value)) }

    fun copyOwned(handle: Long, maximum: Long): ByteArray {
        check(handle != 0L)
        val copy = function("ehv_buffer_copy", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS))
        val free = function("ehv_buffer_free", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))
        try {
            val count = arena.allocate(JAVA_LONG)
            check(copy.invokeWithArguments(handle, MemorySegment.NULL, 0L, count) == 3)
            val size = count.get(JAVA_LONG, 0)
            check(size in 1..maximum)
            val output = arena.allocate(size)
            check(copy.invokeWithArguments(handle, output, size, count) == 0 && count.get(JAVA_LONG, 0) == size)
            return output.toArray(JAVA_BYTE)
        } finally {
            check(free.invokeWithArguments(handle) == 0)
        }
    }
}

private fun <T> nativeCall(library: Path, block: (NativeCall) -> T): T = openCapi(library.toRealPath()).use { symbols ->
    Arena.ofConfined().use { arena -> block(NativeCall(symbols, arena)) }
}

private class WindowsSessionVault(private val library: Path, directory: Path) : SessionVault {
    private val directory = directory.toAbsolutePath().normalize().toString().toByteArray(Charsets.UTF_8)

    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        nativeCall(library) { call ->
            val read = call.function("ehv_session_read", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS))
            val handle = call.arena.allocate(JAVA_LONG)
            when (read.invokeWithArguments(call.bytes(directory), directory.size.toLong(), handle)) {
                9 -> null
                0 -> call.copyOwned(handle.get(JAVA_LONG, 0), 256 * 1024)
                else -> throw SessionStorageException()
            }
        }
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        nativeCall(library) { call ->
            val write = call.function("ehv_session_write", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG))
            if (write.invokeWithArguments(call.bytes(directory), directory.size.toLong(), call.bytes(bytes), bytes.size.toLong()) != 0) throw SessionStorageException()
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        nativeCall(library) { call ->
            val clear = call.function("ehv_session_clear", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG))
            if (clear.invokeWithArguments(call.bytes(directory), directory.size.toLong()) != 0) throw SessionStorageException()
        }
    }
}

private class WindowsGalleryDecoder(private val library: Path) : GalleryListDecoder {
    private val cbor = Cbor { ignoreUnknownKeys = true }
    override fun decode(html: ByteArray): GallerySearchResult = nativeCall(library) { call ->
        require(html.isNotEmpty() && html.size <= 8 * 1024 * 1024)
        val parse = call.function("ehv_parse_gallery_list", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS))
        val handle = call.arena.allocate(JAVA_LONG)
        check(parse.invokeWithArguments(call.bytes(html), html.size.toLong(), handle) == 0) { "Invalid gallery response" }
        cbor.decodeFromByteArray(call.copyOwned(handle.get(JAVA_LONG, 0), 8 * 1024 * 1024))
    }
}

internal fun windowsGallerySession(library: Path, directory: Path, userAgent: String): GallerySession {
    require(ADDRESS.byteSize() == 8L)
    return GallerySession(WindowsSessionVault(library, directory), WindowsGalleryDecoder(library), userAgent)
}
