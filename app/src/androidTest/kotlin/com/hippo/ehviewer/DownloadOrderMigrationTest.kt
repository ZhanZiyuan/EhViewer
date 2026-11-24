package com.hippo.ehviewer

import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ehviewer.core.database.EhDatabase
import com.ehviewer.core.database.model.CUSTOM_DOWNLOAD_POSITION_STEP
import com.hippo.ehviewer.download.SortMode
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadOrderMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val driver = AndroidSQLiteDriver()
    private val databaseName = "ordering-migration-${UUID.randomUUID()}.db"
    private val file = context.getDatabasePath(databaseName)

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = file,
        driver = driver,
        databaseClass = EhDatabase::class,
    )

    @After
    fun removeFixture() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun emptyDatabaseMigrates() {
        helper.createDatabase(23).close()
        helper.runMigrationsAndValidate(24, emptyList<Migration>()).use { connection ->
            connection.prepare("SELECT GID, CUSTOM_POSITION FROM DOWNLOADS").use { assertFalse(it.step()) }
        }
    }

    @Test
    fun largeMigrationPreservesFieldsAndResolvesTiesByGid() {
        val count = 10_000
        helper.createDatabase(23).use { connection -> populate(connection, count) }
        helper.runMigrationsAndValidate(24, emptyList<Migration>()).use { connection ->
            connection.prepare(
                "SELECT GID, STATE, LEGACY, TIME, LABEL, DIRNAME, CUSTOM_POSITION FROM DOWNLOADS " +
                    "LEFT JOIN DOWNLOAD_DIRNAME USING (GID) ORDER BY CUSTOM_POSITION",
            ).use { rows ->
                val expected = (1L..count.toLong()).sortedWith(compareByDescending<Long> { it % 7 }.thenByDescending { it })
                for ((index, gid) in expected.withIndex()) {
                    assertTrue(rows.step())
                    assertEquals(gid, rows.getLong(0))
                    assertEquals(3L, rows.getLong(1))
                    assertEquals(1L, rows.getLong(2))
                    assertEquals(gid % 7, rows.getLong(3))
                    assertEquals("fixture", rows.getText(4))
                    assertEquals("fixture folder", rows.getText(5))
                    assertEquals(index * CUSTOM_DOWNLOAD_POSITION_STEP, rows.getLong(6))
                }
                assertFalse(rows.step())
            }
        }
    }

    @Test
    fun failedBatchRollsBackAndPositionsPersistAcrossReopen() = runBlocking {
        helper.createDatabase(23).use { populate(it, 3) }
        helper.runMigrationsAndValidate(24, emptyList<Migration>()).close()
        withDatabase { db ->
            val original = db.downloadsDao().list().associate { it.gid to it.customPosition }
            val failure = runCatching { db.updateCustomDownloadPositions(linkedMapOf(1L to -900L, 99L to 10L)) }
            assertTrue(failure.isFailure)
            assertEquals(original, db.downloadsDao().list().associate { it.gid to it.customPosition })
            db.updateCustomDownloadPositions(mapOf(1L to -900L))
            val changed = db.downloadsDao().list().first { it.gid == 1L }.apply {
                state = 4
                label = null
            }
            db.downloadsDao().update(listOf(changed))
        }
        withDatabase { db ->
            val ordered = db.downloadsDao().joinList().sortedWith(SortMode.Custom.comparator())
            assertEquals(listOf(1L, 3L, 2L), ordered.map { it.gid })
            assertEquals(4, ordered.first().state)
            assertEquals(-900L, ordered.first().downloadInfo.customPosition)
        }
    }

    @Test
    fun recordsOneRowTransactionLatencyAtThreeLibrarySizes() = runBlocking {
        helper.createDatabase(23).use { populate(it, 10_000) }
        helper.runMigrationsAndValidate(24, emptyList<Migration>()).close()
        withDatabase { db ->
            for (size in listOf(10_000, 1_000, 100)) {
                val downloads = db.downloadsDao().list()
                db.downloadsDao().delete(downloads.filter { it.gid > size })
                val samples = List(30) { i ->
                    val started = System.nanoTime()
                    db.updateCustomDownloadPositions(mapOf(1L to -100L - i))
                    (System.nanoTime() - started) / 1_000.0
                }.sorted()
                Log.i("DownloadOrderPerf", "rows=$size writes=1 samples=30 p50_us=${samples[14]} p95_us=${samples[28]}")
                assertEquals(size, db.downloadsDao().list().size)
            }
        }
    }

    private suspend inline fun <T> withDatabase(block: (EhDatabase) -> T): T {
        val database = Room.databaseBuilder<EhDatabase>(context, file.absolutePath).setDriver(driver).build()
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun populate(connection: SQLiteConnection, count: Int) {
        connection.execSQL("BEGIN")
        connection.execSQL("INSERT INTO DOWNLOAD_LABELS (LABEL, POSITION) VALUES ('fixture', 0)")
        connection.prepare(
            "INSERT INTO GALLERIES (GID, TOKEN, CATEGORY, RATING, PAGES, FAVORITE_SLOT) VALUES (?, 'fixture', 0, 0, 0, -2)",
        ).use { gallery ->
            connection.prepare(
                "INSERT INTO DOWNLOADS (GID, STATE, LEGACY, TIME, LABEL) VALUES (?, 3, 1, ?, 'fixture')",
            ).use { download ->
                connection.prepare("INSERT INTO DOWNLOAD_DIRNAME (GID, DIRNAME) VALUES (?, 'fixture folder')").use { dirname ->
                    for (gid in 1L..count.toLong()) {
                        gallery.bindLong(1, gid)
                        gallery.step()
                        gallery.reset()
                        download.bindLong(1, gid)
                        download.bindLong(2, gid % 7)
                        download.step()
                        download.reset()
                        dirname.bindLong(1, gid)
                        dirname.step()
                        dirname.reset()
                    }
                }
            }
        }
        connection.execSQL("COMMIT")
    }
}
