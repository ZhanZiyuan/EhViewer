@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ehviewer.core.database.download

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Upsert
import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.domain.download.GalleryDownloadFailure
import com.ehviewer.core.domain.download.GalleryDownloadMetadata
import com.ehviewer.core.domain.download.GalleryDownloadState
import kotlinx.serialization.cbor.Cbor

/** Separate new-platform queue: the existing Android DOWNLOADS table and migrations remain intact. */
@Entity(tableName = "TASKS")
data class GalleryDownloadEntity(
    @PrimaryKey val gid: Long,
    val token: String,
    val title: String,
    val pages: Int,
    val restricted: Boolean,
    val state: String,
    val finished: Int,
    val failure: String?,
    val position: Long,
    @ColumnInfo(defaultValue = "NULL") val metadata: ByteArray? = null,
) {
    fun domain() = GalleryDownload(gid, token, title, pages, restricted, GalleryDownloadState.valueOf(state), finished, failure?.let(GalleryDownloadFailure::valueOf), position, metadata?.let { Cbor.decodeFromByteArray(GalleryDownloadMetadata.serializer(), it) }).also { it.validate() }
}

fun GalleryDownload.entity() = GalleryDownloadEntity(gid, token, title, pages, restricted, state.name, finished, failure?.name, position, metadata?.let { Cbor.encodeToByteArray(GalleryDownloadMetadata.serializer(), it) })

@Entity(
    tableName = "PAGES",
    primaryKeys = ["gid", "index"],
    foreignKeys = [ForeignKey(GalleryDownloadEntity::class, ["gid"], ["gid"], onDelete = ForeignKey.CASCADE)],
)
data class DownloadPageEntity(val gid: Long, val index: Int, val token: String, val bytes: Long, val sha256: String)

data class DownloadImageUsage(val bytes: Long, val invalid: Int)

@Dao
interface GalleryDownloadsDao {
    @Query("SELECT * FROM TASKS ORDER BY position, gid LIMIT 501")
    suspend fun list(): List<GalleryDownloadEntity>

    @Query("SELECT * FROM TASKS WHERE gid=:gid")
    suspend fun task(gid: Long): GalleryDownloadEntity?

    @Upsert suspend fun save(task: GalleryDownloadEntity)

    @Query("SELECT * FROM PAGES WHERE gid=:gid ORDER BY `index` LIMIT 100001")
    suspend fun pages(gid: Long): List<DownloadPageEntity>

    @Query("SELECT * FROM PAGES WHERE gid=:gid AND `index`=:index")
    suspend fun page(gid: Long, index: Int): DownloadPageEntity?

    @Query("SELECT COALESCE(SUM(bytes), 0) AS bytes, COALESCE(MAX(CASE WHEN bytes BETWEEN 1 AND 33554432 THEN 0 ELSE 1 END), 0) AS invalid FROM PAGES")
    suspend fun storedImageUsage(): DownloadImageUsage

    @Upsert suspend fun save(page: DownloadPageEntity)

    @Query("DELETE FROM TASKS WHERE gid=:gid")
    suspend fun remove(gid: Long)
}

@Database(entities = [GalleryDownloadEntity::class, DownloadPageEntity::class], version = 2, autoMigrations = [AutoMigration(from = 1, to = 2)], exportSchema = true)
@ConstructedBy(DownloadDatabaseConstructor::class)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloads(): GalleryDownloadsDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object DownloadDatabaseConstructor : RoomDatabaseConstructor<DownloadDatabase> {
    override fun initialize(): DownloadDatabase
}
