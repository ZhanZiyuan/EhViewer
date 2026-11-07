package com.ehviewer.core.data

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import com.ehviewer.core.database.DesktopSQLiteDriver
import com.ehviewer.core.database.download.DownloadDatabase

internal actual fun fileDownloadDatabase(path: String): DownloadDatabase = Room.databaseBuilder<DownloadDatabase>(path).setDriver(DesktopSQLiteDriver()).build()

internal actual fun rawDownloadConnection(path: String): SQLiteConnection = DesktopSQLiteDriver().open(path)
