package com.ehviewer.core.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import com.ehviewer.core.data.fixture.schema17Statements
import com.ehviewer.core.database.EhDatabase
import com.ehviewer.core.database.OwnedRoomDatabase
import com.ehviewer.core.database.Schema17to18
import com.ehviewer.core.domain.GalleryHistoryException
import com.ehviewer.core.model.BaseGalleryInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

internal data class OwnedDatabaseFile(val path: String, val cleanup: () -> Unit)
internal expect fun ownedDatabaseFile(): OwnedDatabaseFile
internal expect fun rawHistoryDriver(): SQLiteDriver
internal expect fun fileHistoryDatabase(path: String, migrations: List<Migration> = listOf(Schema17to18())): OwnedRoomDatabase<EhDatabase>

/** Real files and the original 17→18→23 migrations; never use destructive migration fallbacks. */
class HistoryMigrationTest {
    private fun createLegacy(path: String) = rawHistoryDriver().open(path).use { db ->
        schema17Statements.forEach(db::execSQL)
        db.execSQL("INSERT INTO GALLERIES(GID,TOKEN,TITLE,CATEGORY,RATING,FAVORITE_SLOT) VALUES(1,'0123456789','日本語 history',1,4.5,-2)")
        db.execSQL("INSERT INTO GALLERIES(GID,TOKEN,TITLE,CATEGORY,RATING,FAVORITE_SLOT) VALUES(2,NULL,'old invalid token',1,0,-2)")
        db.execSQL("INSERT INTO HISTORY VALUES(1,100)")
        db.execSQL("INSERT INTO PROGRESS VALUES(1,12)")
        db.execSQL("INSERT INTO DOWNLOAD_LABELS(LABEL,POSITION,_id) VALUES('Owned',0,1)")
        db.execSQL("INSERT INTO DOWNLOADS(GID,STATE,LEGACY,TIME,LABEL,POSITION) VALUES(1,0,0,100,'Owned',0)")
        db.execSQL("INSERT INTO LOCAL_FAVORITES VALUES(1,100)")
    }

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use { statement ->
        assertTrue(statement.step())
        statement.getLong(0)
    }
    private fun SQLiteConnection.text(sql: String): String = prepare(sql).use { statement ->
        assertTrue(statement.step())
        statement.getText(0)
    }

    @Test fun originalVersion17HistoryProgressDownloadsAndFavoritesMigrateAndSurviveReopening() = runTest {
        val owned = ownedDatabaseFile()
        try {
            createLegacy(owned.path)
            var db = fileHistoryDatabase(owned.path)
            try {
                val store = RoomGalleryHistoryStore(db.database)
                assertEquals("日本語 history", store.recent().single().title)
                assertEquals(12, db.database.progressDao().load(1)?.page)
                assertNull(db.database.galleryDao().load(2)) // Original 17→18 removes invalid nullable tokens.
                assertTrue(db.activeConnectionCount > 0)
            } finally {
                db.close()
                assertEquals(0, db.activeConnectionCount)
            }
            db = fileHistoryDatabase(owned.path)
            try {
                assertEquals("日本語 history", RoomGalleryHistoryStore(db.database).recent().single().title)
                assertEquals(12, db.database.progressDao().load(1)?.page)
            } finally {
                db.close()
            }
            rawHistoryDriver().open(owned.path).use { raw ->
                assertEquals(23, raw.long("PRAGMA user_version"))
                assertEquals(1, raw.long("SELECT COUNT(*) FROM DOWNLOADS"))
                assertEquals(1, raw.long("SELECT COUNT(*) FROM LOCAL_FAVORITES"))
                assertEquals("Owned", raw.text("SELECT LABEL FROM DOWNLOAD_LABELS"))
            }
        } finally {
            owned.cleanup()
        }
    }

    @Test fun aMigrationFailureRollsBackTheRealOldDatabaseInsteadOfReplacingIt() = runTest {
        val owned = ownedDatabaseFile()
        try {
            createLegacy(owned.path)
            val failing = object : Migration(17, 18) {
                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("UPDATE GALLERIES SET TITLE='must roll back'")
                    error("test-only migration interruption")
                }
            }
            val db = fileHistoryDatabase(owned.path, listOf(failing))
            try {
                assertFailsWith<GalleryHistoryException> { RoomGalleryHistoryStore(db.database).recent() }
            } finally {
                db.close()
                assertEquals(0, db.activeConnectionCount)
                db.close() // Closing an owner twice must not close a reused native handle.
            }
            rawHistoryDriver().open(owned.path).use { raw ->
                assertEquals(17, raw.long("PRAGMA user_version"))
                assertEquals("日本語 history", raw.text("SELECT TITLE FROM GALLERIES WHERE GID=1"))
                assertEquals(12, raw.long("SELECT PAGE FROM PROGRESS WHERE GID=1"))
            }
        } finally {
            owned.cleanup()
        }
    }

    @Test fun aNewerDatabaseVersionIsPreservedAndNeverSilentlyDowngraded() = runTest {
        val owned = ownedDatabaseFile()
        try {
            val original = fileHistoryDatabase(owned.path)
            try {
                RoomGalleryHistoryStore(original.database).record(BaseGalleryInfo(gid = 1, token = "0123456789", title = "Future history"))
            } finally {
                original.close()
            }
            rawHistoryDriver().open(owned.path).use { it.execSQL("PRAGMA user_version=24") }
            val db = fileHistoryDatabase(owned.path)
            try {
                assertFailsWith<GalleryHistoryException> { RoomGalleryHistoryStore(db.database).recent() }
            } finally {
                db.close()
                assertEquals(0, db.activeConnectionCount)
            }
            rawHistoryDriver().open(owned.path).use { raw ->
                assertEquals(24, raw.long("PRAGMA user_version"))
                assertEquals("Future history", raw.text("SELECT TITLE FROM GALLERIES WHERE GID=1"))
            }
        } finally {
            owned.cleanup()
        }
    }
}
