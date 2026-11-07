package com.ehviewer.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ehviewer.core.domain.AppLanguageException
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

class DataStoreLanguageStoreTest {
    private class OwnedStore(path: Path) {
        private val job = SupervisorJob()
        val data = PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(job + Dispatchers.Default)) { path }
        val language = DataStoreLanguageStore(data)
        suspend fun close() = job.cancelAndJoin()
    }

    private val files = FileSystem.SYSTEM
    private val schema = intPreferencesKey("schema")
    private val tag = stringPreferencesKey("language")

    @Test fun defaultAndSelectedLanguageSurviveActualFileReopenAndExplicitSystemReset() = runTest {
        val owned = ownedDatabaseFile()
        val path = (owned.path + ".preferences_pb").toPath()
        try {
            val first = OwnedStore(path)
            try {
                assertNull(first.language.tag())
                first.language.setTag("ja")
                assertEquals("ja", first.language.tag())
            } finally {
                first.close()
            }
            val second = OwnedStore(path)
            try {
                assertEquals("ja", second.language.tag())
                second.language.setTag("zh-TW")
                assertEquals("zh-TW", second.language.tag())
                second.language.setTag(null)
            } finally {
                second.close()
            }
            val third = OwnedStore(path)
            try {
                assertNull(third.language.tag())
            } finally {
                third.close()
            }
        } finally {
            owned.cleanup()
        }
    }

    @Test fun validUnsupportedTagsAndUnrelatedPreferencesArePreserved() = runTest {
        val owned = ownedDatabaseFile()
        val store = OwnedStore((owned.path + ".preferences_pb").toPath())
        try {
            val unrelated = stringPreferencesKey("owned-unrelated-setting")
            store.data.edit { it[unrelated] = "保留 📚" }
            for (choice in listOf("fr-CA", "zh-Hant-HK", "en-US-u-hc-h12", "x-reader", "i-klingon")) {
                store.language.setTag(choice)
                assertEquals(choice, store.language.tag())
            }
            store.language.setTag(null)
            assertEquals("保留 📚", store.data.data.first()[unrelated])
        } finally {
            store.close()
            owned.cleanup()
        }
    }

    @Test fun futureSchemaCannotBeLoadedOrOverwrittenAndRetainsEveryOriginalByte() = runTest {
        val owned = ownedDatabaseFile()
        val path = (owned.path + ".preferences_pb").toPath()
        val store = OwnedStore(path)
        try {
            store.data.edit {
                it[schema] = 2
                it[tag] = "ja"
            }
            val before = files.read(path) { readByteArray() }
            assertFailsWith<AppLanguageException> { store.language.tag() }
            assertFailsWith<AppLanguageException> { store.language.setTag("en") }
            assertFailsWith<AppLanguageException> { store.language.setTag(null) }
            assertContentEquals(before, files.read(path) { readByteArray() })
        } finally {
            store.close()
            owned.cleanup()
        }
    }

    @Test fun malformedSavedValuesAndInvalidNewTagsNeverReplaceExistingBytes() = runTest {
        val owned = ownedDatabaseFile()
        val path = (owned.path + ".preferences_pb").toPath()
        val store = OwnedStore(path)
        try {
            store.language.setTag("ja")
            val valid = files.read(path) { readByteArray() }
            for (invalid in listOf("", "../en", "en\u0000US", "日本語", "a".repeat(65))) {
                assertFailsWith<IllegalArgumentException> { store.language.setTag(invalid) }
                assertContentEquals(valid, files.read(path) { readByteArray() })
            }
            store.data.edit { it[tag] = "../ja" }
            val malformed = files.read(path) { readByteArray() }
            assertFailsWith<AppLanguageException> { store.language.tag() }
            assertFailsWith<AppLanguageException> { store.language.setTag("en") }
            assertContentEquals(malformed, files.read(path) { readByteArray() })
        } finally {
            store.close()
            owned.cleanup()
        }
    }

    @Test fun aCorruptPreferenceFileRejectsReadsWritesAndSystemResetWithoutReplacingAnyByte() = runTest {
        val owned = ownedDatabaseFile()
        val path = (owned.path + ".preferences_pb").toPath()
        val original = byteArrayOf(0x0a, 0x7f, 0x01)
        files.write(path) { write(original) }
        val store = OwnedStore(path)
        try {
            assertFailsWith<AppLanguageException> { store.language.tag() }
            assertFailsWith<AppLanguageException> { store.language.setTag("ja") }
            assertFailsWith<AppLanguageException> { store.language.setTag(null) }
            assertContentEquals(original, files.read(path) { readByteArray() })
        } finally {
            store.close()
            owned.cleanup()
        }
    }

    @Test fun cancellingAnAdmittedUpdateRetainsTheFileAndAllowsTheNextRealWrite() = runTest {
        val owned = ownedDatabaseFile()
        val path = (owned.path + ".preferences_pb").toPath()
        val store = OwnedStore(path)
        try {
            store.language.setTag("en")
            val before = files.read(path) { readByteArray() }
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val gated = DataStoreLanguageStore(object : DataStore<Preferences> by store.data {
                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                    entered.complete(Unit)
                    proceed.await()
                    return store.data.updateData(transform)
                }
            })
            val update = async { gated.setTag("ja") }
            entered.await()
            update.cancelAndJoin()
            assertFailsWith<CancellationException> { update.await() }
            assertContentEquals(before, files.read(path) { readByteArray() })
            assertEquals("en", store.language.tag())
            proceed.complete(Unit)
            gated.setTag("zh-CN")
            assertEquals("zh-CN", store.language.tag())
        } finally {
            store.close()
            owned.cleanup()
        }
    }
}
