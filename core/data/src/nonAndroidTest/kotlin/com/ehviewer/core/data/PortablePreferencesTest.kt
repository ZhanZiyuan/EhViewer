package com.ehviewer.core.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.ehviewer.core.preferences.DataStorePreferences
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

class PortablePreferencesTest {
    private class Values(store: DataStore<Preferences>) : DataStorePreferences(store) {
        val enabled = boolPref("enabled", false)
        val count = intPref("count", 0)
        val large = longPref("large", 0L)
        val text = stringPref("text", "")
        val tags = stringSetPref("tags")
        val bytes = byteArrayPref("bytes", byteArrayOf())
    }

    private class OwnedStore(path: Path) {
        private val job = SupervisorJob()
        val data = PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(job + Dispatchers.Default)) { path }
        val values = Values(data)
        suspend fun close() = job.cancelAndJoin()
    }

    @Test fun actualPreferenceFilesPreserveTypesAndUnicodeAfterClosingAndReopening() = runTest {
        val owned = ownedDatabaseFile()
        try {
            val path = (owned.path + ".preferences_pb").toPath()
            val first = OwnedStore(path)
            try {
                with(first.values) {
                    assertEquals(false, snapshot()[enabled])
                    withMutableSnapshot {
                        it[enabled] = true
                        it[count] = 42
                        it[large] = Long.MAX_VALUE
                        it[text] = "日本語 📚"
                        it[tags] = setOf("one", "二")
                        it[bytes] = byteArrayOf(0, -1, 42)
                    }
                }
            } finally {
                first.close()
            }
            val second = OwnedStore(path)
            try {
                with(second.values) {
                    val saved = snapshot()
                    assertEquals(true, saved[enabled])
                    assertEquals(42, saved[count])
                    assertEquals(Long.MAX_VALUE, saved[large])
                    assertEquals("日本語 📚", saved[text])
                    assertEquals(setOf("one", "二"), saved[tags])
                    assertContentEquals(byteArrayOf(0, -1, 42), saved[bytes])
                }
            } finally {
                second.close()
            }
        } finally {
            owned.cleanup()
        }
    }

    @Test fun concurrentFacadeWritesKeepIndependentFieldsInTheSameRealFile() = runTest {
        val owned = ownedDatabaseFile()
        try {
            val store = OwnedStore((owned.path + ".preferences_pb").toPath())
            try {
                val first = store.values
                val second = Values(store.data)
                listOf(async { first.withMutableSnapshot { it[first.enabled] = true } }, async { second.withMutableSnapshot { it[second.count] = 7 } }).awaitAll()
                assertEquals(true, first.snapshot()[first.enabled])
                assertEquals(7, second.snapshot()[second.count])
            } finally {
                store.close()
            }
        } finally {
            owned.cleanup()
        }
    }

    @Test fun cancelledUpdatePreservesThePreviousFileAndAllowsANextRealWrite() = runTest {
        val owned = ownedDatabaseFile()
        try {
            val store = OwnedStore((owned.path + ".preferences_pb").toPath())
            try {
                val values = store.values
                values.withMutableSnapshot { it[values.count] = 1 }
                assertFailsWith<CancellationException> {
                    store.data.updateData { old ->
                        old.toMutablePreferences()[values.count.key] = 2
                        throw CancellationException("Owned cancelled preference update")
                    }
                }
                assertEquals(1, values.snapshot()[values.count])
                values.withMutableSnapshot { it[values.count] = 3 }
                assertEquals(3, values.snapshot()[values.count])
            } finally {
                store.close()
            }
        } finally {
            owned.cleanup()
        }
    }

    @Test fun corruptPreferencesAreReportedAndPreservedInsteadOfSilentlyReplacedWithDefaults() = runTest {
        val owned = ownedDatabaseFile()
        try {
            val path = (owned.path + ".preferences_pb").toPath()
            FileSystem.SYSTEM.write(path) { writeByte(0) } // Invalid protobuf field tag.
            val store = OwnedStore(path)
            try {
                assertFailsWith<CorruptionException> { store.values.snapshot() }
            } finally {
                store.close()
            }
            assertContentEquals(byteArrayOf(0), FileSystem.SYSTEM.read(path) { readByteArray() })
        } finally {
            owned.cleanup()
        }
    }
}
