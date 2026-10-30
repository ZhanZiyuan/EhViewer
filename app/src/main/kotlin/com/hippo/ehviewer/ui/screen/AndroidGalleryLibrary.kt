package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.domain.reader.ReaderPreferences
import com.ehviewer.core.domain.reader.ReaderStateException
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.core.domain.reader.ReadingDirection
import com.ehviewer.core.domain.reader.ReadingLayout
import com.ehviewer.core.domain.reader.ReadingPosition
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.GalleryLibrary
import com.ehviewer.core.network.gallery.GalleryContentRepository
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRepository
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.gallery.RustGalleryDecoder
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Borrow the original application-owned engine, cookies, UA, DNS and Cronet settings. */
internal class AndroidGalleryLibrary(
    restricted: Boolean,
    client: HttpClient = EhApplication.noRedirectKtorClient,
) : GalleryLibrary {
    override val galleries = GalleryRepository(client, RustGalleryDecoder, restricted)
    override val content = GalleryContentRepository(client, RustGalleryDecoder, restricted)
    private var current: GalleryDetail? = null
    val readerState: ReaderStateStore = AndroidReadingState { current }

    override suspend fun details(gid: Long, token: String): GalleryDetail {
        val result = content.details(gid, token)
        try {
            // This is the real validated response, never a placeholder FK parent.
            withContext(Dispatchers.IO) { EhDB.sharedHistory.record(result) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw GalleryRequestException(GalleryFailure.LOCAL_STORAGE)
        }
        current = result
        return result
    }
}

/** Existing Room rows and DataStore remain the single source of Android progress/preferences. */
internal class AndroidReadingState(private val current: () -> GalleryDetail?) : ReaderStateStore {
    private suspend fun <T> stored(block: suspend () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw ReaderStateException()
    }

    override suspend fun preferences(): ReaderPreferences = stored {
        val snapshot = Settings.snapshot()
        val mode = ReadingModeType.fromPreference(snapshot[Settings.readingMode])
        val webtoon = current()?.let { gallery ->
            gallery.simpleTags?.any { it.endsWith("webtoon") } == true || gallery.tagGroups.any { group -> group.tags.any { it.text == "webtoon" } }
        } == true
        val continuous = ReadingModeType.isWebtoon(mode) || mode == ReadingModeType.DEFAULT && webtoon
        ReaderPreferences(
            layout = if (continuous) ReadingLayout.CONTINUOUS else ReadingLayout.PAGED,
            direction = if (if (continuous) snapshot[Settings.continuousReadingRtl] else mode in setOf(ReadingModeType.DEFAULT, ReadingModeType.RIGHT_TO_LEFT)) ReadingDirection.RIGHT_TO_LEFT else ReadingDirection.LEFT_TO_RIGHT,
        )
    }

    override suspend fun position(gid: Long): ReadingPosition? = stored {
        require(gid > 0)
        EhDB.getReadingPosition(gid)?.let { record ->
            require(record.page >= 0)
            // The legacy schema has an index, not a page token or timestamp.
            // Common readingResumeUrl resolves the actual token from real previews.
            ReadingPosition(gid, record.page, null, 0)
        }
    }

    override suspend fun setPreferences(preferences: ReaderPreferences) = stored {
        Settings.withMutableSnapshot { snapshot ->
            val old = ReadingModeType.fromPreference(snapshot[Settings.readingMode])
            val mode = when {
                preferences.layout == ReadingLayout.CONTINUOUS -> old.takeIf { ReadingModeType.isWebtoon(it) } ?: ReadingModeType.WEBTOON
                preferences.direction == ReadingDirection.RIGHT_TO_LEFT -> ReadingModeType.RIGHT_TO_LEFT
                else -> ReadingModeType.LEFT_TO_RIGHT
            }
            snapshot[Settings.readingMode] = mode.prefValue
            snapshot[Settings.continuousReadingRtl] = preferences.direction == ReadingDirection.RIGHT_TO_LEFT
        }
    }

    override suspend fun savePosition(gid: Long, index: Int, pageToken: String?) = stored {
        require(gid > 0 && index >= 0 && (pageToken == null || Regex("[a-f0-9]{10}").matches(pageToken)))
        val gallery = EhDB.getGalleryInfo(gid) ?: throw ReaderStateException()
        require(index < gallery.pages)
        EhDB.putReadProgress(gid, index)
    }
}
