package com.ehviewer.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.ehviewer.core.domain.GalleryNetworkPreferences
import com.ehviewer.core.domain.GalleryNetworkPreferencesException
import com.ehviewer.core.domain.validateGalleryRequestTimeout
import com.ehviewer.core.preferences.getDateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

// Android's underlying factory creates a new store: all client and UI adapters must share one.
private val applicationNetworkStore by lazy { getDateStore("gallery-network") }

/** Independent nonsecret preferences; never overwrite a corrupt or newer saved record. */
class DataStoreNetworkPreferences(private val store: DataStore<Preferences>) : GalleryNetworkPreferences {
    constructor() : this(applicationNetworkStore)

    private val schema = intPreferencesKey("schema")
    private val timeout = intPreferencesKey("request-timeout-seconds")

    private fun read(preferences: Preferences): Int? {
        check((preferences[schema] ?: 1) == 1)
        return preferences[timeout].also(::validateGalleryRequestTimeout)
    }

    private suspend fun <T> stored(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryNetworkPreferencesException()
    }

    override suspend fun requestTimeoutSeconds(): Int? = stored { read(store.data.first()) }

    override suspend fun setRequestTimeoutSeconds(seconds: Int?) {
        validateGalleryRequestTimeout(seconds)
        stored {
            store.edit { preferences ->
                if (read(preferences) != seconds) {
                    preferences[schema] = 1
                    if (seconds == null) preferences.remove(timeout) else preferences[timeout] = seconds
                }
            }
        }
    }
}
