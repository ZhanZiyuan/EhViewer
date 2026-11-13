package com.hippo.ehviewer

import androidx.core.content.FileProvider
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ehviewer.core.database.EhDatabase
import com.ehviewer.core.database.model.DownloadArtist
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.database.model.DownloadLabel
import com.ehviewer.core.database.model.GalleryEntity
import com.ehviewer.core.database.model.HistoryInfo
import com.ehviewer.core.database.model.LocalFavoriteInfo
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.toOkioPath
import com.hippo.ehviewer.jni.appendZipEntry
import com.hippo.ehviewer.jni.beginZipWriter
import com.hippo.ehviewer.jni.closeZipWriter
import com.hippo.ehviewer.spider.CbzCommit
import com.hippo.ehviewer.spider.copyCbz
import com.hippo.ehviewer.spider.copyVerifiedCbz
import com.hippo.ehviewer.spider.verifyCbz
import java.io.File
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun backupImportWritesParentAndArtistsTogether() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<EhDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
        try {
            db.downloadLabelDao().insert(DownloadLabel(label = "backup", position = 0))
            val gallery = GalleryEntity(23, "test", "Import", "", null, 0, null, null, 0f, null, 2, null, -2)
            val original = DownloadInfo(gallery, null, DownloadArtist.from(23, listOf("artist"))).apply { label = "backup" }
            repeat(2) { db.importDownloadWithArtists(original) }
            val saved = db.downloadsDao().joinList().single()
            assertEquals("backup", saved.label)
            assertEquals(original.artistInfoList, saved.artistInfoList)

            val other = GalleryEntity(24, "test", "Invalid import", "", null, 0, null, null, 0f, null, 2, null, -2)
            val invalid = DownloadInfo(other, null, DownloadArtist.from(999, listOf("missing parent")))
            assertTrue(runCatching { db.importDownloadWithArtists(invalid) }.isFailure)
            assertEquals(listOf(23L), db.downloadsDao().joinList().map { it.gid })
        } finally {
            db.close()
        }
    }

    @Test
    fun interruptedArchiveRecoversOnlyAfterTheVerifiedBytesArePresent() = runBlocking {
        val folder = File.createTempFile("cbz-recovery-", "", context.cacheDir).apply {
            check(delete() && mkdir())
        }
        try {
            val output = File(folder, "gallery.cbz")
            val marker = File(folder, "gallery.cbz.pending")
            val commit = CbzCommit(output.toOkioPath())
            val image = File(folder, "page.jpg").apply { writeText("page data") }
            val metadata = File(folder, "ComicInfo.xml").apply { writeText("<ComicInfo/>") }

            assertTrue(runCatching { commit.write(2) { writeArchive(it, image, metadata) } }.isFailure)
            assertFalse(output.exists())
            assertFalse(marker.exists())
            assertEquals("page data", image.readText())
            commit.write(1) { writeArchive(it, image, metadata) }
            val saved = output.readBytes()
            marker.writeText(output.inputStream().use { copyCbz(it) })
            output.writeText("interrupted copy")
            assertFalse(commit.recover(1))
            assertTrue(marker.exists())
            output.writeBytes(saved)
            assertTrue(commit.recover(1))
            assertFalse(marker.exists())
            assertTrue(runCatching { commit.write(1) { error("Existing archive must survive") } }.isFailure)
            assertEquals(saved.toList(), output.readBytes().toList())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun singleDocumentExportUsesOnlyTheGrantedUri() = runBlocking {
        val folder = File.createTempFile("cbz-export-", "", context.cacheDir).apply {
            check(delete() && mkdir())
        }
        try {
            val image = File(folder, "page.jpg").apply { writeText("page data") }
            val metadata = File(folder, "ComicInfo.xml").apply { writeText("<ComicInfo/>") }
            val local = File(folder, "source.cbz")
            val destination = File(folder, "destination.cbz").apply { writeText("previous bytes") }
            writeArchive(local, image, metadata)
            verifyCbz(local, 1, "ComicInfo.xml")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destination)
            copyVerifiedCbz(local, uri.toOkioPath(), create = false, fingerprint = local.inputStream().use { copyCbz(it) })
            verifyCbz(uri.toOkioPath(), 1, "ComicInfo.xml")
            assertEquals(local.readBytes().toList(), destination.readBytes().toList())
            assertFalse(folder.list().orEmpty().any { it.endsWith(".pending") || it.endsWith(".part") })
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun incrementalNativeCbzCanBeValidatedThroughAnAndroidDescriptor() = runBlocking {
        val image = File.createTempFile("cbz-page", ".jpg", context.cacheDir).apply { writeText("page data") }
        val comicInfo = File.createTempFile("cbz-metadata", ".xml", context.cacheDir).apply { writeText("<ComicInfo/>") }
        val part = File.createTempFile("cbz-output", ".part", context.cacheDir).toOkioPath()
        try {
            writeArchive(part.toFile(), image, comicInfo)
            verifyCbz(part, 1, "ComicInfo.xml")
        } finally {
            image.delete()
            comicInfo.delete()
            part.toFile().delete()
        }
    }

    private fun writeArchive(file: File, image: File, comicInfo: File) {
        file.toOkioPath().openFileDescriptor("rwt").use { output ->
            val writer = beginZipWriter(output.fd)
            try {
                image.toOkioPath().openFileDescriptor("r").use { appendZipEntry(writer, it.fd, "00000001.jpg") }
                comicInfo.toOkioPath().openFileDescriptor("r").use { appendZipEntry(writer, it.fd, "ComicInfo.xml") }
                closeZipWriter(writer, true)
            } finally {
                closeZipWriter(writer, false)
            }
        }
    }
}
