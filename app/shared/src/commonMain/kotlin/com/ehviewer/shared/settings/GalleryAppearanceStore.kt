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
    suspend fun preferences(): GalleryAppearancePreferences
    suspend fun mode(): GalleryThemeMode = preferences().mode
    suspend fun setMode(mode: GalleryThemeMode)
    suspend fun setBlackDarkTheme(enabled: Boolean)
    suspend fun setHarmonizeCategoryColor(enabled: Boolean)
}

@Serializable
data class GalleryAppearancePreferences(
    val mode: GalleryThemeMode = GalleryThemeMode.SYSTEM,
    val blackDarkTheme: Boolean = false,
    val harmonizeCategoryColor: Boolean = true,
)

@Serializable
private data class AppearanceSnapshot(
    val schema: Int,
    val mode: GalleryThemeMode,
    val blackDarkTheme: Boolean = false,
    val harmonizeCategoryColor: Boolean = true,
)

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

    private suspend fun read(): GalleryAppearancePreferences {
        val bytes = vault.read() ?: return GalleryAppearancePreferences()
        check(bytes.size in 1..4096)
        val record = json.decodeFromString<AppearanceSnapshot>(bytes.decodeToString(throwOnInvalidSequence = true))
        check(record.schema == 1)
        return GalleryAppearancePreferences(record.mode, record.blackDarkTheme, record.harmonizeCategoryColor)
    }

    // Read afresh so cancellation after an atomic write cannot leave a stale preference cache.
    override suspend fun preferences(): GalleryAppearancePreferences = mutex.withLock { stored { read() } }

    private suspend fun update(transform: (GalleryAppearancePreferences) -> GalleryAppearancePreferences) = mutex.withLock {
        stored {
            val old = read()
            val new = transform(old)
            if (new != old) {
                vault.write(json.encodeToString(AppearanceSnapshot(1, new.mode, new.blackDarkTheme, new.harmonizeCategoryColor)).encodeToByteArray())
            }
        }
    }

    override suspend fun setMode(mode: GalleryThemeMode) = update { it.copy(mode = mode) }
    override suspend fun setBlackDarkTheme(enabled: Boolean) = update { it.copy(blackDarkTheme = enabled) }
    override suspend fun setHarmonizeCategoryColor(enabled: Boolean) = update { it.copy(harmonizeCategoryColor = enabled) }
}
