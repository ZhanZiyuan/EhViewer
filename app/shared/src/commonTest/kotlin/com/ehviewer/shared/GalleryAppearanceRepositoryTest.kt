package com.ehviewer.shared

import com.ehviewer.core.network.session.SessionVault
import com.ehviewer.shared.settings.GalleryAppearanceException
import com.ehviewer.shared.settings.GalleryAppearanceRepository
import com.ehviewer.shared.settings.GalleryThemeMode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

private class AppearanceTestVault : SessionVault {
    var bytes: ByteArray? = null
    var writes = 0
    var failRead = false
    var failWrite = false
    var cancelRead = false
    var cancelAfterWrite = false
    override suspend fun read(): ByteArray? {
        if (cancelRead) throw CancellationException()
        if (failRead) error("test-only-private-appearance-path")
        return bytes?.copyOf()
    }
    override suspend fun write(bytes: ByteArray) {
        if (failWrite) error("test-only-private-appearance-path")
        this.bytes = bytes.copyOf()
        writes++
        if (cancelAfterWrite) throw CancellationException()
    }
    override suspend fun clear() = error("Appearance preferences must not erase their backing store")
}

class GalleryAppearanceRepositoryTest {
    @Test fun aNewProfileUsesSystemAppearanceWithoutCreatingARecord() = runTest {
        val vault = AppearanceTestVault()
        val repository = GalleryAppearanceRepository(vault)
        assertEquals(GalleryThemeMode.SYSTEM, repository.mode())
        repository.setMode(GalleryThemeMode.SYSTEM)
        assertNull(vault.bytes)
        assertEquals(0, vault.writes)
    }

    @Test fun allExplicitModesSurviveRepositoryRecreation() = runTest {
        val vault = AppearanceTestVault()
        for (mode in listOf(GalleryThemeMode.LIGHT, GalleryThemeMode.DARK, GalleryThemeMode.SYSTEM)) {
            GalleryAppearanceRepository(vault).setMode(mode)
            assertEquals(mode, GalleryAppearanceRepository(vault).mode())
        }
        assertEquals(3, vault.writes)
    }

    @Test fun failedAtomicWritesPreserveThePreviousPreferenceAndHideBackendDetails() = runTest {
        val vault = AppearanceTestVault()
        val repository = GalleryAppearanceRepository(vault)
        repository.setMode(GalleryThemeMode.LIGHT)
        val before = vault.bytes!!.copyOf()
        vault.failWrite = true
        val failure = assertFailsWith<GalleryAppearanceException> { repository.setMode(GalleryThemeMode.DARK) }
        assertContentEquals(before, vault.bytes)
        assertEquals(GalleryThemeMode.LIGHT, repository.mode())
        assertNull(failure.cause)
        assertFalse(failure.toString().contains("private-appearance"))
    }

    @Test fun malformedFutureEmptyOversizedAndInvalidUtf8RecordsAreNeverOverwritten() = runTest {
        val valid = """{"schema":1,"mode":"LIGHT"}"""
        for (bytes in listOf(
            ByteArray(0),
            ByteArray(4097),
            byteArrayOf(-1),
            "private-not-json".encodeToByteArray(),
            valid.replace("\"schema\":1", "\"schema\":2").encodeToByteArray(),
            valid.replace("LIGHT", "UNKNOWN").encodeToByteArray(),
            valid.replace("}", ",\"futureSetting\":true}").encodeToByteArray(),
        )) {
            val vault = AppearanceTestVault().apply { this.bytes = bytes.copyOf() }
            val repository = GalleryAppearanceRepository(vault)
            assertFailsWith<GalleryAppearanceException> { repository.mode() }
            assertFailsWith<GalleryAppearanceException> { repository.setMode(GalleryThemeMode.DARK) }
            assertContentEquals(bytes, vault.bytes)
            assertEquals(0, vault.writes)
        }
    }

    @Test fun cancellationPropagatesAndAnAlreadyCommittedModeIsReadAfresh() = runTest {
        val vault = AppearanceTestVault()
        val repository = GalleryAppearanceRepository(vault)
        vault.cancelRead = true
        assertFailsWith<CancellationException> { repository.mode() }
        vault.cancelRead = false
        vault.cancelAfterWrite = true
        assertFailsWith<CancellationException> { repository.setMode(GalleryThemeMode.DARK) }
        vault.cancelAfterWrite = false
        assertEquals(GalleryThemeMode.DARK, repository.mode())
        assertEquals(GalleryThemeMode.DARK, GalleryAppearanceRepository(vault).mode())
    }

    @Test fun unavailableStorageDoesNotBecomeADefaultOrAReplacementWrite() = runTest {
        val vault = AppearanceTestVault().apply { failRead = true }
        val repository = GalleryAppearanceRepository(vault)
        val failure = assertFailsWith<GalleryAppearanceException> { repository.mode() }
        assertFailsWith<GalleryAppearanceException> { repository.setMode(GalleryThemeMode.LIGHT) }
        assertNull(failure.cause)
        assertFalse(failure.toString().contains("private-appearance"))
        assertEquals(0, vault.writes)
    }
}
