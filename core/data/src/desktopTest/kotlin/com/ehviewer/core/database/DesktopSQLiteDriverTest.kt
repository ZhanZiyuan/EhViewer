package com.ehviewer.core.database

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSQLiteDriverTest {
    private val driver = DesktopSQLiteDriver()

    @Test fun actualNativeSQLitePreservesBoundTypesAliasesAndRepeatedRows() {
        driver.open(":memory:").use { db ->
            db.prepare("SELECT ? AS integer_value, ? AS real_value, ? AS text_value, ? AS blob_value, ? AS null_value UNION ALL SELECT 2, 3.0, 'second', x'00', NULL").use { statement ->
                assertEquals(5, statement.getColumnCount())
                assertEquals("integer_value", statement.getColumnName(0))
                statement.bindLong(1, Long.MAX_VALUE)
                statement.bindDouble(2, 1.25)
                statement.bindText(3, "日本語 📚\u0000end")
                statement.bindBlob(4, byteArrayOf(0, -1, 42))
                statement.bindNull(5)
                assertTrue(statement.step())
                assertEquals(Long.MAX_VALUE, statement.getLong(0))
                assertEquals(1.25, statement.getDouble(1))
                assertEquals("日本語 📚\u0000end", statement.getText(2))
                assertContentEquals(byteArrayOf(0, -1, 42), statement.getBlob(3))
                assertTrue(statement.isNull(4))
                assertEquals(listOf(SQLITE_DATA_INTEGER, SQLITE_DATA_FLOAT, SQLITE_DATA_TEXT, SQLITE_DATA_BLOB, SQLITE_DATA_NULL), (0..4).map(statement::getColumnType))
                assertTrue(statement.step())
                assertEquals(2, statement.getLong(0))
                assertFalse(statement.step())
                // SQLite requires reset after DONE; a further step may automatically restart it.
                statement.reset()
                assertTrue(statement.step())
                assertEquals(Long.MAX_VALUE, statement.getLong(0))
            }
        }
    }

    @Test fun resetRetainsBindingsAndClearBindingsMakesThemNull() {
        driver.open(":memory:").use { db ->
            db.prepare("SELECT ?").use { statement ->
                statement.bindText(1, "retained")
                assertTrue(statement.step())
                assertFailsWith<SQLiteException> { statement.bindText(1, "must reset") }
                statement.reset()
                assertTrue(statement.step())
                assertEquals("retained", statement.getText(0))
                statement.reset()
                statement.clearBindings()
                assertTrue(statement.step())
                assertTrue(statement.isNull(0))
            }
        }
    }

    @Test fun realFileTransactionsForeignKeysAndFtsSurviveReopening() {
        val directory = Files.createTempDirectory("ehviewer-sqlite-日本語 ")
        val file = directory.resolve("history.db")
        try {
            driver.open(file.toString()).use { db ->
                db.execSQL("PRAGMA foreign_keys=ON")
                db.execSQL("CREATE TABLE gallery(gid INTEGER PRIMARY KEY, title TEXT NOT NULL)")
                db.execSQL("CREATE TABLE progress(gid INTEGER REFERENCES gallery(gid), page INTEGER NOT NULL)")
                db.execSQL("CREATE VIRTUAL TABLE names USING fts4(title)")
                db.execSQL("INSERT INTO gallery VALUES(1, 'reader')")
                db.execSQL("INSERT INTO names VALUES('reader gallery')")
                db.execSQL("BEGIN IMMEDIATE")
                db.execSQL("INSERT INTO progress VALUES(1, 42)")
                db.execSQL("ROLLBACK")
                assertFailsWith<SQLiteException> { db.execSQL("INSERT INTO progress VALUES(2, 1)") }
                db.execSQL("INSERT INTO progress VALUES(1, 3)")
            }
            driver.open(file.toString()).use { db ->
                db.prepare("SELECT page FROM progress").use { statement ->
                    assertTrue(statement.step())
                    assertEquals(3, statement.getLong(0))
                    assertFalse(statement.step())
                }
                db.prepare("SELECT title FROM names WHERE names MATCH 'reader'").use { statement ->
                    assertTrue(statement.step())
                    assertEquals("reader gallery", statement.getText(0))
                }
            }
        } finally {
            Files.list(directory).use { files -> files.forEach(Files::delete) }
            Files.delete(directory)
        }
    }

    @Test fun insertReturningAndPragmaMetadataWorkBeforeStepping() {
        driver.open(":memory:").use { db ->
            db.execSQL("CREATE TABLE gallery(gid INTEGER PRIMARY KEY, title TEXT)")
            db.prepare("INSERT INTO gallery(title) VALUES(?) RETURNING gid").use { statement ->
                assertEquals(1, statement.getColumnCount())
                statement.bindText(1, "reader")
                assertTrue(statement.step())
                assertEquals(1, statement.getLong(0))
                assertFalse(statement.step())
            }
            db.prepare("PRAGMA table_info(gallery)").use { statement ->
                assertTrue(statement.getColumnCount() >= 6)
                assertTrue(statement.step())
                assertEquals("gid", statement.getText(1))
            }
        }
    }

    @Test fun failuresKeepSqliteCodesWithoutExposingSqlValuesOrFilePaths() {
        driver.open(":memory:").use { db ->
            db.execSQL("CREATE TABLE secret(id INTEGER PRIMARY KEY)")
            db.execSQL("INSERT INTO secret VALUES(1)")
            val error = assertFailsWith<SQLiteException> { db.execSQL("INSERT INTO secret VALUES(1)") }
            val code = Regex("Error code: ([0-9]+)").find(error.message.orEmpty())!!.groupValues[1].toInt()
            assertEquals(1555, code) // Preserve SQLITE_CONSTRAINT_PRIMARYKEY, not just base code 19.
            assertFalse(error.message.orEmpty().contains("secret"))
            assertEquals(null, error.cause)
            val statement = db.prepare("SELECT 1")
            assertFailsWith<SQLiteException> { statement.getLong(0) }
            statement.close()
            statement.close()
            assertFailsWith<SQLiteException> { statement.step() }
        }
    }

    @Test fun transactionStateComesFromTheEngineIncludingSavepointsAndAutomaticRollback() {
        driver.open(":memory:").use { db ->
            assertFalse(db.inTransaction())
            db.execSQL("BEGIN DEFERRED")
            assertTrue(db.inTransaction())
            db.execSQL("ROLLBACK")
            assertFalse(db.inTransaction())
            db.execSQL("SAVEPOINT owned")
            assertTrue(db.inTransaction())
            db.execSQL("RELEASE owned")
            assertFalse(db.inTransaction())
            db.execSQL("CREATE TABLE gallery(gid INTEGER)")
            db.execSQL("CREATE TRIGGER abort_transaction BEFORE INSERT ON gallery BEGIN SELECT RAISE(ROLLBACK, 'test-only-rollback'); END")
            db.execSQL("BEGIN IMMEDIATE")
            assertFailsWith<SQLiteException> { db.execSQL("INSERT INTO gallery VALUES(1)") }
            assertFalse(db.inTransaction())
        }
    }
}
