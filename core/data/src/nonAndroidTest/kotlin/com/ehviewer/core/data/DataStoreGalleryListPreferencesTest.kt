package com.ehviewer.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ehviewer.core.domain.GalleryListDensity
import com.ehviewer.core.domain.GalleryListPreferencesException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

class DataStoreGalleryListPreferencesTest {
    private class OwnedStore(path: Path) {
        private val job = SupervisorJob()
        val data = PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(job + Dispatchers.Default)) { path }
        val layout = DataStoreGalleryListPreferences(data)
        suspend fun close() = job.cancelAndJoin()
    }

    private val files = FileSystem.SYSTEM
    private val schema = intPreferencesKey("schema")
    private val density = stringPreferencesKey("density")

    @Test fun actualFileReopenRetainsChoiceAndRestoresThePreviousCompactDefault() = runTest {
        val owned = ownedDatabaseFile()
        val path = (owned.path + ".preferences_pb").toPath()
        try {
            val first = OwnedStore(path)
            try {
                assertEquals(GalleryListDensity.COMPACT, first.layout.density())
                assertEquals(true, first.layout.showComments())
                first.layout.setDensity(GalleryListDensity.COMFORTABLE)
                first.layout.setShowComments(false)
            } finally {
                first.close()
            }
            val second = OwnedStore(path)
            try {
                assertEquals(GalleryListDensity.COMFORTABLE, second.layout.density())
                assertEquals(false, second.layout.showComments())
                second.layout.setDensity(GalleryListDensity.COMPACT)
            } finally {
                second.close()
            }
            val third = OwnedStore(path)
            try {
                assertEquals(GalleryListDensity.COMPACT, third.layout.density())
                assertEquals(false, third.layout.showComments())
                third.layout.setShowComments(true)
                assertEquals(true, third.layout.showComments())
                assertEquals(GalleryListDensity.COMPACT, third.layout.density())
            } finally {
                third.close()
            }
        } finally {
            owned.cleanup()
        }
    }

    @Test fun invalidCommentPreferenceNeverOverwritesTheExistingRecord() = runTest {
        val owned = ownedDatabaseFile()
        val path = (owned.path + ".preferences_pb").toPath()
        val store = OwnedStore(path)
        try {
            store.data.edit { it[stringPreferencesKey("show_comments")] = "invalid-type" }
            val before = files.read(path) { readByteArray() }
            assertFailsWith<GalleryListPreferencesException> { store.layout.showComments() }
            assertFailsWith<GalleryListPreferencesException> { store.layout.setShowComments(false) }
            assertFailsWith<GalleryListPreferencesException> { store.layout.setDensity(GalleryListDensity.COMFORTABLE) }
            assertContentEquals(before, files.read(path) { readByteArray() })
        } finally {
            store.close()
            owned.cleanup()
        }
    }

    @Test fun bothChoicesPreserveUnrelatedValuesAndRepeatedSelectionPreservesBytes() = runTest {
        val owned = ownedDatabaseFile()
        val path = (owned.path + ".preferences_pb").toPath()
        val store = OwnedStore(path)
        try {
            val unrelated = stringPreferencesKey("owned-unrelated")
            store.data.edit { it[unrelated] = "保留 📚" }
            for (choice in GalleryListDensity.entries) {
                store.layout.setDensity(choice)
                assertEquals(choice, store.layout.density())
                val before = files.read(path) { readByteArray() }
                store.layout.setDensity(choice)
                assertContentEquals(before, files.read(path) { readByteArray() })
                assertEquals("保留 📚", store.data.data.first()[unrelated])
            }
        } finally {
            store.close()
            owned.cleanup()
        }
    }

    @Test fun futureAndInvalidSchemasRejectAllUpdatesWithoutReplacingTheFile() = runTest {
        for (version in listOf(-1, 0, 2)) {
            val owned = ownedDatabaseFile()
            val path = (owned.path + ".preferences_pb").toPath()
            val store = OwnedStore(path)
            try {
                store.data.edit {
                    it[schema] = version
                    it[density] = "COMFORTABLE"
                }
                val before = files.read(path) { readByteArray() }
                assertFailsWith<GalleryListPreferencesException> { store.layout.density() }
                for (choice in GalleryListDensity.entries) assertFailsWith<GalleryListPreferencesException> { store.layout.setDensity(choice) }
                assertContentEquals(before, files.read(path) { readByteArray() })
            } finally {
                store.close()
                owned.cleanup()
            }
        }
    }

    @Test fun unknownNamesAndIncorrectTypesArePreservedWithSanitizedErrors() = runTest {
        for (wrongType in listOf(false, true)) {
            val owned = ownedDatabaseFile()
            val path = (owned.path + ".preferences_pb").toPath()
            val store = OwnedStore(path)
            try {
                store.data.edit {
                    if (wrongType) it[intPreferencesKey("density")] = 1 else it[density] = "FUTURE_LAYOUT"
                }
                val before = files.read(path) { readByteArray() }
                val error = assertFailsWith<GalleryListPreferencesException> { store.layout.density() }
                assertEquals("Gallery layout could not be loaded or saved", error.message)
                assertEquals(null, error.cause)
                for (choice in GalleryListDensity.entries) assertFailsWith<GalleryListPreferencesException> { store.layout.setDensity(choice) }
                assertContentEquals(before, files.read(path) { readByteArray() })
            } finally {
                store.close()
                owned.cleanup()
            }
        }
    }

    @Test fun corruptFilesRemainByteIdenticalAfterReadsAndEveryWriteAttempt() = runTest {
        val owned = ownedDatabaseFile()
        val path = (owned.path + ".preferences_pb").toPath()
        val original = byteArrayOf(0x0a, 0x7f, 0x01)
        files.write(path) { write(original) }
        val store = OwnedStore(path)
        try {
            assertFailsWith<GalleryListPreferencesException> { store.layout.density() }
            for (choice in GalleryListDensity.entries) assertFailsWith<GalleryListPreferencesException> { store.layout.setDensity(choice) }
            assertContentEquals(original, files.read(path) { readByteArray() })
        } finally {
            store.close()
            owned.cleanup()
        }
    }

    @Test fun cancellationBeforeCommitPreservesBytesAndLaterWritesRecover() = runTest {
        val owned = ownedDatabaseFile()
        val path = (owned.path + ".preferences_pb").toPath()
        val store = OwnedStore(path)
        try {
            store.layout.setDensity(GalleryListDensity.COMFORTABLE)
            val before = files.read(path) { readByteArray() }
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val gated = DataStoreGalleryListPreferences(object : DataStore<Preferences> by store.data {
                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                    entered.complete(Unit)
                    proceed.await()
                    return store.data.updateData(transform)
                }
            })
            val update = async { gated.setDensity(GalleryListDensity.COMPACT) }
            entered.await()
            update.cancelAndJoin()
            assertFailsWith<CancellationException> { update.await() }
            assertContentEquals(before, files.read(path) { readByteArray() })
            proceed.complete(Unit)
            gated.setDensity(GalleryListDensity.COMPACT)
            assertEquals(GalleryListDensity.COMPACT, store.layout.density())
        } finally {
            store.close()
            owned.cleanup()
        }
    }
}
