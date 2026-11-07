package com.ehviewer.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.ehviewer.core.domain.download.GalleryDownloadStorageException
import com.ehviewer.core.domain.download.GalleryStoragePreferences
import com.ehviewer.core.domain.download.validateDownloadLimit
import com.ehviewer.core.preferences.getDateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private val applicationStorageStore by lazy { getDateStore("gallery-storage") }

/** Nonsecret limits have an independent schema and never replace corrupt or future records. */
class DataStoreStoragePreferences(private val store: DataStore<Preferences>) : GalleryStoragePreferences {
    constructor() : this(applicationStorageStore)

    private val schema = intPreferencesKey("schema")
    private val limit = intPreferencesKey("download-limit-mib")

    private fun read(preferences: Preferences): Int? {
        check((preferences[schema] ?: 1) == 1)
        return preferences[limit].also(::validateDownloadLimit)
    }

    private suspend fun <T> stored(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryDownloadStorageException()
    }

    override suspend fun downloadLimitMiB(): Int? = stored { read(store.data.first()) }

    override suspend fun setDownloadLimitMiB(limitMiB: Int?) {
        validateDownloadLimit(limitMiB)
        stored {
            store.edit { preferences ->
                if (read(preferences) != limitMiB) {
                    preferences[schema] = 1
                    if (limitMiB == null) preferences.remove(limit) else preferences[limit] = limitMiB
                }
            }
        }
    }
}
