package com.ehviewer.shared.reader

import com.ehviewer.core.domain.reader.ReaderStateStore

/** Offline locations are indexes; private cache keys must never be persisted as online page tokens. */
internal class CachedReaderStateStore(private val store: ReaderStateStore) : ReaderStateStore by store {
    override suspend fun position(gid: Long) = store.position(gid)?.copy(pageToken = null)
    override suspend fun savePosition(gid: Long, index: Int, pageToken: String?) = store.savePosition(gid, index, null)
}
