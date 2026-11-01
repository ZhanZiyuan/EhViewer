package verification

import org.junit.Assert.assertThrows
import org.junit.Test

class DataValidationTest {
    private val native = mapOf("status" to "PASS", "executed" to true, "tests" to 45, "suites" to mapOf("iosSimulatorArm64Test.com.ehviewer.core.data.RoomGalleryHistoryStoreTest" to 5, "iosSimulatorArm64Test.com.ehviewer.core.data.HistoryMigrationTest" to 3, "iosSimulatorArm64Test.com.ehviewer.core.data.PortablePreferencesTest" to 4, "iosSimulatorArm64Test.com.ehviewer.core.data.DownloadStorageTest" to 4, "iosSimulatorArm64Test.com.ehviewer.core.data.FileArchiveLibraryTest" to 8, "iosSimulatorArm64Test.com.ehviewer.core.data.PartialDownloadStorageTest" to 7, "iosSimulatorArm64Test.com.ehviewer.core.data.DirectoryLibraryTest" to 10, "iosSimulatorArm64Test.com.ehviewer.core.data.ArchiveLibraryRecoveryTest" to 4))

    @Test fun nativeAndJvmRequireActualRoomAndMigrationSuites() {
        verifyDataTests(native, native = true)
        val jvm = native + mapOf("tests" to 51, "suites" to (native["suites"].obj() + ("DesktopSQLiteDriverTest[desktop]" to 6)))
        verifyDataTests(jvm)
        assertThrows(IllegalArgumentException::class.java) { verifyDataTests(native) }
    }

    @Test fun aRawDriverSuccessCannotReplaceFailedSkippedOrMissingRoomMigrationExecution() {
        for (bad in listOf(native + ("executed" to false), native + ("status" to "SKIPPED"), native + ("suites" to native["suites"].obj().filterKeys { !it.endsWith("HistoryMigrationTest") }), native + ("suites" to (native["suites"].obj() + ("iosSimulatorArm64Test.com.ehviewer.core.data.HistoryMigrationTest" to 2))))) {
            assertThrows(IllegalArgumentException::class.java) { verifyDataTests(bad, native = true) }
        }
        assertThrows(IllegalArgumentException::class.java) { verifyDataTests(native + ("suites" to native["suites"].obj().filterKeys { !it.endsWith("DownloadStorageTest") }), native = true) }
        assertThrows(IllegalArgumentException::class.java) { verifyDataTests(native + ("suites" to native["suites"].obj().filterKeys { !it.endsWith("FileArchiveLibraryTest") }), native = true) }
        assertThrows(IllegalArgumentException::class.java) { verifyDataTests(native + ("suites" to native["suites"].obj().filterKeys { !it.endsWith("PartialDownloadStorageTest") }), native = true) }
        assertThrows(IllegalArgumentException::class.java) { verifyDataTests(native + ("suites" to native["suites"].obj().filterKeys { !it.endsWith("DirectoryLibraryTest") }), native = true) }
        assertThrows(IllegalArgumentException::class.java) { verifyDataTests(native + ("suites" to native["suites"].obj().filterKeys { !it.endsWith("ArchiveLibraryRecoveryTest") }), native = true) }
    }
}
