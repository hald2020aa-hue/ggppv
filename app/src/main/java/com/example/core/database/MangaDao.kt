package com.example.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MangaDao {

    // ─── SOURCES ───
    @Query("SELECT * FROM sources ORDER BY lastCheckedAt DESC")
    fun observeSources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources")
    suspend fun getAllSourcesOnce(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE domain = :domain LIMIT 1")
    suspend fun getSourceByDomain(domain: String): SourceEntity?

    @Query("SELECT * FROM sources WHERE id = :sourceId LIMIT 1")
    suspend fun getSourceById(sourceId: String): SourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSource(source: SourceEntity)

    @Query("UPDATE sources SET isEnabled = :enabled, status = :status WHERE id = :sourceId")
    suspend fun updateSourceEnabled(sourceId: String, enabled: Boolean, status: String)

    @Query("DELETE FROM sources WHERE id = :sourceId")
    suspend fun deleteSource(sourceId: String)

    @Query("DELETE FROM sources")
    suspend fun clearAllSources()

    @Query("SELECT * FROM source_configs WHERE sourceId = :id LIMIT 1")
    suspend fun getSourceConfiguration(id: String): SourceConfigurationEntity?

    @Query("SELECT * FROM source_configs WHERE domain = :domain LIMIT 1")
    suspend fun getSourceConfigurationByDomain(domain: String): SourceConfigurationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSourceConfiguration(config: SourceConfigurationEntity)

    @Query("DELETE FROM source_configs WHERE sourceId = :id")
    suspend fun deleteSourceConfiguration(id: String)

    // ─── MANGA ───
    @Query("SELECT * FROM manga WHERE sourceId = :sourceId ORDER BY updatedAt DESC")
    fun observeMangaBySource(sourceId: String): Flow<List<MangaEntity>>

    @Query("SELECT * FROM manga WHERE sourceId = :sourceId ORDER BY updatedAt DESC")
    suspend fun getMangaBySourceOnce(sourceId: String): List<MangaEntity>

    @Query("SELECT * FROM manga WHERE isSavedInStorage = 1 OR id IN (SELECT DISTINCT mangaId FROM chapters WHERE isDownloaded = 1) ORDER BY updatedAt DESC")
    fun observeStoredManga(): Flow<List<MangaEntity>>

    @Query("SELECT * FROM manga")
    fun observeAllManga(): Flow<List<MangaEntity>>

    @Query("SELECT * FROM manga WHERE id = :mangaId LIMIT 1")
    fun observeMangaById(mangaId: String): Flow<MangaEntity?>

    @Query("SELECT * FROM manga WHERE id = :mangaId LIMIT 1")
    suspend fun getMangaById(mangaId: String): MangaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertManga(manga: MangaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMangaList(list: List<MangaEntity>)

    @Query("UPDATE manga SET isSavedInStorage = :saved, updatedAt = :timestamp WHERE id = :mangaId")
    suspend fun setMangaSavedInStorage(mangaId: String, saved: Boolean, timestamp: Long = System.currentTimeMillis())

    // ─── CHAPTERS ───
    @Query("SELECT * FROM chapters WHERE mangaId = :mangaId ORDER BY chapterNumber ASC")
    fun observeChaptersByManga(mangaId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE mangaId = :mangaId ORDER BY chapterNumber ASC")
    suspend fun getChaptersByMangaOnce(mangaId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :chapterId LIMIT 1")
    suspend fun getChapterById(chapterId: String): ChapterEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChaptersIgnoreDuplicates(chapters: List<ChapterEntity>)

    @Update
    suspend fun updateChapter(chapter: ChapterEntity)

    @Transaction
    suspend fun syncChaptersPreservingState(mangaId: String, incoming: List<ChapterEntity>) {
        val existing = getChaptersByMangaOnce(mangaId).associateBy { "${it.sourceId}:${it.remoteId}" }
        val merged = incoming.map { ch ->
            val old = existing["${ch.sourceId}:${ch.remoteId}"]
            if (old != null) {
                ch.copy(
                    id = old.id,
                    isRead = old.isRead,
                    isDownloaded = old.isDownloaded,
                    lastReadPage = old.lastReadPage
                )
            } else {
                ch
            }
        }
        upsertChapters(merged)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapters(chapters: List<ChapterEntity>)

    @Query("UPDATE chapters SET isRead = :isRead, lastReadPage = :lastPage WHERE id = :chapterId")
    suspend fun updateChapterReadProgress(chapterId: String, isRead: Boolean, lastPage: Int)

    @Query("UPDATE chapters SET isDownloaded = :downloaded WHERE id = :chapterId")
    suspend fun updateChapterDownloaded(chapterId: String, downloaded: Boolean)

    // ─── PAGES ───
    @Query("SELECT * FROM pages WHERE chapterId = :chapterId ORDER BY pageIndex ASC")
    suspend fun getPagesForChapter(chapterId: String): List<PageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPages(pages: List<PageEntity>)

    @Query("UPDATE pages SET localPath = :localPath WHERE chapterId = :chapterId AND pageIndex = :pageIndex")
    suspend fun updatePageLocalPath(chapterId: String, pageIndex: Int, localPath: String?)

    // ─── FAVORITES ───
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeFavorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE mangaId = :mangaId)")
    fun observeIsFavorite(mangaId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE mangaId = :mangaId")
    suspend fun removeFavorite(mangaId: String)

    // ─── READING HISTORY ───
    @Query("SELECT * FROM reading_history ORDER BY updatedAt DESC")
    fun observeReadingHistory(): Flow<List<ReadingHistoryEntity>>

    @Query("SELECT * FROM reading_history WHERE mangaId = :mangaId LIMIT 1")
    fun observeHistoryForManga(mangaId: String): Flow<ReadingHistoryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadingHistory(entry: ReadingHistoryEntity)

    @Query("DELETE FROM reading_history")
    suspend fun clearReadingHistory()

    // ─── DOWNLOAD TASKS ───
    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun observeDownloadTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getDownloadTaskById(taskId: String): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownloadTask(task: DownloadTaskEntity)

    @Query("UPDATE download_tasks SET status = :status, downloadedPages = :downloadedPages, failedPages = :failedPages, progress = :progress WHERE id = :taskId")
    suspend fun updateDownloadTaskProgress(
        taskId: String,
        status: String,
        downloadedPages: Int,
        failedPages: Int,
        progress: Int
    )

    @Query("UPDATE download_tasks SET status = :newStatus WHERE status = :currentStatus")
    suspend fun updateDownloadTasksByStatus(currentStatus: String, newStatus: String)

    @Query("DELETE FROM download_tasks WHERE id = :taskId")
    suspend fun deleteDownloadTask(taskId: String)

    @Query("DELETE FROM download_tasks")
    suspend fun clearAllDownloadTasks()
}
