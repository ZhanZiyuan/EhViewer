package com.ehviewer.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.ehviewer.core.domain.GalleryFavoritePreferences
import com.ehviewer.core.domain.GalleryFavoritePreferencesException
import com.ehviewer.core.domain.GalleryFavoriteTarget
import com.ehviewer.core.preferences.getDateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private val applicationFavoriteStore by lazy { getDateStore("gallery-favorites") }

class DataStoreGalleryFavoritePreferences(private val store: DataStore<Preferences>) : GalleryFavoritePreferences {
    constructor() : this(applicationFavoriteStore)

    private val slot = intPreferencesKey("default_favorite_slot")

    private fun read(preferences: Preferences) = preferences[slot]?.let(::GalleryFavoriteTarget) ?: GalleryFavoriteTarget.Ask

    private suspend fun <T> stored(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryFavoritePreferencesException()
    }

    override suspend fun target(): GalleryFavoriteTarget = stored { read(store.data.first()) }

    override suspend fun setTarget(target: GalleryFavoriteTarget) {
        stored {
            store.edit { preferences ->
                if (read(preferences) != target) preferences[slot] = target.slot
            }
        }
    }
}
