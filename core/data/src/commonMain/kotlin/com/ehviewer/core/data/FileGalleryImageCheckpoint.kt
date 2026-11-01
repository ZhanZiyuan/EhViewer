@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ehviewer.core.data

import com.ehviewer.core.domain.download.GalleryImageIdentity
import com.ehviewer.core.domain.download.GalleryImageProgress
import com.ehviewer.core.domain.download.GalleryImageProgress.Companion.CHUNK_BYTES
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/** Called only inside the owning storage's serialized IO admission. */
internal class FileGalleryImageCheckpoint(private val files: FileSystem, directory: Path, index: Int) {
    private val data = directory / "partial-$index.data"
    private val metadata = directory / "partial-$index.cbor"
    private val temporary = directory / "partial-$index.cbor.part"
    private val cbor = Cbor { encodeDefaults = true }

    private fun regular(path: Path, maximum: Long): Long? = files.metadataOrNull(path)?.let {
        check(it.isRegularFile && it.symlinkTarget == null)
        checkNotNull(it.size).also { size -> check(size in 0..maximum) }
    }

    fun open(): GalleryImageProgress {
        val length = regular(metadata, 16384)
        val state = if (length == null) {
            GalleryImageProgress()
        } else {
            check(length > 0)
            cbor.decodeFromByteArray<GalleryImageProgress>(files.read(metadata) { readByteArray(length) }).also { it.validate() }
        }
        val dataBytes = regular(data, GalleryImageIdentity.MAX_BYTES)
        check((dataBytes ?: 0) >= state.bytes)
        if (state.bytes > 0) {
            files.source(data).buffer().use { source ->
                var remaining = state.bytes
                state.chunks.forEach { hash ->
                    val count = minOf(remaining, CHUNK_BYTES.toLong())
                    check(source.readByteArray(count).toByteString().sha256().hex() == hash)
                    remaining -= count
                }
                check(remaining == 0L)
            }
        }
        // Verify the published prefix before removing a tail left by an interrupted append.
        if (dataBytes != null && dataBytes != state.bytes) {
            files.openReadWrite(data).use {
                it.resize(state.bytes)
                it.flush()
            }
        }
        return state
    }

    private fun publish(state: GalleryImageProgress) {
        state.validate()
        val encoded = cbor.encodeToByteArray(state)
        check(encoded.size <= 16384)
        regular(temporary, 16384)
        regular(metadata, 16384)
        files.openReadWrite(temporary).use { handle ->
            handle.resize(0)
            handle.sink().buffer().use { it.write(encoded) }
            handle.flush()
        }
        files.atomicMove(temporary, metadata)
    }

    fun reset(identity: GalleryImageIdentity?): GalleryImageProgress {
        identity?.validate()
        regular(data, GalleryImageIdentity.MAX_BYTES)
        // Invalidate the old identity durably before touching its payload.
        publish(GalleryImageProgress())
        files.openReadWrite(data).use {
            it.resize(0)
            it.flush()
        }
        if (identity != null) publish(GalleryImageProgress(identity))
        return GalleryImageProgress(identity)
    }

    fun append(state: GalleryImageProgress, bytes: ByteArray): GalleryImageProgress {
        val identity = checkNotNull(state.identity)
        check(!state.complete && bytes.size in 1..CHUNK_BYTES && state.bytes + bytes.size <= identity.totalBytes)
        check(regular(data, GalleryImageIdentity.MAX_BYTES) == state.bytes)
        val hashes = state.chunks.toMutableList()
        val tail = (state.bytes % CHUNK_BYTES).toInt()
        val joined = ByteArray(tail + bytes.size)
        if (tail > 0) {
            files.openReadOnly(data).use { handle ->
                handle.source(state.bytes - tail).buffer().use { source -> source.readByteArray(tail.toLong()).copyInto(joined) }
            }
            hashes.removeAt(hashes.lastIndex)
        }
        bytes.copyInto(joined, tail)
        var offset = 0
        while (offset < joined.size) {
            val end = minOf(joined.size, offset + CHUNK_BYTES)
            hashes += joined.toByteString(offset, end - offset).sha256().hex()
            offset = end
        }
        files.openReadWrite(data).use { handle ->
            handle.write(state.bytes, bytes, 0, bytes.size)
            handle.flush()
        }
        return state.copy(bytes = state.bytes + bytes.size, chunks = hashes).also(::publish)
    }

    fun finish(state: GalleryImageProgress): GalleryImageProgress {
        check(state.bytes == checkNotNull(state.identity).totalBytes)
        return state.copy(complete = true).also(::publish)
    }

    fun readComplete(state: GalleryImageProgress): ByteArray {
        check(state.complete)
        return files.read(data) { readByteArray(state.bytes) }
    }

    fun discard() {
        // If interrupted, an unreferenced data file is safely truncated on its next admission.
        regular(metadata, 16384)
        files.delete(metadata, mustExist = false)
        regular(data, GalleryImageIdentity.MAX_BYTES)
        files.delete(data, mustExist = false)
        regular(temporary, 16384)
        files.delete(temporary, mustExist = false)
    }
}
