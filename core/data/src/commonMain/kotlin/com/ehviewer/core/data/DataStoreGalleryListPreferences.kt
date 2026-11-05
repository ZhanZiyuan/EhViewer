package com.ehviewer.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ehviewer.core.domain.GalleryListDensity
import com.ehviewer.core.domain.GalleryListPreferences
import com.ehviewer.core.domain.GalleryListPreferencesException
import com.ehviewer.core.preferences.getDateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private val applicationGalleryListStore by lazy { getDateStore("gallery-list") }

/** Nonsecret atomic preferences; missing records keep the previous shared compact default. */
class DataStoreGalleryListPreferences(private val store: DataStore<Preferences>) : GalleryListPreferences {
    constructor() : this(applicationGalleryListStore)

    private val schema = intPreferencesKey("schema")
    private val density = stringPreferencesKey("density")
    private val comments = booleanPreferencesKey("show_comments")
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
}
