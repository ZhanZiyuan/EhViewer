package com.ehviewer.shared.reader

import com.ehviewer.core.domain.reader.ReaderPreferences
import com.ehviewer.core.domain.reader.ReaderStateException
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.core.domain.reader.ReadingPosition
import com.ehviewer.core.network.session.SessionVault
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ReaderSnapshot(val schema: Int, val preferences: ReaderPreferences, val positions: List<ReadingPosition>)

/** A separate authenticated snapshot uses the real OS-backed vault, never the cookie payload. */
class ReaderStateRepository(private val vault: SessionVault, private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) : ReaderStateStore {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }
    private var loaded: ReaderSnapshot? = null

    private suspend fun load(): ReaderSnapshot {
        loaded?.let { return it }
        return storage {
            val bytes = vault.read()
            val snapshot = if (bytes == null || bytes.isEmpty()) {
                ReaderSnapshot(1, ReaderPreferences(), emptyList())
            } else {
                check(bytes.size <= MAX_BYTES)
                json.decodeFromString<ReaderSnapshot>(bytes.decodeToString(throwOnInvalidSequence = true))
            }
            check(snapshot.schema == 1 && snapshot.positions.size <= MAX_POSITIONS)
            check(snapshot.positions.all { valid(it.gid, it.index, it.pageToken) && it.updatedAt >= 0 })
            check(snapshot.positions.map { it.gid }.toSet().size == snapshot.positions.size)
            loaded = snapshot
            snapshot
        }
    }

    private suspend fun persist(snapshot: ReaderSnapshot) = storage {
        val bytes = json.encodeToString(snapshot).encodeToByteArray()
        check(bytes.size <= MAX_BYTES)
        vault.write(bytes)
        loaded = snapshot
    }

    private suspend fun <T> storage(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        // A native atomic write may have committed just before cancellation; reload the disk next time.
        loaded = null
        throw cancelled
    } catch (_: Exception) {
        loaded = null
        throw ReaderStateException()
    }

    override suspend fun preferences(): ReaderPreferences = mutex.withLock { load().preferences }

    override suspend fun position(gid: Long): ReadingPosition? {
        require(gid > 0) { "Invalid gallery identity" }
        return mutex.withLock { load().positions.firstOrNull { it.gid == gid } }
    }

    override suspend fun setPreferences(preferences: ReaderPreferences) = mutex.withLock {
        val before = load()
        if (preferences != before.preferences) persist(before.copy(preferences = preferences))
    }

    override suspend fun savePosition(gid: Long, index: Int, pageToken: String?) {
        require(valid(gid, index, pageToken)) { "Invalid reading position" }
        mutex.withLock {
            val before = load()
            val time = now()
            check(time >= 0)
            val position = ReadingPosition(gid, index, pageToken, time)
            persist(before.copy(positions = (before.positions.filterNot { it.gid == gid } + position).takeLast(MAX_POSITIONS)))
        }
    }

    private fun valid(gid: Long, index: Int, token: String?) = gid > 0 && index in 0 until Int.MAX_VALUE && (token == null || Regex("[a-f0-9]{10}").matches(token))

    private companion object {
        const val MAX_BYTES = 256 * 1024
        const val MAX_POSITIONS = 1024
    }
}
