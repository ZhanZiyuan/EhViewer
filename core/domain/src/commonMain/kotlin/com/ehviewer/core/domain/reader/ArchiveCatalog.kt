package com.ehviewer.core.domain.reader

/** Archive metadata and password operations, independent of Android and JNI. */
interface ArchiveCatalog {
    val pageCount: Int
    val needsPassword: Boolean
    fun extension(index: Int): String
    fun providePassword(password: String): Boolean
}
