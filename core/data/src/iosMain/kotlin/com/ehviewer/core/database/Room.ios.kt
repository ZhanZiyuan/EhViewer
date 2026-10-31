package com.ehviewer.core.database

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

actual inline fun <reified T : RoomDatabase> roomDb(name: String, builder: RoomDatabase.Builder<T>.() -> Unit): T = Room.databaseBuilder<T>(getDatabasePath(name).toString()).setDriver(BundledSQLiteDriver()).apply(builder).build()

inline fun <reified T : RoomDatabase> ownedRoomDb(name: String, crossinline builder: RoomDatabase.Builder<T>.() -> Unit): OwnedRoomDatabase<T> = ownedRoomDatabase(BundledSQLiteDriver()) { driver ->
    Room.databaseBuilder<T>(getDatabasePath(name).toString()).setDriver(driver).apply(builder).build()
}

actual fun getDatabasePath(name: String) = DataDirectory.file(name)
