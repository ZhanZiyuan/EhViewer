package com.ehviewer.core.database

import androidx.room.Room
import androidx.room.RoomDatabase

actual inline fun <reified T : RoomDatabase> roomDb(name: String, builder: RoomDatabase.Builder<T>.() -> Unit): T = Room.databaseBuilder<T>(getDatabasePath(name).toString()).setDriver(DesktopSQLiteDriver()).apply(builder).build()

inline fun <reified T : RoomDatabase> ownedRoomDb(name: String, crossinline builder: RoomDatabase.Builder<T>.() -> Unit): OwnedRoomDatabase<T> = ownedRoomDatabase(DesktopSQLiteDriver()) { driver ->
    Room.databaseBuilder<T>(getDatabasePath(name).toString()).setDriver(driver).apply(builder).build()
}

actual fun getDatabasePath(name: String) = DataDirectory.file(name)
