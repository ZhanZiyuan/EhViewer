package com.ehviewer.core.database

import androidx.room.Room
import androidx.room.RoomDatabase

actual inline fun <reified T : RoomDatabase> roomDb(name: String, builder: RoomDatabase.Builder<T>.() -> Unit): T = Room.databaseBuilder<T>(getDatabasePath(name).toString()).setDriver(DesktopSQLiteDriver()).apply(builder).build()

actual fun getDatabasePath(name: String) = DataDirectory.file(name)
