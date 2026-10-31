package com.ehviewer.core.domain.reader

import kotlinx.serialization.Serializable

@Serializable
enum class ReadingLayout { PAGED, CONTINUOUS }

@Serializable
enum class ReadingDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

@Serializable
data class ReaderPreferences(
    val layout: ReadingLayout = ReadingLayout.PAGED,
    val direction: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT,
)

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
