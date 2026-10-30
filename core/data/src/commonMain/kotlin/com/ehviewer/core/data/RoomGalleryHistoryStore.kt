package com.ehviewer.core.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.database.EhDatabase
import com.ehviewer.core.database.model.HistoryInfo
import com.ehviewer.core.domain.GalleryHistoryException
import com.ehviewer.core.domain.GalleryHistoryStore
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import kotlinx.coroutines.CancellationException

/** The original schema and DAOs back every platform; history removal retains reading progress. */
class RoomGalleryHistoryStore(private val database: EhDatabase) : GalleryHistoryStore {
    private suspend fun <T> stored(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryHistoryException()
    }

    override suspend fun recent(limit: Int): List<BaseGalleryInfo> {
        require(limit in 1..500)
        return stored { database.historyDao().recent(limit) }
    }

    override suspend fun record(gallery: GalleryInfo) = stored {
        require(gallery.gid > 0 && Regex("[a-f0-9]{10}").matches(gallery.token))
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                database.galleryDao().upsert(gallery.asEntity())
                database.historyDao().upsert(HistoryInfo(gallery.gid))
            }
        }
    }

    override suspend fun forget(gid: Long) {
        require(gid > 0)
        stored { database.historyDao().deleteByKey(gid) }
    }
}
