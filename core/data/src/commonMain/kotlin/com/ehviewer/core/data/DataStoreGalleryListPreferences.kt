package com.ehviewer.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ehviewer.core.domain.GalleryLaunchPage
import com.ehviewer.core.domain.GalleryLaunchPreferences
import com.ehviewer.core.domain.GalleryLaunchPreferencesException
import com.ehviewer.core.domain.GalleryLaunchSettings
import com.ehviewer.core.domain.GalleryListDensity
import com.ehviewer.core.domain.GalleryListMode
import com.ehviewer.core.domain.GalleryListPreferences
import com.ehviewer.core.domain.GalleryListPreferencesException
import com.ehviewer.core.domain.GalleryListPresentation
import com.ehviewer.core.domain.GalleryListPresentationPreferences
import com.ehviewer.core.domain.GalleryToplistPeriod
import com.ehviewer.core.preferences.getDateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private val applicationGalleryListStore by lazy { getDateStore("gallery-list") }

/** Nonsecret atomic preferences; missing records keep the previous shared compact default. */
class DataStoreGalleryListPreferences(private val store: DataStore<Preferences>) : GalleryListPreferences, GalleryListPresentationPreferences, GalleryLaunchPreferences {
    constructor() : this(applicationGalleryListStore)

    private val schema = intPreferencesKey("schema")
    private val mode = stringPreferencesKey("list_mode")
    private val detailThumbnailSize = intPreferencesKey("list_tile_size")
    private val thumbnailColumns = intPreferencesKey("thumb_columns")
    private val density = stringPreferencesKey("density")
    private val comments = booleanPreferencesKey("show_comments")
    private val launchPage = intPreferencesKey("launch_page_2")
    private val toplistPeriod = stringPreferencesKey("recent_toplist")
    private data class Snapshot(val density: GalleryListDensity, val comments: Boolean)

    private fun read(preferences: Preferences): Snapshot {
        check((preferences[schema] ?: 1) == 1)
        return Snapshot(preferences[density]?.let(GalleryListDensity::valueOf) ?: GalleryListDensity.COMPACT, preferences[comments] ?: true)
    }

    private suspend fun <T> stored(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryListPreferencesException()
    }

    override suspend fun density(): GalleryListDensity = stored { read(store.data.first()).density }
    override suspend fun showComments(): Boolean = stored { read(store.data.first()).comments }

    override suspend fun setShowComments(show: Boolean) = stored {
        store.edit { preferences ->
            if (read(preferences).comments != show) {
                preferences[schema] = 1
                preferences[comments] = show
            }
        }
        Unit
    }

    override suspend fun setDensity(density: GalleryListDensity) {
        stored {
            store.edit { preferences ->
                if (read(preferences).density != density) {
                    preferences[schema] = 1
                    preferences[this.density] = density.name
                }
            }
        }
    }

    private fun readPresentation(preferences: Preferences): GalleryListPresentation {
        check((preferences[schema] ?: 1) == 1)
        return GalleryListPresentation(
            mode = preferences[mode]?.let(GalleryListMode::valueOf) ?: GalleryListMode.DETAIL,
            detailThumbnailSize = preferences[detailThumbnailSize] ?: 40,
            thumbnailColumns = preferences[thumbnailColumns] ?: 3,
        )
    }

    override suspend fun presentation(): GalleryListPresentation = stored { readPresentation(store.data.first()) }

    private suspend fun <T> setPresentation(key: Preferences.Key<T>, value: T, transform: (GalleryListPresentation) -> GalleryListPresentation) {
        stored {
            store.edit { preferences ->
                val current = readPresentation(preferences)
                if (transform(current) != current) {
                    preferences[schema] = 1
                    preferences[key] = value
                }
            }
        }
    }

    override suspend fun setMode(mode: GalleryListMode) = setPresentation(this.mode, mode.name) { it.copy(mode = mode) }
    override suspend fun setDetailThumbnailSize(size: Int) = setPresentation(detailThumbnailSize, size) { it.copy(detailThumbnailSize = size) }
    override suspend fun setThumbnailColumns(columns: Int) = setPresentation(thumbnailColumns, columns) { it.copy(thumbnailColumns = columns) }

    private suspend fun <T> storedLaunch(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryLaunchPreferencesException()
    }

    private fun readLaunch(preferences: Preferences): GalleryLaunchSettings {
        check((preferences[schema] ?: 1) == 1)
        return GalleryLaunchSettings(
            page = preferences[launchPage]?.let { index -> GalleryLaunchPage.entries.single { it.originalIndex == index } } ?: GalleryLaunchPage.HOMEPAGE,
            toplistPeriod = preferences[toplistPeriod]?.let { code -> GalleryToplistPeriod.entries.single { it.originalCode == code } } ?: GalleryToplistPeriod.ALL_TIME,
        )
    }

    override suspend fun launchSettings(): GalleryLaunchSettings = storedLaunch { readLaunch(store.data.first()) }

    private suspend fun <T> setLaunch(key: Preferences.Key<T>, value: T) = storedLaunch {
        store.edit { preferences ->
            readLaunch(preferences)
            if (preferences[key] != value) {
                preferences[schema] = 1
                preferences[key] = value
            }
        }
        Unit
    }

    override suspend fun setLaunchPage(page: GalleryLaunchPage) = setLaunch(launchPage, page.originalIndex)
    override suspend fun setToplistPeriod(period: GalleryToplistPeriod) = setLaunch(toplistPeriod, period.originalCode)
}
