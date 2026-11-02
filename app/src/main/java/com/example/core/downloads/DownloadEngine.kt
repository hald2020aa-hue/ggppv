package com.example.core.downloads

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.core.database.ChapterEntity
import com.example.core.database.DownloadTaskEntity
import com.example.core.database.MangaDatabase
import com.example.core.database.PageEntity
import com.example.core.storage.LocalChapterStorage
import com.example.domain.models.DownloadStatus
import com.example.sourceapi.SourceRegistry
import com.example.sources.AzoraMangaSource
import com.example.sources.MangaDexSource
import com.example.sources.MangalekSource
import com.example.sources.MangatekSource
import com.example.sources.TeamXSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Rule 7, 11, 12, 13: Independent WorkManager Worker + Reactive Download Engine.
 * Downloads chapter pages with bounded concurrency, saves to .tmp first, validates image content,
 * moves to final .webp path, and updates Room database progress.
 */
class ChapterDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString("taskId") ?: return Result.failure()
        val db = MangaDatabase.getInstance(applicationContext)
        val dao = db.mangaDao()
        val storage = LocalChapterStorage(applicationContext)
        val registry = SourceRegistry(com.example.sources.BuiltInSourceCatalog.createAll())
        val factory = com.example.sourceapi.SourceAdapterFactory(registry)
        // Worker runs in a separate process/lifecycle: restore custom adapters from Room.
        dao.getAllSourcesOnce().forEach { saved ->
            val config = dao.getSourceConfiguration(saved.id)?.configurationJson?.let {
                runCatching { com.example.sourceapi.SourceConfiguration.fromJson(it) }.getOrNull()
            }
            val adapter = if (config != null) factory.create(config.copy(sourceId = saved.id, name = saved.name))
                else registry.findByDomain(saved.domain) ?: com.example.sources.DynamicSiteSource(
                    saved.domain, customId = saved.id, customName = saved.name
                )
            registry.register(if (adapter.id == saved.id) adapter else
                com.example.sourceapi.AliasedMangaSource(adapter, saved.id))
        }

        val task = dao.getDownloadTaskById(taskId) ?: return Result.failure()
        if (task.status == DownloadStatus.COMPLETED.name || task.status == DownloadStatus.CANCELLED.name) {
            return Result.success()
        }
        if (DownloadEngine.isForegroundTaskActive(taskId)) {
            return Result.success()
        }

        val chapter = dao.getChapterById(task.chapterId) ?: return Result.failure()
        val manga = dao.getMangaById(chapter.mangaId) ?: return Result.failure()
        if (manga.contentRating == "ADULT_18_PLUS" &&
            !com.example.core.storage.AppPreferences(applicationContext).settingsFlow.first().adultContentEnabled) {
            return Result.failure()
        }
        val source = registry.findById(chapter.sourceId) ?: return Result.failure()

        if (!storage.hasEnoughFreeSpace()) {
            dao.updateDownloadTaskProgress(
                taskId = taskId,
                status = DownloadStatus.FAILED.name,
                downloadedPages = task.downloadedPages,
                failedPages = task.totalPages - task.downloadedPages,
                progress = task.progress
            )
            return Result.failure()
        }

        val pages = source.getPages(chapter.remoteId)
        if (pages.isEmpty()) {
            dao.updateDownloadTaskProgress(taskId = taskId, status = DownloadStatus.FAILED.name,
                downloadedPages = 0, failedPages = 0, progress = 0)
            return Result.failure()
        }
        val total = pages.size
        var downloaded = task.downloadedPages.coerceIn(0, total)
        var failed = 0

        for (i in downloaded until total) {
            val currentTask = dao.getDownloadTaskById(taskId)
            if (currentTask == null ||
                currentTask.status == DownloadStatus.PAUSED.name ||
                currentTask.status == DownloadStatus.CANCELLED.name
            ) {
                return Result.success()
            }

            val page = pages[i]
            val saveRes = storage.downloadAndSavePage(
                sourceId = chapter.sourceId,
                mangaId = chapter.mangaId,
                chapterNumber = chapter.chapterNumber,
                pageIndex = page.index,
                imageUrl = page.imageUrl,
                mangaTitle = task.mangaTitle,
                chapterTitle = task.chapterTitle,
                dialogueAr = page.dialoguePreviewAr,
                refererUrl = source.baseUrl
            )

            if (saveRes.isSuccess) {
                downloaded++
                val file = saveRes.getOrNull()
                dao.upsertPages(
                    listOf(
                        PageEntity(
                            id = "${chapter.id}_p_${page.index}",
                            chapterId = chapter.id,
                            pageIndex = page.index,
                            imageUrl = page.imageUrl,
                            localPath = file?.absolutePath,
                            dialoguePreviewAr = page.dialoguePreviewAr
                        )
                    )
                )
            } else {
                failed++
            }

            val progress = (downloaded * 100) / total
            dao.updateDownloadTaskProgress(
                taskId = taskId,
                status = DownloadStatus.DOWNLOADING.name,
                downloadedPages = downloaded,
                failedPages = failed,
                progress = progress
            )
        }

        val finalStatus = if (downloaded >= total && failed == 0) {
            dao.updateChapterDownloaded(chapter.id, true)
            dao.setMangaSavedInStorage(chapter.mangaId, true)
            DownloadStatus.COMPLETED.name
        } else {
            DownloadStatus.FAILED.name
        }

        dao.updateDownloadTaskProgress(
            taskId = taskId,
            status = finalStatus,
            downloadedPages = downloaded,
            failedPages = failed,
            progress = if (finalStatus == DownloadStatus.COMPLETED.name) 100 else (downloaded * 100) / total
        )

        return if (finalStatus == DownloadStatus.COMPLETED.name) Result.success() else Result.retry()
    }
}

class DownloadEngine(
    private val context: Context,
    private val db: MangaDatabase,
    private val storage: LocalChapterStorage,
    private val registry: SourceRegistry
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val concurrencySemaphore = Semaphore(3)

    companion object {
        private val activeForegroundTaskIds = ConcurrentHashMap.newKeySet<String>()
        fun isForegroundTaskActive(taskId: String): Boolean = activeForegroundTaskIds.contains(taskId)
    }

    fun enqueueChapterDownload(
        mangaId: String,
        mangaTitle: String,
        coverUrl: String?,
        chapter: ChapterEntity,
        wifiOnly: Boolean = false
    ) {
        val taskId = "dl_${chapter.id}"
        scope.launch {
            val source = registry.findById(chapter.sourceId)
            val pages = source?.getPages(chapter.remoteId).orEmpty()
            val existing = db.mangaDao().getDownloadTaskById(taskId)

            if (pages.isEmpty()) {
                val failedEntity = DownloadTaskEntity(
                    id = taskId,
                    chapterId = chapter.id,
                    mangaId = mangaId,
                    mangaTitle = mangaTitle,
                    chapterTitle = "الفصل ${chapter.title}",
                    coverUrl = coverUrl,
                    status = DownloadStatus.FAILED.name,
                    totalPages = 0,
                    downloadedPages = 0,
                    failedPages = 1,
                    progress = 0,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis()
                )
                db.mangaDao().upsertDownloadTask(failedEntity)
                return@launch
            }

            val totalPages = pages.size
            val resumePages = if (existing != null && existing.status == DownloadStatus.PAUSED.name) {
                existing.downloadedPages.coerceIn(0, totalPages)
            } else {
                0
            }

            val entity = DownloadTaskEntity(
                id = taskId,
                chapterId = chapter.id,
                mangaId = mangaId,
                mangaTitle = mangaTitle,
                chapterTitle = "الفصل ${chapter.title}",
                coverUrl = coverUrl,
                status = DownloadStatus.DOWNLOADING.name,
                totalPages = totalPages,
                downloadedPages = resumePages,
                failedPages = 0,
                progress = (resumePages * 100) / totalPages,
                createdAt = existing?.createdAt ?: System.currentTimeMillis()
            )
            db.mangaDao().upsertDownloadTask(entity)
            db.mangaDao().setMangaSavedInStorage(mangaId, true)

            // Mark foreground task active BEFORE scheduling WorkManager so the worker never races
            activeForegroundTaskIds.add(taskId)
            startTaskCoroutine(taskId, chapter, mangaTitle, pages, resumePages)

            // Schedule WorkManager task as a fallback if the process is killed before completion
            runCatching {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
                val workRequest = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
                    .setConstraints(constraints)
                    .setInputData(workDataOf("taskId" to taskId))
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    taskId,
                    ExistingWorkPolicy.KEEP,
                    workRequest
                )
            }
        }
    }

    private fun startTaskCoroutine(
        taskId: String,
        chapter: ChapterEntity,
        mangaTitle: String,
        pagesInput: List<com.example.domain.models.MangaPage>,
        startFromPage: Int
    ) {
        activeJobs[taskId]?.cancel()
        activeForegroundTaskIds.add(taskId)
        val job = scope.launch {
            try {
                concurrencySemaphore.withPermit {
                    val dao = db.mangaDao()
                    val source = registry.findById(chapter.sourceId)
                    val pages = if (pagesInput.isNotEmpty()) pagesInput else source?.getPages(chapter.remoteId).orEmpty()
                    if (pages.isEmpty()) {
                        dao.updateDownloadTaskProgress(
                            taskId = taskId,
                            status = DownloadStatus.FAILED.name,
                            downloadedPages = 0,
                            failedPages = 1,
                            progress = 0
                        )
                        return@withPermit
                    }
                    val total = pages.size
                    var downloaded = startFromPage.coerceIn(0, total)
                    var failed = 0

                for (i in downloaded until total) {
                    val current = dao.getDownloadTaskById(taskId) ?: return@withPermit
                    if (current.status == DownloadStatus.PAUSED.name ||
                        current.status == DownloadStatus.CANCELLED.name
                    ) {
                        return@withPermit
                    }

                    delay(320L) // Smooth visual progress & rate-limiting respect (Rule 12)
                    val page = pages[i]
                    val res = storage.downloadAndSavePage(
                        sourceId = chapter.sourceId,
                        mangaId = chapter.mangaId,
                        chapterNumber = chapter.chapterNumber,
                        pageIndex = page.index,
                        imageUrl = page.imageUrl,
                        mangaTitle = mangaTitle,
                        chapterTitle = "الفصل ${chapter.title}",
                        dialogueAr = page.dialoguePreviewAr,
                        refererUrl = source?.baseUrl
                    )
                    if (res.isSuccess) {
                        downloaded++
                        val savedFile = res.getOrNull()
                        dao.upsertPages(
                            listOf(
                                PageEntity(
                                    id = "${chapter.id}_p_${page.index}",
                                    chapterId = chapter.id,
                                    pageIndex = page.index,
                                    imageUrl = page.imageUrl,
                                    localPath = savedFile?.absolutePath,
                                    dialoguePreviewAr = page.dialoguePreviewAr
                                )
                            )
                        )
                    } else {
                        failed++
                    }

                    val progress = (downloaded * 100) / total
                    dao.updateDownloadTaskProgress(
                        taskId = taskId,
                        status = DownloadStatus.DOWNLOADING.name,
                        downloadedPages = downloaded,
                        failedPages = failed,
                        progress = progress
                    )
                }

                if (downloaded >= total && failed == 0) {
                    dao.updateChapterDownloaded(chapter.id, true)
                    dao.setMangaSavedInStorage(chapter.mangaId, true)
                    dao.updateDownloadTaskProgress(
                        taskId = taskId,
                        status = DownloadStatus.COMPLETED.name,
                        downloadedPages = total,
                        failedPages = 0,
                        progress = 100
                    )
                    } else {
                        dao.updateDownloadTaskProgress(
                            taskId = taskId,
                            status = DownloadStatus.FAILED.name,
                            downloadedPages = downloaded,
                            failedPages = failed,
                            progress = (downloaded * 100) / total
                        )
                    }
                }
            } finally {
                activeForegroundTaskIds.remove(taskId)
            }
        }
        activeJobs[taskId] = job
    }

    suspend fun pauseTask(taskId: String) {
        activeJobs[taskId]?.cancel()
        val task = db.mangaDao().getDownloadTaskById(taskId) ?: return
        db.mangaDao().updateDownloadTaskProgress(
            taskId = taskId,
            status = DownloadStatus.PAUSED.name,
            downloadedPages = task.downloadedPages,
            failedPages = task.failedPages,
            progress = task.progress
        )
    }

    suspend fun resumeTask(taskId: String) {
        val task = db.mangaDao().getDownloadTaskById(taskId) ?: return
        val chapter = db.mangaDao().getChapterById(task.chapterId) ?: return
        enqueueChapterDownload(
            mangaId = task.mangaId,
            mangaTitle = task.mangaTitle,
            coverUrl = task.coverUrl,
            chapter = chapter
        )
    }

    suspend fun cancelTask(taskId: String) {
        activeJobs[taskId]?.cancel()
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(taskId) }
        val task = db.mangaDao().getDownloadTaskById(taskId) ?: return
        db.mangaDao().updateDownloadTaskProgress(
            taskId = taskId,
            status = DownloadStatus.CANCELLED.name,
            downloadedPages = task.downloadedPages,
            failedPages = task.failedPages,
            progress = task.progress
        )
    }

    suspend fun pauseAllActive() {
        activeJobs.values.forEach { it.cancel() }
        db.mangaDao().updateDownloadTasksByStatus(
            currentStatus = DownloadStatus.DOWNLOADING.name,
            newStatus = DownloadStatus.PAUSED.name
        )
    }

    suspend fun cancelAllActive() {
        activeJobs.values.forEach { it.cancel() }
        db.mangaDao().updateDownloadTasksByStatus(
            currentStatus = DownloadStatus.DOWNLOADING.name,
            newStatus = DownloadStatus.CANCELLED.name
        )
        db.mangaDao().updateDownloadTasksByStatus(
            currentStatus = DownloadStatus.PAUSED.name,
            newStatus = DownloadStatus.CANCELLED.name
        )
    }

    suspend fun clearHistory() {
        activeJobs.values.forEach { it.cancel() }
        db.mangaDao().clearAllDownloadTasks()
    }
}
