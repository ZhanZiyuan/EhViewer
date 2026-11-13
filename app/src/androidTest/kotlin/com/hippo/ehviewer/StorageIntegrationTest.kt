package com.hippo.ehviewer

import android.provider.DocumentsContract
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
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.files.write
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.download.setDownloadLocation
import com.hippo.ehviewer.jni.appendZipEntry
import com.hippo.ehviewer.jni.beginZipWriter
import com.hippo.ehviewer.jni.closeZipWriter
import com.hippo.ehviewer.spider.CbzCommit
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.spider.copyCbz
import com.hippo.ehviewer.spider.copyVerifiedCbz
import com.hippo.ehviewer.spider.getComicInfo
import com.hippo.ehviewer.spider.getGalleryDownloadDir
import com.hippo.ehviewer.spider.isSameCbzDocument
import com.hippo.ehviewer.spider.verifyCbz
import com.hippo.ehviewer.spider.write
import com.hippo.ehviewer.spider.writeComicInfo
import com.hippo.ehviewer.ui.keepNoMediaFileStatus
import com.hippo.ehviewer.ui.settings.hasDownloadContents
import com.hippo.ehviewer.util.AppConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
            db.galleryDao().updatePageCount(gallery.gid, 10)
            val updated = checkNotNull(db.galleryDao().load(gallery.gid))
            assertEquals(10, updated.pages)
            assertEquals("Cloud changed", updated.title)
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
            marker.writeText("torn marker")
            assertFalse(commit.recover(1))
            assertTrue(marker.exists())
            assertEquals(saved.toList(), output.readBytes().toList())
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
    fun treeAndSingleDocumentUrisIdentifyTheSameArchive() {
        val authority = "com.android.externalstorage.documents"
        val root = DocumentsContract.buildTreeDocumentUri(authority, "primary:Download")
        val documentId = "primary:Download/archive.cbz"
        val fromTree = DocumentsContract.buildDocumentUriUsingTree(root, documentId).toOkioPath()
        val single = DocumentsContract.buildDocumentUri(authority, documentId).toOkioPath()
        val different = DocumentsContract.buildDocumentUri(authority, "primary:Download/other.cbz").toOkioPath()
        assertTrue(fromTree.isSameCbzDocument(single))
        assertFalse(fromTree.isSameCbzDocument(different))
    }

    @Test
    fun defaultDownloadDirectoryIsAnExistingAbsolutePath() {
        val directory = AppConfig.defaultDownloadDir.toOkioPath()
        assertTrue(directory.isAbsolute && directory.isDirectory)
    }

    @Test
    fun defaultFolderWarningIgnoresOnlyTheMediaScanMarker() = runBlocking {
        val folder = File.createTempFile("download-warning-", "", context.cacheDir).apply {
            check(delete() && mkdir())
        }
        try {
            assertFalse(folder.hasDownloadContents())
            keepNoMediaFileStatus(folder.toOkioPath(), mediaScan = false)
            val marker = File(folder, ".nomedia")
            assertTrue(marker.isFile)
            assertFalse(folder.hasDownloadContents())
            assertTrue(marker.isFile)
            val gallery = File(folder, "gallery").apply { check(mkdir()) }
            assertTrue(folder.hasDownloadContents())
            assertTrue(gallery.isDirectory)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun resettingDownloadLocationClearsTheStoredOverride() = runBlocking {
        val saved = Settings.snapshot()
        val authority = "com.android.externalstorage.documents"
        val root = DocumentsContract.buildTreeDocumentUri(authority, "primary:Download/Test Folder")
        val custom = DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root)).toOkioPath()
        val prefs = with(Settings) { listOf(downloadScheme, downloadAuthority, downloadPath, downloadQuery, downloadFragment) }
        try {
            setDownloadLocation(custom)
            assertEquals(custom, downloadLocation)
            setDownloadLocation(null)
            val reset = Settings.snapshot()
            assertTrue(prefs.none { it in reset })
            assertEquals(AppConfig.defaultDownloadDir.toOkioPath(), downloadLocation)
            assertTrue(downloadLocation.isDirectory)
        } finally {
            Settings.withMutableSnapshot { current -> prefs.forEach { current[it] = saved[it] } }
        }
    }

    @Test
    fun retryWaitsForPreviousArchiveToReleaseTheGallery() = runBlocking {
        val gid = 8_000_000_000L + System.nanoTime().ushr(1) % 1_000_000_000
        val gallery = GalleryEntity(gid, "test", "Archive restart", "", null, 0, null, null, 0f, null, 1, null, -2)
        val directory = getGalleryDownloadDir(gallery).apply { mkdirs() }
        val cbzEnabled = Settings.saveAsCbz.value
        val archiving = CompletableDeferred<Unit>()
        val unblock = CountDownLatch(1)
        val completed = CompletableDeferred<Boolean>()
        var running: SpiderQueen? = null
        try {
            Settings.withMutableSnapshot { it[Settings.saveAsCbz] = true }
            SpiderInfo(gid, gallery.token, 1).write(directory / SpiderQueen.SPIDER_INFO_FILENAME)
            (directory / "00000001.jpg").write { write("test page".encodeToByteArray()) }
            writeComicInfo(gallery.getComicInfo(), directory / "ComicInfo.xml")
            val first = SpiderQueen.obtainSpiderQueen(
                gallery,
                SpiderQueen.MODE_DOWNLOAD,
                object : SpiderQueen.OnSpiderListener {
                    override fun onArchiving() {
                        archiving.complete(Unit)
                        check(unblock.await(15, TimeUnit.SECONDS))
                    }
                },
            )
            running = first
            withTimeout(10_000) { archiving.await() }
            SpiderQueen.releaseSpiderQueen(first, SpiderQueen.MODE_DOWNLOAD)
            running = null
            val retry = SpiderQueen.obtainSpiderQueen(
                gallery,
                SpiderQueen.MODE_DOWNLOAD,
                object : SpiderQueen.OnSpiderListener {
                    override fun onFinish(finished: Int, downloaded: Int, total: Int, failed: Boolean) {
                        completed.complete(!failed && finished == 1 && downloaded == 1 && total == 1)
                    }
                },
            )
            running = retry
            assertEquals(null, withTimeoutOrNull(250) { retry.awaitReady() })
            unblock.countDown()
            assertTrue(withTimeout(15_000) { completed.await() })
            verifyCbz(directory / "$gid.cbz", 1, "ComicInfo.xml")
            assertFalse((directory / "$gid.cbz.part").exists())
            assertFalse((directory / "$gid.cbz.pending").exists())
        } finally {
            unblock.countDown()
            running?.let { SpiderQueen.releaseSpiderQueen(it, SpiderQueen.MODE_DOWNLOAD) }
            withTimeout(15_000) { SpiderQueen.awaitDownloadCleanup(gid) }
            Settings.withMutableSnapshot { it[Settings.saveAsCbz] = cbzEnabled }
            directory.delete()
            EhDB.removeDownloadDirname(gid)
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
