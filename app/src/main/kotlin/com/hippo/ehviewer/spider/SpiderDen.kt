/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.spider

import android.webkit.MimeTypeMap
import arrow.fx.coroutines.parMap
import com.ehviewer.core.database.model.DownloadArtist
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.find
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.list
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.moveTo
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.openInputStream
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.files.write
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUtils.getSuitableTitle
import com.hippo.ehviewer.client.ehRequest
import com.hippo.ehviewer.client.getImageKey
import com.hippo.ehviewer.coil.read
import com.hippo.ehviewer.coil.suspendEdit
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.download.tempDownloadDir
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.jni.appendZipEntry
import com.hippo.ehviewer.jni.beginZipWriter
import com.hippo.ehviewer.jni.closeZipWriter
import com.hippo.ehviewer.ktbuilder.diskCache
import com.hippo.ehviewer.ktor.allowReadTransportRetry
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.copyTo
import com.hippo.ehviewer.util.copyWithSha1
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import moe.tarsin.coroutines.runSuspendCatching
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

class SpiderDen(val info: GalleryInfo) {
    private val gid = info.gid
    var downloadDir: Path? = null
        private set

    private var tempDownloadDir: Path? = null
    private val saveAsCbz = Settings.saveAsCbz.value
    private val archiveName = "$gid.cbz"
    val archivesDownload get() = saveAsCbz
    private var archivedSources: List<Path>? = null

    private val lock = ReentrantReadWriteLock()

    private val fileIndex = PageFileIndex()

    private fun refreshFileIndex() = lock.write {
        // Search both directories to support galleries downloaded by older versions.
        fileIndex.refresh(sequenceOf(tempDownloadDir, downloadDir).filterNotNull().distinct().flatMap { it.list().asSequence() })
    }

    private val imageDir
        get() = tempDownloadDir.takeIf { saveAsCbz } ?: downloadDir

    constructor(info: GalleryInfo, dirname: String) : this(info) {
        downloadDir = downloadLocation / dirname
        tempDownloadDir = info.tempDownloadDir?.takeIf { it.isDirectory }
        refreshFileIndex()
    }

    @Volatile
    @SpiderQueen.Mode
    var mode = SpiderQueen.MODE_READ
        private set

    suspend fun setMode(value: Int) {
        if (value == SpiderQueen.MODE_DOWNLOAD) {
            val refresh = downloadDir == null || (saveAsCbz && tempDownloadDir == null)
            if (downloadDir == null) {
                downloadDir = getGalleryDownloadDir(info).apply { mkdirs() }
            }
            if (saveAsCbz && tempDownloadDir == null) {
                tempDownloadDir = info.tempDownloadDir!!.apply { mkdirs() }
            }
            if (refresh) refreshFileIndex()
        }
        mode = value
    }

    private fun containInCache(index: Int): Boolean {
        val key = getImageKey(gid, index)
        return sCache.read(key) {} != null
    }

    private fun findImageFile(index: Int): Path? {
        val candidates = lock.read { fileIndex.candidates(index, temporary = false) }
        for (file in candidates) {
            if (file.exists()) return file
            lock.write { if (!file.exists()) fileIndex.remove(file) }
        }
        return null
    }

    private fun containInDownloadDir(index: Int): Boolean = findImageFile(index) != null

    private fun copyFromCacheToDownloadDir(index: Int): Boolean {
        val dir = imageDir ?: return false
        val key = getImageKey(gid, index)
        return runCatching {
            sCache.read(key) {
                val extension = metadata.toFile().readText()
                val file = dir.resolve(perFilename(index, extension))
                val temporary = dir.resolve(file.name + TEMP_SUFFIX)
                lock.write { fileIndex.add(temporary) }
                try {
                    data sendTo temporary
                    commitImage(temporary, file)
                } finally {
                    temporary.delete()
                    lock.write { fileIndex.remove(temporary) }
                }
            } != null
        }.onFailure {
            logcat(it)
        }.getOrDefault(false)
    }

    operator fun contains(index: Int): Boolean = when (mode) {
        SpiderQueen.MODE_READ -> {
            containInCache(index) || containInDownloadDir(index)
        }
        SpiderQueen.MODE_DOWNLOAD -> {
            containInDownloadDir(index) || copyFromCacheToDownloadDir(index)
        }
        else -> {
            false
        }
    }

    private fun removeFromCache(index: Int): Boolean {
        val key = getImageKey(gid, index)
        return sCache.remove(key)
    }

    private fun removeTempFile(index: Int) {
        lock.read { fileIndex.candidates(index, temporary = true) }.forEach { file ->
            file.delete()
            lock.write { fileIndex.remove(file) }
        }
    }

    fun removeIntermediateFiles(index: Int) {
        removeFromCache(index)
        removeTempFile(index)
    }

    fun clearCache() {
        repeat(info.pages, ::removeFromCache)
    }

    private fun commitImage(temporary: Path, file: Path) {
        if (!file.isAbsolute) {
            file.delete()
            lock.write { fileIndex.remove(file) }
        }
        temporary moveTo file
        check(file.exists()) { "Unable to commit ${file.name}" }
        lock.write { fileIndex.add(file) }
    }

    suspend fun makeHttpCallAndSaveImage(
        index: Int,
        url: String,
        referer: String?,
        notifyProgress: (Long, Long, Int) -> Unit,
    ) = timeoutBySpeed(
        url,
        { configure ->
            ehRequest(url, referer) {
                allowReadTransportRetry()
                configure()
            }
        },
        notifyProgress,
        { resp -> check(saveFromHttpResponse(index, resp)) },
    )

    private suspend inline fun saveResponseMeta(
        index: Int,
        ext: String,
        crossinline fops: suspend (Path) -> Unit,
    ): Boolean {
        imageDir?.run {
            val file = resolve(perFilename(index, ext))
            val tempFile = resolve(file.name + TEMP_SUFFIX)
            lock.write { fileIndex.add(tempFile) }
            try {
                fops(tempFile)
                coroutineContext.ensureActive()
                commitImage(tempFile, file)
            } finally {
                tempFile.delete()
                lock.write { fileIndex.remove(tempFile) }
            }
            return true
        }

        // Read Mode, allow save to cache
        if (mode == SpiderQueen.MODE_READ) {
            val key = getImageKey(gid, index)
            return sCache.suspendEdit(key) {
                metadata.toFile().writeText(ext)
                fops(data)
            }
        }
        return false
    }

    private suspend fun saveFromHttpResponse(index: Int, response: HttpResponse): Boolean {
        val url = response.request.url.toString()
        val extension = MimeTypeMap.getFileExtensionFromUrl(url).ifEmpty { "jpg" }
        return saveResponseMeta(index, extension) { outFile ->
            val body = response.bodyAsChannel()
            val expected = FileHashRegex.find(url)?.groupValues?.get(1)
            if (expected == null) {
                body.copyTo(outFile)
            } else {
                val actual = outFile.write { body.copyWithSha1(this) }
                check(expected == actual) { "File hash mismatch: expected $expected, but got $actual\nURL: $url" }
            }
        }
    }

    fun saveToPath(index: Int, file: Path): Boolean {
        val key = getImageKey(gid, index)

        // Read from diskCache first
        sCache.read(key) {
            runCatching {
                data sendTo file
                return true
            }.onFailure {
                logcat(it)
                return false
            }
        }

        // Read from download dir
        runCatching {
            requireNotNull(findImageFile(index)) sendTo file
        }.onFailure {
            logcat(it)
            return false
        }.onSuccess {
            return true
        }
        return false
    }

    fun getExtension(index: Int): String? {
        val key = getImageKey(gid, index)
        return sCache.read(key) { metadata.toFile().readText() }
            ?: findImageFile(index)?.name.let { FileUtils.getExtensionFromFilename(it) }
    }

    fun getImageSource(index: Int): PathSource {
        if (mode == SpiderQueen.MODE_READ) {
            val key = getImageKey(gid, index)
            val snapshot = sCache.openSnapshot(key)
            if (snapshot != null) {
                return object : PathSource, AutoCloseable by snapshot {
                    override val source = snapshot.data
                    override val type by lazy {
                        snapshot.metadata.toFile().readText()
                    }
                }
            }
        }
        val source = requireNotNull(findImageFile(index)) { "Source $index not found!" }
        return object : PathSource {
            override val source = source
            override val type by lazy {
                FileUtils.getExtensionFromFilename(source.name)!!
            }

            override fun close() = Unit
        }
    }

    suspend fun archive(pages: Int): Boolean {
        if (!saveAsCbz) return true
        var stage = "write"
        return runSuspendCatching {
            val target = checkNotNull(downloadDir) / archiveName
            val sources = archiveSources(pages)
            CbzCommit(target).write(pages, onStage = { stage = it }) { staged ->
                buildCbz(staged, sources, refreshMetadata = true)
            }
            archivedSources = sources
        }.onFailure { failure ->
            logcat("SpiderDen") { "CBZ $stage failed (${failure.javaClass.simpleName})" }
        }.isSuccess
    }

    suspend fun recoverArchive(pages: Int): Boolean {
        val target = downloadDir?.resolve(archiveName) ?: return false
        return runSuspendCatching {
            CbzCommit(target).recover(pages)
        }.onFailure { logcat("SpiderDen") { "CBZ recovery failed (${it.javaClass.simpleName})" } }.getOrDefault(false)
    }

    // Postpone source cleanup until the last reader releases this gallery.
    suspend fun postArchive(): Boolean {
        val sources = archivedSources ?: return false
        val cleanup = runSuspendCatching {
            sources.parMap(concurrency = 10) { it.delete() }
            refreshFileIndex()
        }
        cleanup.onFailure { logcat("SpiderDen") { "CBZ source cleanup failed (${it.javaClass.simpleName})" } }
        return true
    }

    suspend fun exportAsCbz(file: Path) {
        val directory = checkNotNull(downloadDir)
        val existing = directory / archiveName
        val pages = readComicInfo(directory / COMIC_INFO_FILE)?.pageCount?.takeIf { it > 0 } ?: info.pages
        check(existing != file) { "CBZ export destination is the source" }
        withCbzTemp { staged ->
            if (existing.exists() && !existing.cbzSidecar(".pending").exists()) {
                existing.openInputStream().use { source -> staged.outputStream().use { copyCbz(source, it) } }
            } else {
                buildCbz(staged, archiveSources(pages), refreshMetadata = false)
            }
            verifyCbz(staged, pages, COMIC_INFO_FILE)
            val fingerprint = staged.inputStream().use { copyCbz(it) }
            copyVerifiedCbz(staged, file, create = false, fingerprint = fingerprint)
        }
    }

    private fun archiveSources(pages: Int): List<Path> {
        check(pages > 0) { "Unknown CBZ page count" }
        return List(pages) { index ->
            checkNotNull(findImageFile(index)) { "Missing CBZ source page ${index + 1}" }
        }
    }

    private suspend fun buildCbz(file: File, sources: List<Path>, refreshMetadata: Boolean) {
        val directory = checkNotNull(downloadDir)
        val existingMetadata = readComicInfo(directory / COMIC_INFO_FILE)
        if (refreshMetadata && existingMetadata == null && info !is GalleryDetail) {
            runSuspendCatching { EhEngine.fillGalleryListByApi(listOf(info)) }
                .onFailure { logcat("SpiderDen") { "CBZ metadata refresh failed (${it.javaClass.simpleName})" } }
        }
        val metadata = (existingMetadata ?: info.getComicInfo()).copy(pageCount = sources.size)
        withCbzTemp { comicInfo ->
            writeComicInfo(metadata, comicInfo.toOkioPath())
            file.toOkioPath().openFileDescriptor("rwt").use { output ->
                val handle = beginZipWriter(output.fd)
                try {
                    sources.forEachIndexed { index, image ->
                        coroutineContext.ensureActive()
                        val extension = checkNotNull(FileUtils.getExtensionFromFilename(image.name))
                        val name = perFilename(index, extension)
                        if (image.isAbsolute) {
                            image.openFileDescriptor("r").use { appendZipEntry(handle, it.fd, name) }
                        } else {
                            // A provider may return a pipe. Stage at most one page for the ZIP writer.
                            withCbzTemp { page ->
                                image.openInputStream().use { input -> page.outputStream().use { copyCbz(input, it) } }
                                page.toOkioPath().openFileDescriptor("r").use { appendZipEntry(handle, it.fd, name) }
                            }
                        }
                    }
                    comicInfo.toOkioPath().openFileDescriptor("r").use { appendZipEntry(handle, it.fd, COMIC_INFO_FILE) }
                    closeZipWriter(handle, true)
                } finally {
                    closeZipWriter(handle, false)
                }
            }
        }
        if (refreshMetadata) {
            runSuspendCatching {
                writeComicInfo(metadata, directory / COMIC_INFO_FILE)
                saveDownloadArtists(metadata)
            }.onFailure { logcat("SpiderDen") { "Unable to update local CBZ metadata (${it.javaClass.simpleName})" } }
        }
    }

    suspend fun initDownloadDirIfExist() {
        downloadDir = getGalleryDownloadDir(info).takeIf { it.isDirectory }
        tempDownloadDir = info.tempDownloadDir?.takeIf { it.isDirectory }
        refreshFileIndex()
    }

    suspend fun initDownloadDir() {
        downloadDir = getGalleryDownloadDir(info).apply { mkdirs() }
        refreshFileIndex()
    }

    suspend fun writeComicInfo(fetchMetadata: Boolean = true) {
        downloadDir?.run {
            resolve(COMIC_INFO_FILE).also {
                if (info !is GalleryDetail && fetchMetadata) {
                    EhEngine.fillGalleryListByApi(listOf(info))
                }
                info.getComicInfo().apply {
                    writeComicInfo(this, it)
                    saveDownloadArtists(this)
                }
            }
        }
    }

    private suspend fun saveDownloadArtists(metadata: ComicInfo) {
        DownloadManager.getDownloadInfo(gid)?.let { downloadInfo ->
            downloadInfo.artistInfoList = DownloadArtist.from(gid, metadata.penciller.orEmpty())
            EhDB.putDownloadArtist(gid, downloadInfo.artistInfoList)
        }
    }
}

private val sCache by lazy {
    diskCache {
        directory(appCtx.cacheDir.toOkioPath() / "image_cache")
        maxSizeBytes(Settings.readCacheSize.value.coerceIn(320, 5120).toLong() * 1024 * 1024)
    }
}
internal const val TEMP_SUFFIX = ".tmp"
private val FileNameRegex = Regex("^\\d{8}\\.\\w{3,4}")
private val FileHashRegex = Regex("/h/([0-9a-f]{40})")

fun perFilename(index: Int, extension: String = ""): String = "%08d.%s".format(index + 1, extension)

suspend fun GalleryInfo.downloadDirname(): String {
    var dirname = EhDB.getDownloadDirname(gid)
    if (dirname == null) {
        val title = getSuitableTitle(this)
        dirname = FileUtils.sanitizeFilename("$gid-$title")
        EhDB.putDownloadDirname(gid, dirname)
    }
    return dirname
}

suspend fun getGalleryDownloadDir(info: GalleryInfo): Path {
    val dirname = info.downloadDirname()
    return downloadLocation / dirname
}
