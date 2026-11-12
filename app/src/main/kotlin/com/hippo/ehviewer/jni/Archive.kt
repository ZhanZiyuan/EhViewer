package com.hippo.ehviewer.jni

import java.nio.ByteBuffer

external fun releaseByteBuffer(buffer: ByteBuffer)
external fun openArchive(fd: Int, size: Long, sortEntries: Boolean): Int
external fun extractToByteBuffer(index: Int): ByteBuffer?

/** Read-only output, except independently writable GIF data when the platform workaround needs it. */
internal external fun extractForReading(index: Int, rewriteGif: Boolean): ByteBuffer?
external fun extractToFd(index: Int, fd: Int): Boolean
external fun getExtension(index: Int): String
external fun needPassword(): Boolean
external fun providePassword(str: String): Boolean
external fun closeArchive()
external fun archiveFdBatch(fdBatch: IntArray, names: Array<String>, arcFd: Int, size: Int)

internal external fun openArchiveSession(fd: Int, size: Long, sortEntries: Boolean): Long
internal external fun closeArchiveSession(handle: Long)
internal external fun archiveSessionPageCount(handle: Long): Int
internal external fun archiveSessionNeedsPassword(handle: Long): Boolean
internal external fun archiveSessionProvidePassword(handle: Long, password: String): Boolean
internal external fun archiveSessionExtension(handle: Long, index: Int): String
internal external fun archiveSessionRead(handle: Long, index: Int, rewriteGif: Boolean): ByteBuffer?
internal external fun archiveSessionCopyTo(handle: Long, index: Int, fd: Int): Boolean
internal external fun beginZipWriter(fd: Int): Long
internal external fun appendZipEntry(handle: Long, fd: Int, name: String)
internal external fun closeZipWriter(handle: Long, commit: Boolean)
internal external fun trimArchiveCaches()
