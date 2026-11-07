package com.ehviewer.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.throwSQLiteException
import java.nio.file.Path

/** Keep the real AndroidX SQLite engine and transaction state on every desktop CPU. */
class DesktopSQLiteDriver : SQLiteDriver {
    private val driver = BundledSQLiteDriver()

    override fun open(fileName: String): SQLiteConnection = sqliteOperation {
        require(fileName == ":memory:" || Path.of(fileName).isAbsolute) { "Database path must be absolute" }
        DesktopSQLiteConnection(driver.open(fileName))
    }
}

/** Preserve native result codes for Room's busy handling without exposing SQL, values or paths. */
private inline fun <T> sqliteOperation(block: () -> T): T = try {
    block()
} catch (failure: SQLiteException) {
    val code = Regex("Error code: ([0-9]+)").find(failure.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 1
    throwSQLiteException(code, "SQLite operation failed")
}

private class DesktopSQLiteConnection(private val connection: SQLiteConnection) : SQLiteConnection {
    override fun inTransaction(): Boolean = sqliteOperation { connection.inTransaction() }
    override fun prepare(sql: String): SQLiteStatement = sqliteOperation { DesktopSQLiteStatement(connection.prepare(sql)) }
    override fun close() = sqliteOperation { connection.close() }
}

private class DesktopSQLiteStatement(private val statement: SQLiteStatement) : SQLiteStatement {
    override fun bindBlob(index: Int, value: ByteArray) = sqliteOperation { statement.bindBlob(index, value) }
    override fun bindDouble(index: Int, value: Double) = sqliteOperation { statement.bindDouble(index, value) }
    override fun bindLong(index: Int, value: Long) = sqliteOperation { statement.bindLong(index, value) }
    override fun bindText(index: Int, value: String) = sqliteOperation { statement.bindText(index, value) }
    override fun bindNull(index: Int) = sqliteOperation { statement.bindNull(index) }
    override fun getBlob(index: Int): ByteArray = sqliteOperation { statement.getBlob(index) }
    override fun getDouble(index: Int): Double = sqliteOperation { statement.getDouble(index) }
    override fun getLong(index: Int): Long = sqliteOperation { statement.getLong(index) }
    override fun getText(index: Int): String = sqliteOperation { statement.getText(index) }
    override fun isNull(index: Int): Boolean = sqliteOperation { statement.isNull(index) }
    override fun getColumnType(index: Int): Int = sqliteOperation { statement.getColumnType(index) }
    override fun getColumnCount(): Int = sqliteOperation { statement.getColumnCount() }
    override fun getColumnName(index: Int): String = sqliteOperation { statement.getColumnName(index) }
    override fun step(): Boolean = sqliteOperation { statement.step() }
    override fun reset() = sqliteOperation { statement.reset() }
    override fun clearBindings() = sqliteOperation { statement.clearBindings() }
    override fun close() = sqliteOperation { statement.close() }
}
