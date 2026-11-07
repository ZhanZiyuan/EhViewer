package com.ehviewer.core.domain.reader

import kotlinx.serialization.Serializable

@Serializable
enum class ReadingLayout { PAGED, CONTINUOUS }

@Serializable
enum class ReadingDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

@Serializable
enum class ReaderBackground { BLACK, GRAY, WHITE, AUTOMATIC }

/** Original Android reader choices; default is resolved from the actual gallery's tags. */
@Serializable
enum class ReaderMode {
    DEFAULT,
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
    VERTICAL,
    WEBTOON,
    CONTINUOUS_VERTICAL,
    ;

    val continuous: Boolean get() = this == WEBTOON || this == CONTINUOUS_VERTICAL

    fun resolve(webtoon: Boolean): ReaderMode = if (this == DEFAULT) {
        if (webtoon) WEBTOON else RIGHT_TO_LEFT
    } else {
        this
    }
}

@Serializable
data class ReaderPreferences(
    val layout: ReadingLayout = ReadingLayout.PAGED,
    val direction: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT,
    val background: ReaderBackground = ReaderBackground.BLACK,
    val showPageNumber: Boolean = true,
    val showSeekBar: Boolean = true,
    val doubleTapToZoom: Boolean = true,
    // Missing in older snapshots. Retain their actual layout/direction until the
    // user explicitly chooses a mode rather than reinterpret existing preferences.
    val mode: ReaderMode? = null,
    val cropBorders: Boolean = false,
) {
    val selectedMode: ReaderMode
        get() = mode ?: when {
            layout == ReadingLayout.CONTINUOUS -> ReaderMode.WEBTOON
            direction == ReadingDirection.RIGHT_TO_LEFT -> ReaderMode.RIGHT_TO_LEFT
            else -> ReaderMode.LEFT_TO_RIGHT
        }

    fun resolvedMode(webtoon: Boolean = false): ReaderMode = selectedMode.resolve(webtoon)

    fun withMode(mode: ReaderMode, webtoon: Boolean = false): ReaderPreferences {
        val resolved = mode.resolve(webtoon)
        return copy(
            mode = mode,
            layout = if (resolved.continuous) ReadingLayout.CONTINUOUS else ReadingLayout.PAGED,
            direction = when (resolved) {
                ReaderMode.LEFT_TO_RIGHT, ReaderMode.VERTICAL -> ReadingDirection.LEFT_TO_RIGHT
                ReaderMode.RIGHT_TO_LEFT -> ReadingDirection.RIGHT_TO_LEFT
                // Preserve the older continuous direction preference even though the
                // original vertical reader's navigation strip is left-to-right.
                else -> direction
            },
        )
    }
}

@Serializable
data class ReadingPosition(val gid: Long, val index: Int, val pageToken: String?, val updatedAt: Long)

class ReaderStateException : Exception("Reading state could not be loaded or saved")

/** The Android adapter can retain its existing Room progress and preferences without copying data. */
interface ReaderStateStore {
    suspend fun preferences(): ReaderPreferences
    suspend fun position(gid: Long): ReadingPosition?
    suspend fun setPreferences(preferences: ReaderPreferences)
    suspend fun savePosition(gid: Long, index: Int, pageToken: String?)
}
