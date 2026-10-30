package com.ehviewer.shared.settings

import com.ehviewer.core.network.session.SessionVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class GalleryThemeMode { SYSTEM, LIGHT, DARK }

class GalleryAppearanceException : Exception("Appearance settings could not be loaded or saved")

interface GalleryAppearanceStore {
    suspend fun mode(): GalleryThemeMode
    suspend fun setMode(mode: GalleryThemeMode)
}

@Serializable
private data class AppearanceSnapshot(val schema: Int, val mode: GalleryThemeMode)

/** A separate real OS-backed vault; invalid or newer records are preserved without replacement. */
class GalleryAppearanceRepository(private val vault: SessionVault) : GalleryAppearanceStore {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }

    private suspend fun <T> stored(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryAppearanceException()
    }

    private suspend fun read(): GalleryThemeMode {
        val bytes = vault.read() ?: return GalleryThemeMode.SYSTEM
        check(bytes.size in 1..4096)
        val record = json.decodeFromString<AppearanceSnapshot>(bytes.decodeToString(throwOnInvalidSequence = true))
        check(record.schema == 1)
        return record.mode
    }

    // Read afresh so cancellation after an atomic write cannot leave a stale preference cache.
    override suspend fun mode(): GalleryThemeMode = mutex.withLock { stored { read() } }

    override suspend fun setMode(mode: GalleryThemeMode) = mutex.withLock {
        stored {
            if (read() != mode) vault.write(json.encodeToString(AppearanceSnapshot(1, mode)).encodeToByteArray())
        }
    }
}
