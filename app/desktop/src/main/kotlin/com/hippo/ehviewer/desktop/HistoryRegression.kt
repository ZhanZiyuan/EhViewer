package com.hippo.ehviewer.desktop

import androidx.room.Room
import com.ehviewer.core.data.RoomGalleryHistoryStore
import com.ehviewer.core.database.DesktopSQLiteDriver
import com.ehviewer.core.database.EhDatabase
import com.ehviewer.shared.verifyHistoryProduct
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

/** Exercise the packaged driver/Room implementation in a new owned file, independently of user data. */
fun verifyDesktopHistory(): Int = runBlocking {
    val directory = Files.createTempDirectory("ehviewer-history-runtime-")
    val database = Room.databaseBuilder<EhDatabase>(directory.resolve("history.db").toString()).setDriver(DesktopSQLiteDriver()).build()
    try {
        verifyHistoryProduct(RoomGalleryHistoryStore(database))
    } finally {
        database.close()
        Files.list(directory).use { files -> files.forEach(Files::delete) }
        Files.delete(directory)
    }
}
