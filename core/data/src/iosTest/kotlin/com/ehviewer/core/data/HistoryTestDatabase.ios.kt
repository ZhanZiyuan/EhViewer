@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ehviewer.core.data

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ehviewer.core.database.EhDatabase
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

internal actual fun historyTestDatabase(): EhDatabase = Room.inMemoryDatabaseBuilder<EhDatabase>().setDriver(BundledSQLiteDriver()).build()

internal actual fun fileHistoryDatabase(path: String, migrations: List<Migration>): EhDatabase = Room.databaseBuilder<EhDatabase>(path).setDriver(BundledSQLiteDriver()).addMigrations(*migrations.toTypedArray()).build()
internal actual fun rawHistoryDriver(): SQLiteDriver = BundledSQLiteDriver()
internal actual fun ownedDatabaseFile(): OwnedDatabaseFile {
    val directory = (NSTemporaryDirectory() + "ehviewer-room-${NSUUID().UUIDString}").toPath()
    FileSystem.SYSTEM.createDirectories(directory)
    return OwnedDatabaseFile((directory / "history.db").toString()) {
        FileSystem.SYSTEM.list(directory).forEach { FileSystem.SYSTEM.delete(it) }
        FileSystem.SYSTEM.delete(directory)
    }
}
