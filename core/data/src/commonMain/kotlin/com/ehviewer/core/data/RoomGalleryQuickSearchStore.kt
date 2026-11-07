package com.ehviewer.core.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.ehviewer.core.database.EhDatabase
import com.ehviewer.core.database.model.QuickSearch
import com.ehviewer.core.domain.GalleryQuickSearch
import com.ehviewer.core.domain.GalleryQuickSearchError
import com.ehviewer.core.domain.GalleryQuickSearchException
import com.ehviewer.core.domain.GalleryQuickSearchStore
import kotlinx.coroutines.CancellationException

/** Stores shared shortcuts in the original EhViewer database without a schema migration. */
class RoomGalleryQuickSearchStore(private val database: EhDatabase) : GalleryQuickSearchStore {
    private val dao get() = database.quickSearchDao()

    override suspend fun list(): List<GalleryQuickSearch> = stored { dao.list().map { it.asShortcut() } }

    override suspend fun save(search: GalleryQuickSearch) {
        require(search.name.isNotBlank() && search.name.length <= 512 && search.id == null)
        stored {
            database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    val existing = dao.list()
                    if (existing.any { it.name == search.name }) throw GalleryQuickSearchException(GalleryQuickSearchError.NAME_IN_USE)
                    val next = search.next
                    if (next != null && existing.asSequence().map { it.asShortcut() }.any { it.next == next && it.copy(name = search.name, id = null) == search }) {
                        throw GalleryQuickSearchException(GalleryQuickSearchError.SAME_POSITION)
                    }
                    val position = (existing.maxOfOrNull { it.position } ?: -1) + 1
                    dao.insert(QuickSearch(name = search.name, mode = search.mode, category = search.category, keyword = search.keyword, advanceSearch = search.advanceSearch, minRating = search.minRating, pageFrom = search.pageFrom, pageTo = search.pageTo, position = position))
                }
            }
        }
    }

    override suspend fun remove(id: Long) {
        stored {
            database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    val current = dao.list().firstOrNull { it.id == id } ?: return@immediateTransaction
                    dao.delete(current)
                    dao.fill(current.position)
                }
            }
        }
    }

    override suspend fun move(id: Long, before: Long?) {
        if (id == before) return
        stored {
            database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    val rows = dao.list()
                    val current = rows.firstOrNull { it.id == id } ?: return@immediateTransaction
                    val remaining = rows.filter { it.id != id }.toMutableList()
                    val position = if (before == null) remaining.size else remaining.indexOfFirst { it.id == before }.takeIf { it >= 0 } ?: return@immediateTransaction
                    remaining.add(position, current)
                    dao.update(remaining.mapIndexed { index, row -> row.copy(position = index) })
                }
            }
        }
    }

    private suspend fun <T> stored(action: suspend () -> T): T = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: GalleryQuickSearchException) {
        throw error
    } catch (_: Exception) {
        throw GalleryQuickSearchException(GalleryQuickSearchError.UNAVAILABLE)
    }

    private fun QuickSearch.asShortcut() = GalleryQuickSearch(name, mode, keyword, category, advanceSearch, minRating, pageFrom, pageTo, id)
}
