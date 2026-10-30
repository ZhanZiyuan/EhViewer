package com.ehviewer.core.database

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

actual inline fun <reified T : RoomDatabase> roomDb(name: String, builder: RoomDatabase.Builder<T>.() -> Unit): T = Room.databaseBuilder<T>(getDatabasePath(name).toString()).setDriver(BundledSQLiteDriver()).apply(builder).build()

actual fun getDatabasePath(name: String) = DataDirectory.file(name)
