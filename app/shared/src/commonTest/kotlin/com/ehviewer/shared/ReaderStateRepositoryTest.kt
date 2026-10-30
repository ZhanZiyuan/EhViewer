package com.ehviewer.shared

import com.ehviewer.core.domain.reader.ReaderPreferences
import com.ehviewer.core.domain.reader.ReaderStateException
import com.ehviewer.core.domain.reader.ReadingDirection
import com.ehviewer.core.domain.reader.ReadingLayout
import com.ehviewer.core.network.session.SessionVault
import com.ehviewer.shared.reader.ReaderStateRepository
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/** Test-only storage faults; product factories must supply the actual platform secure vault. */
private class ReaderTestVault : SessionVault {
    var bytes: ByteArray? = null
    var failWrite = false
    var cancelAfterWrite = false
    var writes = 0
    override suspend fun read() = bytes?.copyOf()
    override suspend fun write(bytes: ByteArray) {
        if (failWrite) error("test-only-private-reader-path")
        this.bytes = bytes.copyOf()
        writes++
        if (cancelAfterWrite) throw CancellationException("test-only-cancelled-after-commit")
    }
    override suspend fun clear() {
        bytes = ByteArray(0)
    }
}

class ReaderStateRepositoryTest {
    @Test fun aNewStoreHasRealDefaultsWithoutWritingAnInventedHistory() = runTest {
        val vault = ReaderTestVault()
        val repository = ReaderStateRepository(vault)
        assertEquals(ReaderPreferences(), repository.preferences())
        assertNull(repository.position(1))
        assertNull(vault.bytes)
        assertEquals(0, vault.writes)
    }

    @Test fun preferencesAndResumePositionSurviveARealRepositoryRecreation() = runTest {
        val vault = ReaderTestVault()
        val first = ReaderStateRepository(vault) { 123 }
        val preference = ReaderPreferences(ReadingLayout.CONTINUOUS, ReadingDirection.RIGHT_TO_LEFT)
        first.setPreferences(preference)
        first.savePosition(5, 42, "0123456789")
        val restored = ReaderStateRepository(vault)
        assertEquals(preference, restored.preferences())
        val position = restored.position(5)!!
        assertEquals(42, position.index)
        assertEquals("0123456789", position.pageToken)
        assertEquals(123L, position.updatedAt)
    }

    @Test fun failedWritesDoNotInventAChangedPositionOrEraseThePreviousSnapshot() = runTest {
        val vault = ReaderTestVault()
        val repository = ReaderStateRepository(vault) { 100 }
        repository.savePosition(1, 2, "0123456789")
        val before = vault.bytes!!.copyOf()
        vault.failWrite = true
        val error = assertFailsWith<ReaderStateException> { repository.savePosition(1, 3, "1111111111") }
        assertEquals(2, repository.position(1)?.index)
        assertContentEquals(before, vault.bytes)
        assertFalse(error.toString().contains("private-reader"))
        assertNull(error.cause)
    }

    @Test fun malformedFutureDuplicateAndOversizedSnapshotsArePreservedAndFailExplicitly() = runTest {
        val valid = """{"schema":1,"preferences":{"layout":"PAGED","direction":"LEFT_TO_RIGHT"},"positions":[]}"""
        val duplicate = """{"gid":1,"index":0,"pageToken":null,"updatedAt":0}"""
        for (bytes in listOf("private-not-json".encodeToByteArray(), valid.replace("\"schema\":1", "\"schema\":2").encodeToByteArray(), valid.replace("\"positions\":[]", "\"positions\":[$duplicate,$duplicate]").encodeToByteArray(), ByteArray(256 * 1024 + 1), byteArrayOf(-1))) {
            val vault = ReaderTestVault().apply { this.bytes = bytes.copyOf() }
            val error = assertFailsWith<ReaderStateException> { ReaderStateRepository(vault).preferences() }
            assertContentEquals(bytes, vault.bytes)
            assertEquals(0, vault.writes)
            assertNull(error.cause)
            assertFalse(error.toString().contains("private-not-json"))
        }
    }

    @Test fun cancellationAfterAnAtomicCommitPropagatesAndInvalidatesTheStaleCache() = runTest {
        val vault = ReaderTestVault()
        val repository = ReaderStateRepository(vault) { 100 }
        repository.savePosition(1, 0, "0123456789")
        vault.cancelAfterWrite = true
        assertFailsWith<CancellationException> { repository.savePosition(1, 4, "1111111111") }
        vault.cancelAfterWrite = false
        assertEquals(4, repository.position(1)?.index)
        assertEquals(4, ReaderStateRepository(vault).position(1)?.index)
    }

    @Test fun concurrentSettingsAndProgressWritesPreserveBothPartsOfTheSnapshot() = runTest {
        val vault = ReaderTestVault()
        val repository = ReaderStateRepository(vault) { 100 }
        val preference = ReaderPreferences(direction = ReadingDirection.RIGHT_TO_LEFT)
        launch { repository.setPreferences(preference) }
        launch { repository.savePosition(1, 5, "0123456789") }
        launch { repository.savePosition(2, 6, null) }
        testScheduler.advanceUntilIdle()
        val restored = ReaderStateRepository(vault)
        assertEquals(preference, restored.preferences())
        assertEquals(5, restored.position(1)?.index)
        assertEquals(6, restored.position(2)?.index)
    }

    @Test fun boundedHistoryEvictsOnlyTheLeastRecentlyReadPosition() = runTest {
        val vault = ReaderTestVault()
        val repository = ReaderStateRepository(vault) { 100 }
        repeat(1024) { repository.savePosition(it + 1L, 0, null) }
        repository.savePosition(1, 7, "0123456789")
        repository.savePosition(1025, 0, null)
        val restored = ReaderStateRepository(vault)
        assertNull(restored.position(2))
        assertEquals(7, restored.position(1)?.index)
        assertEquals(0, restored.position(1025)?.index)
    }

    @Test fun invalidPositionsCannotReachTheStoreOrOverflowTheDisplayedPageNumber() = runTest {
        val vault = ReaderTestVault()
        val repository = ReaderStateRepository(vault)
        for ((gid, index, token) in listOf(Triple(0L, 0, null), Triple(1L, -1, null), Triple(1L, Int.MAX_VALUE, null), Triple(1L, 0, "../private"))) {
            assertFailsWith<IllegalArgumentException> { repository.savePosition(gid, index, token) }
        }
        assertNull(vault.bytes)
    }

    @Test fun anAuthenticatedClearedSnapshotRestoresDefaultsWithoutARewrite() = runTest {
        val vault = ReaderTestVault()
        ReaderStateRepository(vault).savePosition(1, 2, null)
        vault.clear()
        val cleared = ReaderStateRepository(vault)
        assertNull(cleared.position(1))
        assertEquals(ReaderPreferences(), cleared.preferences())
        assertEquals(1, vault.writes)
    }
}
