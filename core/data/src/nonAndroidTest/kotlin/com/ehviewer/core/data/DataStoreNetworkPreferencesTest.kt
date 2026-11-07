package com.ehviewer.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ehviewer.core.domain.GalleryNetworkPreferencesException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

class DataStoreNetworkPreferencesTest {
    private class OwnedStore(path: Path) {
        private val job = SupervisorJob()
        val data = PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(job + Dispatchers.Default)) { path }
        val network = DataStoreNetworkPreferences(data)
        suspend fun close() = job.cancelAndJoin()
    }

    private val files = FileSystem.SYSTEM
    private val schema = intPreferencesKey("schema")
    private val timeout = intPreferencesKey("request-timeout-seconds")

    @Test fun actualFileReopenRetainsSelectedTimeoutAndResetRestoresPlatformDefault() = runTest {
        val owned = ownedDatabaseFile()
        val path = (owned.path + ".preferences_pb").toPath()
        try {
            val first = OwnedStore(path)
            try {
                assertNull(first.network.requestTimeoutSeconds())
                first.network.setRequestTimeoutSeconds(120)
            } finally {
                first.close()
            }
            val second = OwnedStore(path)
            try {
                assertEquals(120, second.network.requestTimeoutSeconds())
                second.network.setRequestTimeoutSeconds(null)
            } finally {
                second.close()
            }
            val third = OwnedStore(path)
            try {
                assertNull(third.network.requestTimeoutSeconds())
            } finally {
                third.close()
            }
        } finally {
            owned.cleanup()
        }
    }

    @Test fun allChoicesAndResetPreserveUnrelatedSettingsAndInvalidInputsPreserveBytes() = runTest {
        val owned = ownedDatabaseFile()
        val path = (owned.path + ".preferences_pb").toPath()
        val store = OwnedStore(path)
        try {
            val unrelated = stringPreferencesKey("owned-unrelated")
            store.data.edit { it[unrelated] = "保留 📚" }
            for (seconds in listOf(10, 30, 60, 120, null)) {
                store.network.setRequestTimeoutSeconds(seconds)
                assertEquals(seconds, store.network.requestTimeoutSeconds())
            }
            val before = files.read(path) { readByteArray() }
            for (invalid in listOf(-1, 0, 1, 11, 121, Int.MAX_VALUE)) {
                assertFailsWith<IllegalArgumentException> { store.network.setRequestTimeoutSeconds(invalid) }
                assertContentEquals(before, files.read(path) { readByteArray() })
            }
            assertEquals("保留 📚", store.data.data.first()[unrelated])
        } finally {
            store.close()
            owned.cleanup()
        }
    }

    @Test fun futureSchemasRejectReadsWritesAndResetWithoutReplacingAnyByte() = runTest {
        val owned = ownedDatabaseFile()
        val path = (owned.path + ".preferences_pb").toPath()
        val store = OwnedStore(path)
        try {
            store.data.edit {
                it[schema] = 2
                it[timeout] = 60
            }
            val before = files.read(path) { readByteArray() }
            assertFailsWith<GalleryNetworkPreferencesException> { store.network.requestTimeoutSeconds() }
            assertFailsWith<GalleryNetworkPreferencesException> { store.network.setRequestTimeoutSeconds(30) }
            assertFailsWith<GalleryNetworkPreferencesException> { store.network.setRequestTimeoutSeconds(null) }
            assertContentEquals(before, files.read(path) { readByteArray() })
        } finally {
            store.close()
            owned.cleanup()
        }
    }

    @Test fun invalidSavedTimeoutCannotBeSilentlyReplacedEvenByReset() = runTest {
        val owned = ownedDatabaseFile()
        val path = (owned.path + ".preferences_pb").toPath()
        val store = OwnedStore(path)
        try {
            store.data.edit { it[timeout] = 0 }
            val before = files.read(path) { readByteArray() }
            assertFailsWith<GalleryNetworkPreferencesException> { store.network.requestTimeoutSeconds() }
            assertFailsWith<GalleryNetworkPreferencesException> { store.network.setRequestTimeoutSeconds(10) }
            assertFailsWith<GalleryNetworkPreferencesException> { store.network.setRequestTimeoutSeconds(null) }
            assertContentEquals(before, files.read(path) { readByteArray() })
        } finally {
            store.close()
            owned.cleanup()
        }
    }

    @Test fun corruptFileRemainsByteIdenticalAfterRejectedReadsWritesAndReset() = runTest {
        val owned = ownedDatabaseFile()
        val path = (owned.path + ".preferences_pb").toPath()
        val original = byteArrayOf(0x0a, 0x7f, 0x01)
        files.write(path) { write(original) }
        val store = OwnedStore(path)
        try {
            assertFailsWith<GalleryNetworkPreferencesException> { store.network.requestTimeoutSeconds() }
            assertFailsWith<GalleryNetworkPreferencesException> { store.network.setRequestTimeoutSeconds(60) }
            assertFailsWith<GalleryNetworkPreferencesException> { store.network.setRequestTimeoutSeconds(null) }
            assertContentEquals(original, files.read(path) { readByteArray() })
        } finally {
            store.close()
            owned.cleanup()
        }
    }

    @Test fun cancelledPendingUpdatePreservesTheFileAndAllowsASubsequentRealWrite() = runTest {
        val owned = ownedDatabaseFile()
        val path = (owned.path + ".preferences_pb").toPath()
        val store = OwnedStore(path)
        try {
            store.network.setRequestTimeoutSeconds(30)
            val before = files.read(path) { readByteArray() }
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val gated = DataStoreNetworkPreferences(object : DataStore<Preferences> by store.data {
                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                    entered.complete(Unit)
                    proceed.await()
                    return store.data.updateData(transform)
                }
            })
            val update = async { gated.setRequestTimeoutSeconds(120) }
            entered.await()
            update.cancelAndJoin()
            assertFailsWith<CancellationException> { update.await() }
            assertContentEquals(before, files.read(path) { readByteArray() })
            assertEquals(30, store.network.requestTimeoutSeconds())
            proceed.complete(Unit)
            gated.setRequestTimeoutSeconds(60)
            assertEquals(60, store.network.requestTimeoutSeconds())
        } finally {
            store.close()
            owned.cleanup()
        }
    }
}
