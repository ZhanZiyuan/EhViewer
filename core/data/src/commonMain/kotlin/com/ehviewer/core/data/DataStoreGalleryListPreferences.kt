package com.ehviewer.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

    private fun read(preferences: Preferences): GalleryListDensity {
        check((preferences[schema] ?: 1) == 1)
        return preferences[density]?.let(GalleryListDensity::valueOf) ?: GalleryListDensity.COMPACT
    }

    private suspend fun <T> stored(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryListPreferencesException()
    }

    override suspend fun density(): GalleryListDensity = stored { read(store.data.first()) }

    override suspend fun setDensity(density: GalleryListDensity) {
        stored {
            store.edit { preferences ->
                if (read(preferences) != density) {
                    preferences[schema] = 1
                    preferences[this.density] = density.name
                }
            }
        }
    }
}
