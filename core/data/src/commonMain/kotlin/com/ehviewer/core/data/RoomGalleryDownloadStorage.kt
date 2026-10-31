package com.ehviewer.core.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.ehviewer.core.database.download.DownloadDatabase
import com.ehviewer.core.database.download.DownloadPageEntity
import com.ehviewer.core.database.download.entity
import com.ehviewer.core.domain.download.DownloadedGalleryPage
import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.domain.download.GalleryDownloadState
import com.ehviewer.core.domain.download.GalleryDownloadStorage
import com.ehviewer.core.domain.download.GalleryDownloadStorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/** Real files plus separate Room queue metadata; images are committed before their metadata row. */
class RoomGalleryDownloadStorage(
    private val database: DownloadDatabase,
    private val directory: Path,
    private val files: FileSystem = FileSystem.SYSTEM,
) : GalleryDownloadStorage {
    private val mutex = Mutex()
    private val dao = database.downloads()

    init {
        require(directory.isAbsolute)
    }

    private suspend fun <T> stored(block: suspend () -> T): T = try {
        withContext(Dispatchers.IO) { mutex.withLock { block() } }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryDownloadStorageException()
    }

    override suspend fun list(): List<GalleryDownload> = stored {
        dao.list().also { check(it.size <= 500) }.map { it.domain() }
    }

    override suspend fun save(download: GalleryDownload) = stored {
        download.validate()
        require(download.pages > 0)
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val current = dao.task(download.gid)?.domain()
                if (current != null) check(current.token == download.token && current.pages == download.pages && current.restricted == download.restricted && download.finished >= current.finished)
                if (download.finished > 0) check(dao.page(download.gid, download.finished - 1) != null)
                dao.save(download.entity())
            }
        }
    }

    private fun folder(gid: Long): Path {
        require(gid > 0)
        files.createDirectories(directory)
        check(files.metadata(directory).symlinkTarget == null)
        return (directory / gid.toString()).also {
            files.createDirectories(it)
            check(files.metadata(it).symlinkTarget == null)
        }
    }

    private fun image(gid: Long, index: Int): Path {
        require(index in 0 until 100_000)
        return folder(gid) / "page-$index.bin"
    }

    private fun validate(record: DownloadPageEntity, task: GalleryDownload) {
        check(record.gid == task.gid && record.index in 0 until task.pages && Regex("[a-f0-9]{10}").matches(record.token))
        check(record.bytes in 1..MAX_IMAGE_BYTES && Regex("[a-f0-9]{64}").matches(record.sha256))
        val metadata = files.metadata(image(record.gid, record.index))
        check(metadata.isRegularFile && metadata.symlinkTarget == null && metadata.size == record.bytes)
    }

    override suspend fun pages(gid: Long): List<DownloadedGalleryPage> = stored {
        val task = checkNotNull(dao.task(gid)).domain()
        val records = dao.pages(gid)
        check(records.size <= task.pages && records.map { it.index } == records.indices.toList())
        records.map {
            validate(it, task)
            DownloadedGalleryPage(it.index, it.token, it.bytes)
        }
    }

    private fun read(record: DownloadPageEntity, task: GalleryDownload): ByteArray {
        validate(record, task)
        val bytes = files.read(image(task.gid, record.index)) { readByteArray(record.bytes) }
        check(bytes.toByteString().sha256().hex() == record.sha256)
        return bytes
    }

    override suspend fun readPage(gid: Long, index: Int): ByteArray = stored {
        val task = checkNotNull(dao.task(gid)).domain()
        read(checkNotNull(dao.page(gid, index)), task)
    }

    override suspend fun writePage(gid: Long, index: Int, token: String, bytes: ByteArray) = stored {
        require(bytes.size.toLong() in 1..MAX_IMAGE_BYTES && Regex("[a-f0-9]{10}").matches(token))
        val operation = currentCoroutineContext()
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val task = checkNotNull(dao.task(gid)).domain()
                check(task.state == GalleryDownloadState.DOWNLOADING && index in 0 until task.pages)
                val existing = dao.page(gid, index)
                if (existing != null) {
                    check(existing.token == token)
                    read(existing, task) // A duplicate worker cannot overwrite an already committed image.
                } else {
                    if (index > 0) check(dao.page(gid, index - 1) != null)
                    val target = image(gid, index)
                    val temporary = folder(gid) / "page-$index.part"
                    check(files.metadataOrNull(temporary)?.symlinkTarget == null && files.metadataOrNull(target)?.symlinkTarget == null)
                    files.openReadWrite(temporary).use { handle ->
                        handle.resize(0)
                        handle.sink().buffer().use { it.write(bytes) }
                        handle.flush()
                    }
                    files.atomicMove(temporary, target)
                    operation.ensureActive()
                    dao.save(DownloadPageEntity(gid, index, token, bytes.size.toLong(), bytes.toByteString().sha256().hex()))
                    operation.ensureActive()
                }
                Unit
            }
        }
    }

    override suspend fun remove(gid: Long) = stored {
        require(gid > 0)
        // Keep the task recoverable if filesystem deletion fails, rather than silently losing it.
        files.deleteRecursively(folder(gid), mustExist = true)
        dao.remove(gid)
    }

    companion object {
        const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
    }
}
