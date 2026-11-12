package com.hippo.ehviewer

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ehviewer.core.database.EhDatabase
import com.ehviewer.core.database.model.GalleryEntity
import com.ehviewer.core.database.model.HistoryInfo
import com.ehviewer.core.database.model.LocalFavoriteInfo
import com.ehviewer.core.files.openFileDescriptor
import com.hippo.ehviewer.jni.appendZipEntry
import com.hippo.ehviewer.jni.beginZipWriter
import com.hippo.ehviewer.jni.closeZipWriter
import com.hippo.ehviewer.spider.verifyCbz
import java.io.File
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun roomFtsFollowsGalleryChangesForHistoryAndLocalFavorites() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<EhDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
        try {
            val gallery = GalleryEntity(17, "test", "Cloud needle", "", null, 0, null, null, 0f, null, 1, null, -2)
            db.galleryDao().upsert(gallery)
            db.historyDao().upsert(HistoryInfo(gallery.gid))
            db.localFavoritesDao().upsert(LocalFavoriteInfo(gallery.gid))

            val old = gallerySearchExpression("needle (")
            assertEquals(listOf(17L), gids(db.historyDao().joinListLazy(old)))
            assertEquals(listOf(17L), gids(db.localFavoritesDao().joinListLazy(old)))
            gallery.title = "Cloud changed"
            db.galleryDao().update(gallery)
            assertTrue(gids(db.historyDao().joinListLazy(old)).isEmpty())
            assertTrue(gids(db.localFavoritesDao().joinListLazy(old)).isEmpty())
            assertEquals(listOf(17L), gids(db.historyDao().joinListLazy(gallerySearchExpression("changed"))))
        } finally {
            db.close()
        }
    }

    private suspend fun gids(source: PagingSource<Int, GalleryEntity>): List<Long> {
        val result = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
        return (result as PagingSource.LoadResult.Page).data.map { it.gid }
    }

    @Test
    fun incrementalNativeCbzCanBeValidatedThroughAnAndroidDescriptor() = runBlocking {
        val image = File.createTempFile("cbz-page", ".jpg", context.cacheDir).apply { writeText("page data") }
        val comicInfo = File.createTempFile("cbz-metadata", ".xml", context.cacheDir).apply { writeText("<ComicInfo/>") }
        val part = File.createTempFile("cbz-output", ".part", context.cacheDir).toOkioPath()
        try {
            part.openFileDescriptor("rwt").use { output ->
                val writer = beginZipWriter(output.fd)
                try {
                    image.toOkioPath().openFileDescriptor("r").use { appendZipEntry(writer, it.fd, "00000001.jpg") }
                    comicInfo.toOkioPath().openFileDescriptor("r").use { appendZipEntry(writer, it.fd, "ComicInfo.xml") }
                    closeZipWriter(writer, true)
                } finally {
                    closeZipWriter(writer, false)
                }
            }
            verifyCbz(part, 1, "ComicInfo.xml")
        } finally {
            image.delete()
            comicInfo.delete()
            part.toFile().delete()
        }
    }
}
