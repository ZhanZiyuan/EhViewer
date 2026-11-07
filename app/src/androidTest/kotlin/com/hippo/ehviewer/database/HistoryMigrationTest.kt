package com.hippo.ehviewer.database

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ehviewer.core.data.RoomGalleryHistoryStore
import com.ehviewer.core.data.fixture.schema17Statements
import com.ehviewer.core.database.EhDatabase
import com.ehviewer.core.database.Schema17to18
import com.ehviewer.core.domain.GalleryHistoryException
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.preferences.DataStorePreferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The original Android driver and schema, in an owned cache directory; never the user's database. */
@RunWith(AndroidJUnit4::class)
class HistoryMigrationTest {
    private class LegacyValues(name: String) : DataStorePreferences(name) {
        val theme = intPref("theme", -1)
        val enabled = boolPref("enabled", false)
        val title = stringPref("title", "")
        val tags = stringSetPref("tags")
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val driver = AndroidSQLiteDriver()
    private fun open(path: String, migration: Migration = Schema17to18()): EhDatabase = Room.databaseBuilder<EhDatabase>(context, path).setDriver(driver).addMigrations(migration).build()
    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use { statement ->
        assertTrue(statement.step())
        statement.getLong(0)
    }
    private fun SQLiteConnection.text(sql: String): String = prepare(sql).use { statement ->
        assertTrue(statement.step())
        statement.getText(0)
    }

    private fun legacy(path: String) = driver.open(path).use { db ->
        schema17Statements.forEach(db::execSQL)
        db.execSQL("INSERT INTO GALLERIES(GID,TOKEN,TITLE,CATEGORY,RATING,FAVORITE_SLOT) VALUES(1,'0123456789','日本語 history',1,4.5,-2)")
        db.execSQL("INSERT INTO GALLERIES(GID,TOKEN,TITLE,CATEGORY,RATING,FAVORITE_SLOT) VALUES(2,NULL,'old invalid token',1,0,-2)")
        db.execSQL("INSERT INTO HISTORY VALUES(1,100)")
        db.execSQL("INSERT INTO PROGRESS VALUES(1,12)")
        db.execSQL("INSERT INTO DOWNLOAD_LABELS(LABEL,POSITION,_id) VALUES('Owned',0,1)")
        db.execSQL("INSERT INTO DOWNLOADS(GID,STATE,LEGACY,TIME,LABEL,POSITION) VALUES(1,0,0,100,'Owned',0)")
        db.execSQL("INSERT INTO LOCAL_FAVORITES VALUES(1,100)")
    }

    private fun owned(block: (String) -> Unit) {
        val directory = File(context.cacheDir, "history-migration-${UUID.randomUUID()}")
        check(directory.mkdirs())
        try {
            block(File(directory, "history.db").absolutePath)
        } finally {
            check(directory.deleteRecursively())
        }
    }

    @Test fun originalAndroidHistoryProgressDownloadsAndFavoritesMigrateFrom17AndReopen() = owned { path ->
        legacy(path)
        var db = open(path)
        try {
            runBlocking {
                assertEquals("日本語 history", RoomGalleryHistoryStore(db).recent().single().title)
                assertEquals(12, db.progressDao().load(1)?.page)
                assertNull(db.galleryDao().load(2))
            }
        } finally {
            db.close()
        }
        db = open(path)
        try {
            runBlocking { assertEquals("日本語 history", RoomGalleryHistoryStore(db).recent().single().title) }
        } finally {
            db.close()
        }
        driver.open(path).use { raw ->
            assertEquals(23L, raw.long("PRAGMA user_version"))
            assertEquals(1L, raw.long("SELECT COUNT(*) FROM DOWNLOADS"))
            assertEquals(1L, raw.long("SELECT COUNT(*) FROM LOCAL_FAVORITES"))
            assertEquals("Owned", raw.text("SELECT LABEL FROM DOWNLOAD_LABELS"))
            assertEquals(12L, raw.long("SELECT PAGE FROM PROGRESS WHERE GID=1"))
        }
    }

    @Test fun interruptedAndroidMigrationRollsBackInsteadOfReplacingTheOldFile() = owned { path ->
        legacy(path)
        val failure = object : Migration(17, 18) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("UPDATE GALLERIES SET TITLE='must roll back'")
                error("test-only migration interruption")
            }
        }
        val db = open(path, failure)
        try {
            assertThrows(GalleryHistoryException::class.java) { runBlocking { RoomGalleryHistoryStore(db).recent() } }
        } finally {
            db.close()
        }
        driver.open(path).use { raw ->
            assertEquals(17L, raw.long("PRAGMA user_version"))
            assertEquals("日本語 history", raw.text("SELECT TITLE FROM GALLERIES WHERE GID=1"))
            assertEquals(12L, raw.long("SELECT PAGE FROM PROGRESS WHERE GID=1"))
        }
    }

    @Test fun aNewerAndroidDatabaseVersionIsNeverDestructivelyDowngraded() = owned { path ->
        val original = open(path)
        try {
            runBlocking { RoomGalleryHistoryStore(original).record(BaseGalleryInfo(gid = 1, token = "0123456789", title = "Future history")) }
        } finally {
            original.close()
        }
        driver.open(path).use { it.execSQL("PRAGMA user_version=24") }
        val db = open(path)
        try {
            assertThrows(GalleryHistoryException::class.java) { runBlocking { RoomGalleryHistoryStore(db).recent() } }
        } finally {
            db.close()
        }
        driver.open(path).use { raw ->
            assertEquals(24L, raw.long("PRAGMA user_version"))
            assertEquals("Future history", raw.text("SELECT TITLE FROM GALLERIES WHERE GID=1"))
        }
    }

    @Test fun legacyPreferencesMigrateThroughTheOriginalAndroidFactoryWithoutLosingFields() = runBlocking {
        val name = "history-preferences-${UUID.randomUUID()}"
        val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        assertTrue(legacy.edit().putInt("theme", 2).putBoolean("enabled", true).putString("title", "日本語 📚").putStringSet("tags", setOf("one", "二")).commit())
        try {
            val values = LegacyValues(name)
            val migrated = values.snapshot()
            assertEquals(2, migrated[values.theme])
            assertEquals(true, migrated[values.enabled])
            assertEquals("日本語 📚", migrated[values.title])
            assertEquals(setOf("one", "二"), migrated[values.tags])
            assertTrue(context.preferencesDataStoreFile(name).isFile)
            assertTrue(legacy.all.isEmpty())
            values.withMutableSnapshot { it[values.enabled] = false }
            val updated = values.snapshot()
            assertEquals(false, updated[values.enabled])
            assertEquals(2, updated[values.theme])
            assertEquals(setOf("one", "二"), updated[values.tags])
        } finally {
            context.deleteSharedPreferences(name)
            val file = context.preferencesDataStoreFile(name)
            if (file.exists()) check(file.delete())
        }
    }
}
