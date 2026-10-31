package com.ehviewer.core.data

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteDriver
import com.ehviewer.core.database.DesktopSQLiteDriver
import com.ehviewer.core.database.EhDatabase
import com.ehviewer.core.database.OwnedRoomDatabase
import com.ehviewer.core.database.ownedRoomDatabase
import java.nio.file.Files

internal actual fun historyTestDatabase(): EhDatabase = Room.inMemoryDatabaseBuilder<EhDatabase>().setDriver(DesktopSQLiteDriver()).build()

internal actual fun fileHistoryDatabase(path: String, migrations: List<Migration>): OwnedRoomDatabase<EhDatabase> = ownedRoomDatabase(DesktopSQLiteDriver()) { driver ->
    Room.databaseBuilder<EhDatabase>(path).setDriver(driver).addMigrations(*migrations.toTypedArray()).build()
}
internal actual fun rawHistoryDriver(): SQLiteDriver = DesktopSQLiteDriver()
internal actual fun ownedDatabaseFile(): OwnedDatabaseFile {
    val directory = Files.createTempDirectory("ehviewer-room-migration-日本語 ")
    return OwnedDatabaseFile(directory.resolve("history.db").toString()) {
        Files.list(directory).use { files -> files.forEach(Files::delete) }
        Files.delete(directory)
    }
}
