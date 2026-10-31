package com.ehviewer.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ehviewer.core.database.download.DownloadDatabase

internal actual fun fileDownloadDatabase(path: String): DownloadDatabase = Room.databaseBuilder<DownloadDatabase>(path).setDriver(BundledSQLiteDriver()).build()
