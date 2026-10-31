package com.ehviewer.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path

/** Configure once from the actual desktop user directory or iOS sandbox before opening storage. */
object DataDirectory {
    private var directory: Path? = null

    fun configure(path: Path) {
        require(path.isAbsolute) { "Application data directory must be absolute" }
        check(directory == null || directory == path) { "Application data directory is already configured" }
        FileSystem.SYSTEM.createDirectories(path)
        directory = path
    }

    fun file(name: String): Path {
        require(Regex("[a-zA-Z0-9_.-]{1,128}").matches(name) && name !in setOf(".", "..")) { "Invalid data file name" }
        return checkNotNull(directory) { "Application data directory is not configured" } / name
    }
}

private val storesMutex = Mutex()
private val stores = mutableMapOf<Path, DataStore<Preferences>>()

/** One real DataStore per path; constructing it does not replace or recover corrupt persisted data. */
internal fun portableDataStore(name: String?): DataStore<Preferences> = runBlocking {
    val path = DataDirectory.file("${name ?: "moe.tarsin.ehviewer_preferences"}.preferences_pb")
    storesMutex.withLock { stores.getOrPut(path) { PreferenceDataStoreFactory.createWithPath { path } } }
}
