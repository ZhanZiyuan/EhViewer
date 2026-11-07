package com.ehviewer.core.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.database.EhDatabase
import com.ehviewer.core.database.model.LocalFavoriteInfo
import com.ehviewer.core.domain.GalleryLocalFavorites
import com.ehviewer.core.domain.GalleryLocalFavoritesException
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import kotlinx.coroutines.CancellationException

/** Reuses the original LOCAL_FAVORITES table and the actual shared gallery rows. */
class RoomGalleryLocalFavorites(private val database: EhDatabase) : GalleryLocalFavorites {
    private suspend fun <T> stored(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryLocalFavoritesException()
    }

    override suspend fun contains(gid: Long): Boolean {
        require(gid > 0)
        return stored { database.localFavoritesDao().contains(gid) }
    }

    override suspend fun page(offset: Int, limit: Int): List<BaseGalleryInfo> {
        require(offset >= 0 && limit in 1..100)
        return stored { database.localFavoritesDao().page(offset, limit) }
    }

    override suspend fun toggle(gallery: GalleryInfo): Boolean {
        require(gallery.gid > 0 && Regex("[a-f0-9]{10}").matches(gallery.token))
        return stored {
            database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    val local = database.localFavoritesDao()
                    if (local.contains(gallery.gid)) {
                        local.deleteByKey(gallery.gid)
                        // History, reading progress and downloads can still reference GALLERIES.
                        false
                    } else {
                        val existing = database.galleryDao().load(gallery.gid)
                        if (existing == null) {
                            database.galleryDao().upsert(gallery.asEntity())
                        } else {
                            check(existing.token == gallery.token)
                        }
                        local.upsert(LocalFavoriteInfo(gallery.gid))
                        true
                    }
                }
            }
        }
    }
}
