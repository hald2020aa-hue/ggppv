package com.example.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sources",
    indices = [Index(value = ["domain"], unique = true)]
)
data class SourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val domain: String,
    val baseUrl: String,
    val language: String,
    val iconUrl: String?,
    val iconText: String = "M",
    val iconColorHex: Long = 0xFF3F51B5,
    val isEnabled: Boolean,
    val isSupported: Boolean,
    val status: String = "SUPPORTED",
    val version: Int,
    val lastCheckedAt: Long
)

@Entity(
    tableName = "manga",
    indices = [
        Index(value = ["sourceId", "remoteId"], unique = true),
        Index(value = ["sourceId"])
    ]
)
data class MangaEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val remoteId: String,
    val title: String,
    val coverUrl: String?,
    val description: String,
    val status: String?,
    val author: String?,
    val artist: String?,
    val genresCsv: String = "",
    val rating: Float? = null,
    val latestChapter: String? = null,
    val isSavedInStorage: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "GENERAL") val contentRating: String = "GENERAL"
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mangaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sourceId", "remoteId"], unique = true),
        Index(value = ["mangaId"])
    ]
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val mangaId: String,
    val sourceId: String,
    val remoteId: String,
    val title: String,
    val chapterNumber: Float?,
    val uploadDate: Long?,
    val dateLabel: String = "",
    val isRead: Boolean = false,
    val isDownloaded: Boolean = false,
    val lastReadPage: Int = 0
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["chapterId", "pageIndex"], unique = true),
        Index(value = ["chapterId"])
    ]
)
data class PageEntity(
    @PrimaryKey val id: String,
    val chapterId: String,
    val pageIndex: Int,
    val imageUrl: String,
    val localPath: String?,
    val dialoguePreviewAr: String = ""
)

@Entity(
    tableName = "favorites",
    indices = [Index(value = ["mangaId"], unique = true)]
)
data class FavoriteEntity(
    @PrimaryKey val mangaId: String,
    val category: String = "الكل",
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "reading_history",
    indices = [Index(value = ["mangaId"], unique = true)]
)
data class ReadingHistoryEntity(
    @PrimaryKey val id: String,
    val mangaId: String,
    val chapterId: String,
    val chapterTitle: String,
    val pageIndex: Int,
    val scrollOffset: Int,
    val updatedAt: Long
)

@Entity(
    tableName = "download_tasks",
    indices = [Index(value = ["chapterId"], unique = true)]
)
data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    val chapterId: String,
    val mangaId: String,
    val mangaTitle: String,
    val chapterTitle: String,
    val coverUrl: String?,
    val status: String,
    val totalPages: Int,
    val downloadedPages: Int,
    val failedPages: Int,
    val progress: Int,
    val createdAt: Long = System.currentTimeMillis()
)

/** Site-specific selector configuration. Stored separately to preserve all preexisting source rows. */
@Entity(tableName = "source_configs")
data class SourceConfigurationEntity(
    @PrimaryKey val sourceId: String,
    val domain: String,
    val configurationJson: String,
    val lastValidatedAt: Long = 0L
)
