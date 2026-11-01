package com.ehviewer.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ehviewer.core.domain.AppLanguageException
import com.ehviewer.core.domain.AppLanguageStore
import com.ehviewer.core.domain.validateAppLanguageTag
import com.ehviewer.core.preferences.getDateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Nonsecret application preferences use the existing atomic DataStore, independently of OS vaults. */
class DataStoreLanguageStore(private val store: DataStore<Preferences>) : AppLanguageStore {
    constructor(name: String = "gallery-language") : this(getDateStore(name))

    private val schema = intPreferencesKey("schema")
    private val language = stringPreferencesKey("language")

    private fun read(preferences: Preferences): String? {
        check((preferences[schema] ?: 1) == 1)
        return preferences[language].also(::validateAppLanguageTag)
    }

    private suspend fun <T> stored(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw AppLanguageException()
    }

    override suspend fun tag(): String? = stored { read(store.data.first()) }

    override suspend fun setTag(tag: String?) {
        validateAppLanguageTag(tag)
        stored {
            store.edit { preferences ->
                // Check existing bytes before writing; corrupt/future records must remain recoverable.
                if (read(preferences) != tag) {
                    preferences[schema] = 1
                    if (tag == null) preferences.remove(language) else preferences[language] = tag
                }
            }
        }
    }
}
