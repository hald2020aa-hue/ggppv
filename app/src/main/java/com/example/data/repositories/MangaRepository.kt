package com.example.data.repositories

import com.example.core.common.PipelineLogger
import com.example.core.common.PipelineStage
import com.example.core.database.ChapterEntity
import com.example.core.database.FavoriteEntity
import com.example.core.database.MangaDao
import com.example.core.database.MangaEntity
import com.example.core.database.PageEntity
import com.example.core.database.ReadingHistoryEntity
import com.example.core.database.SourceEntity
import com.example.core.database.SourceConfigurationEntity
import com.example.core.downloads.DownloadEngine
import com.example.core.storage.LocalChapterStorage
import com.example.core.storage.StorageStats
import com.example.core.storage.AppPreferences
import com.example.core.common.ContentPolicy
import com.example.domain.models.ContentRating
import com.example.core.verification.VerificationChallengeType
import com.example.core.verification.VerificationDetectionResult
import com.example.core.verification.VerificationManager
import com.example.core.verification.VerificationRequiredException
import com.example.core.verification.VerificationSession
import com.example.core.verification.VerificationState
import com.example.domain.models.DownloadStatus
import com.example.domain.models.DownloadTask
import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaDetails
import com.example.domain.models.MangaPage
import com.example.domain.models.MangaSummary
import com.example.domain.models.SourceDiagnosticReport
import com.example.domain.models.SourceInfo
import com.example.domain.models.SourceStatus
import com.example.sourceapi.MangaSource
import com.example.sourceapi.SourceConfiguration
import com.example.sourceapi.UniversalSourceEngine
import com.example.sourceapi.AliasedMangaSource
import com.example.sourceapi.SourceManager
import com.example.sourceapi.SourceRegistry
import com.example.sourceapi.SourceValidationStepResult
import com.example.sourceapi.SourceValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Section 6: SourceRepository contract managing source registration, domain validation,
 * and security verification states across [SourceManager], [SourceRegistry], and [VerificationManager].
 */
interface SourceRepository {
    val addedSourcesFlow: Flow<List<SourceInfo>>
    val verificationSessionsFlow: StateFlow<Map<String, VerificationSession>>
    suspend fun ensureDefaultSourcesRegistered()
    suspend fun clearAllSources()
    suspend fun resolveConnector(sourceIdOrDomain: String): MangaSource?
    suspend fun validateSiteDomain(rawInput: String): Result<SourceValidationStepResult>
    fun getSourceVerificationState(sourceIdOrDomain: String): VerificationState
    fun startSourceVerification(sourceId: String, domain: String): VerificationSession
    fun completeSourceVerification(sourceId: String, domain: String, verifiedHtml: String? = null, pageTitle: String = ""): VerificationSession
    fun cancelSourceVerification(sourceId: String, domain: String): VerificationSession
    fun failSourceVerification(sourceId: String, domain: String, reasonAr: String): VerificationSession
}

class MangaRepository(
    private val dao: MangaDao,
    private val registry: SourceRegistry,
    private val validator: SourceValidator,
    private val storage: LocalChapterStorage,
    private val downloadEngine: DownloadEngine,
    private val preferences: AppPreferences,
    val verificationManager: VerificationManager = validator.verificationManager,
    val sourceManager: SourceManager = SourceManager(registry, validator, verificationManager)
) : SourceRepository {
    val universalEngine = UniversalSourceEngine(registry, verificationManager)

    private suspend fun adapterFor(source: SourceEntity): MangaSource {
        val saved = dao.getSourceConfiguration(source.id)
        val base = if (saved != null) {
            runCatching { SourceConfiguration.fromJson(saved.configurationJson) }.getOrNull()?.let { config ->
                universalEngine.adapters.configure(config.copy(sourceId = source.id, name = source.name))
            }
        } else null
        val result = base ?: registry.findByDomain(source.domain) ?: com.example.sources.DynamicSiteSource(
            domain = source.domain, customId = source.id, customName = source.name,
            customIconText = source.iconText, customIconColorHex = source.iconColorHex
        )
        return if (result.id != source.id) AliasedMangaSource(result, source.id) else result
    }

    suspend fun runSourceDiagnostics(sourceId: String): SourceDiagnosticReport? {
        val entry = dao.getSourceById(sourceId) ?: return null
        val adapter = resolveConnector(entry.id) ?: return null
        val report = universalEngine.tests.run(adapter)
        dao.upsertSource(entry.copy(
            status = report.adapterStatus.name,
            isSupported = report.adapterStatus == SourceStatus.SUPPORTED,
            lastCheckedAt = report.lastTestedAt
        ))
        return report
    }

    suspend fun getSourceConfiguration(id: String): SourceConfiguration? =
        dao.getSourceConfiguration(id)?.configurationJson?.let { runCatching { SourceConfiguration.fromJson(it) }.getOrNull() }

    suspend fun previewSourceConfiguration(config: SourceConfiguration): List<MangaSummary> {
        val allowAdult = preferences.settingsFlow.first().adultContentEnabled
        return universalEngine.adapters.candidate(config).getPopular(1).items
            .filter { allowAdult || ContentPolicy.classify(it.genres, it.contentRating) != ContentRating.ADULT_18_PLUS }
            .take(12)
    }

    suspend fun saveSourceConfiguration(config: SourceConfiguration): SourceDiagnosticReport {
        val current = dao.getSourceById(config.sourceId) ?: error("Source must be added first")
        require(config.domain == current.domain) { "Source domain cannot change during configuration" }
        // Preserve built-in specialist: selector config never replaces its adapter.
        config.validatePaths()
        val rawAdapter = universalEngine.adapters.candidate(config)
        val adapter = if (rawAdapter.id == config.sourceId) rawAdapter else
            AliasedMangaSource(rawAdapter, config.sourceId)
        val report = universalEngine.tests.run(adapter)
        // Only publish the candidate after the test, preserving the previous live adapter on errors.
        adapter.attachVerificationManager(verificationManager)
        registry.register(adapter)
        dao.upsertSourceConfiguration(SourceConfigurationEntity(current.id, current.domain, config.toJson(),
            System.currentTimeMillis()))
        dao.upsertSource(current.copy(name = config.name, status = report.adapterStatus.name,
            isSupported = report.adapterStatus == SourceStatus.SUPPORTED,
            lastCheckedAt = System.currentTimeMillis()))
        return report
    }

    override val verificationSessionsFlow: StateFlow<Map<String, VerificationSession>> =
        verificationManager.sessionsFlow

    // ─── SOURCES MANAGEMENT ───
    override val addedSourcesFlow: Flow<List<SourceInfo>> = dao.observeSources().map { list ->
        list.map { it.toDomain() }
    }

    /**
     * Restores any user-added sources from the Room `sources` table into [SourceRegistry] on launch.
     * Does NOT auto-insert pre-loaded manga websites — the user adds their desired sites manually.
     */
    override suspend fun ensureDefaultSourcesRegistered() {
        for (saved in dao.getAllSourcesOnce()) {
            val adapter = adapterFor(saved)
            adapter.attachVerificationManager(verificationManager)
            registry.register(adapter)
        }
    }

    override suspend fun clearAllSources() {
        val saved = dao.getAllSourcesOnce()
        dao.clearAllSources()
        saved.forEach { source ->
            dao.deleteSourceConfiguration(source.id)
            registry.unregister(source.id)
        }
        // Built-ins remain in the registry, but never appear as user-added Room entries.
    }

    override suspend fun resolveConnector(sourceIdOrDomain: String): MangaSource? {
        val row = dao.getSourceById(sourceIdOrDomain)
            ?: dao.getSourceByDomain(sourceIdOrDomain)
        if (row != null) {
            registry.findById(row.id)?.let { return it }
            return adapterFor(row).also { it.attachVerificationManager(verificationManager); registry.register(it) }
        }
        return registry.findById(sourceIdOrDomain) ?: registry.findByDomain(sourceIdOrDomain)
    }

    override suspend fun validateSiteDomain(rawInput: String): Result<SourceValidationStepResult> {
        return validator.validateDomainCompatibility(rawInput)
    }

    override fun getSourceVerificationState(sourceIdOrDomain: String): VerificationState {
        return sourceManager.getVerificationStateForSource(sourceIdOrDomain)
    }

    override fun startSourceVerification(sourceId: String, domain: String): VerificationSession {
        return verificationManager.startVerification(sourceId, domain)
    }

    override fun completeSourceVerification(
        sourceId: String,
        domain: String,
        verifiedHtml: String?,
        pageTitle: String
    ): VerificationSession {
        return verificationManager.completeVerification(
            sourceId = sourceId,
            domain = domain,
            verifiedHtml = verifiedHtml,
            pageTitle = pageTitle
        )
    }

    override fun cancelSourceVerification(sourceId: String, domain: String): VerificationSession {
        return verificationManager.cancelVerification(sourceId, domain)
    }

    override fun failSourceVerification(sourceId: String, domain: String, reasonAr: String): VerificationSession {
        return verificationManager.failVerification(sourceId, domain, reasonAr)
    }

    fun markDomainHumanVerified(domain: String, verifiedHtml: String? = null) {
        validator.markDomainHumanVerified(domain, verifiedHtml)
    }

    fun isDomainHumanVerified(domain: String): Boolean {
        return validator.isDomainHumanVerified(domain)
    }

    suspend fun confirmAndAddSource(
        validationResult: SourceValidationStepResult,
        customSiteName: String
    ): Result<SourceInfo> {
        val connector = validationResult.matchedSource
            ?: com.example.sources.DynamicSiteSource(
                domain = validationResult.normalizedDomain,
                customName = customSiteName.trim().ifEmpty { validationResult.suggestedName }
            )

        val existing = dao.getSourceByDomain(validationResult.normalizedDomain)
        val finalName = customSiteName.trim().ifEmpty { connector.name }
        val sourceId = existing?.id ?: connector.id

        // Register in active SourceRegistry with the user's chosen name and ID
        val registeredAdapter = if (connector.id != sourceId || connector.name != finalName) {
            registry.findByDomain(validationResult.normalizedDomain) ?: com.example.sources.DynamicSiteSource(
                domain = validationResult.normalizedDomain,
                customId = sourceId,
                customName = finalName,
                customIconText = connector.iconText,
                customIconColorHex = connector.iconColorHex
            )
        } else {
            connector
        }
        val retainedAdapter = if (registeredAdapter.id != sourceId)
            AliasedMangaSource(registeredAdapter, sourceId) else registeredAdapter
        retainedAdapter.attachVerificationManager(verificationManager)
        registry.register(retainedAdapter)

        val approved = validationResult.status == SourceStatus.SUPPORTED
        val entity = SourceEntity(
            id = sourceId,
            name = finalName,
            domain = validationResult.normalizedDomain,
            baseUrl = retainedAdapter.baseUrl,
            language = "ar",
            iconUrl = null,
            iconText = registeredAdapter.iconText,
            iconColorHex = registeredAdapter.iconColorHex,
            isEnabled = true,
            isSupported = approved,
            status = validationResult.status.name,
            version = 1,
            lastCheckedAt = System.currentTimeMillis()
        )
        dao.upsertSource(entity)

        // Pre-populate catalog into local DB so Unified Library / Recommendations / Search work immediately
        if (approved || validationResult.status == SourceStatus.PARTIALLY_SUPPORTED) {
            runCatching { refreshSourceCatalog(sourceId, 1) }
        }
        return Result.success(entity.toDomain())
    }

    suspend fun toggleSourceEnabled(sourceId: String, enabled: Boolean) {
        val old = dao.getSourceById(sourceId) ?: return
        val status = if (enabled) {
            if (old.status == SourceStatus.DISABLED.name) SourceStatus.NEEDS_CONFIGURATION.name else old.status
        } else SourceStatus.DISABLED.name
        dao.updateSourceEnabled(sourceId, enabled, status)
    }

    suspend fun removeSource(sourceId: String) {
        dao.deleteSourceConfiguration(sourceId)
        dao.deleteSource(sourceId)
        registry.unregister(sourceId)
    }

    // ─── CATALOG & DETAILS ───
    fun observeMangaBySource(sourceId: String): Flow<List<MangaSummary>> =
        combine(dao.observeMangaBySource(sourceId), preferences.settingsFlow) { list, settings ->
            list.filter { settings.adultContentEnabled || it.contentRating != ContentRating.ADULT_18_PLUS.name }
                .map { it.toSummary() }
        }

    suspend fun getMangaBySourceOnce(sourceId: String): List<MangaSummary> {
        val allowed = preferences.settingsFlow.first()
        return dao.getMangaBySourceOnce(sourceId)
            .filter { allowed.adultContentEnabled || it.contentRating != ContentRating.ADULT_18_PLUS.name }
            .map { it.toSummary() }
    }

    val storedMangaFlow: Flow<List<MangaSummary>> =
        combine(dao.observeStoredManga(), preferences.settingsFlow) { list, settings ->
            list.filter { settings.adultContentEnabled || it.contentRating != ContentRating.ADULT_18_PLUS.name }
                .map { it.toSummary() }
        }

    val allMangaFlow: Flow<List<MangaSummary>> =
        combine(dao.observeAllManga(), preferences.settingsFlow) { list, settings ->
            list.filter { settings.adultContentEnabled || it.contentRating != ContentRating.ADULT_18_PLUS.name }
                .map { it.toSummary() }
        }

    /**
     * Parses and persists manga items captured directly from an authenticated/verified WebView DOM.
     */
    suspend fun ingestVerifiedWebViewHtml(
        sourceId: String,
        html: String,
        baseUrl: String
    ): List<MangaSummary> {
        val connector = resolveConnector(sourceId) ?: return emptyList()
        val parsed = connector.parseVerifiedHtmlSnapshot(html, baseUrl)
            .filter { it.title.isNotBlank() && it.mangaUrl.isNotBlank() }
            .map { it.copy(sourceId = sourceId) }
        if (parsed.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val entities = parsed.mapIndexed { idx, summary ->
            val existing = dao.getMangaById(summary.id)
            MangaEntity(
                id = summary.id,
                sourceId = sourceId,
                remoteId = summary.mangaUrl,
                title = summary.title.trim(),
                coverUrl = summary.coverUrl?.takeIf { it.isNotBlank() } ?: existing?.coverUrl,
                description = existing?.description.orEmpty(),
                status = summary.status ?: existing?.status,
                author = existing?.author,
                artist = existing?.artist,
                contentRating = ContentPolicy.classify(summary.genres, summary.contentRating).name,
                genresCsv = if (summary.genres.isNotEmpty()) {
                    summary.genres.joinToString(",")
                } else {
                    existing?.genresCsv.orEmpty()
                },
                rating = summary.rating ?: existing?.rating,
                latestChapter = summary.latestChapter ?: existing?.latestChapter,
                isSavedInStorage = existing?.isSavedInStorage ?: false,
                updatedAt = now - idx * 10L
            )
        }
        dao.upsertMangaList(entities)
        verificationManager.markRequiresWebViewFallback(sourceId, connector.domain, false)
        // Never return an unfiltered preview if all parsed works are blocked by rating settings.
        return getMangaBySourceOnce(sourceId)
    }

    /**
     * Fetches popular/latest summaries for [sourceId] from the registered [MangaSource] connector,
     * logs every pipeline stage via [PipelineLogger], upserts the results into Room, and returns
     * the full list of [MangaSummary] items for immediate ViewModel state update.
     */
    suspend fun refreshSourceCatalog(sourceId: String, page: Int = 1): Result<List<MangaSummary>> {
        val connector = resolveConnector(sourceId)
        if (connector == null) {
            PipelineLogger.log(
                stage = PipelineStage.SOURCE_FOUND_IN_REGISTRY,
                sourceId = sourceId,
                sourceName = sourceId,
                resultCount = 0,
                status = "FAILED",
                failureReason = "Source '$sourceId' not found in SourceRegistry"
            )
            return Result.failure(IllegalStateException("المصدر ($sourceId) غير مسجل في النظام"))
        }

        connector.attachVerificationManager(verificationManager)

        PipelineLogger.log(
            stage = PipelineStage.SOURCE_FOUND_IN_REGISTRY,
            sourceId = sourceId,
            sourceName = connector.name,
            resultCount = 1,
            status = "FOUND",
            details = "connectorId=${connector.id} domain=${connector.domain}"
        )

        if (connector.requiresCloudflareVerification && !validator.isDomainHumanVerified(connector.domain)) {
            val currentVerificationState = verificationManager.getState(connector.domain)
            if (currentVerificationState == VerificationState.NOT_REQUIRED) {
                verificationManager.markVerificationRequired(
                    sourceId = sourceId,
                    domain = connector.domain,
                    challengeType = VerificationChallengeType.CLOUDFLARE_CHALLENGE,
                    reasonAr = "يتطلب هذا الموقع إكمال التحقق الأمني"
                )
            }
            PipelineLogger.log(
                stage = PipelineStage.GET_POPULAR_CALLED,
                sourceId = sourceId,
                sourceName = connector.name,
                resultCount = 0,
                status = "BLOCKED_CLOUDFLARE",
                failureReason = "SECURITY_VERIFICATION_REQUIRED (${verificationManager.getState(connector.domain)}) for ${connector.domain}"
            )
            return Result.failure(
                VerificationRequiredException(
                    sourceId = sourceId,
                    domain = connector.domain,
                    targetUrl = connector.baseUrl,
                    detection = VerificationDetectionResult(
                        isVerificationRequired = true,
                        challengeType = VerificationChallengeType.CLOUDFLARE_CHALLENGE,
                        httpCode = 403,
                        reasonAr = verificationManager.getState(connector.domain).labelAr
                    )
                )
            )
        }

        return try {
            PipelineLogger.log(
                stage = PipelineStage.GET_POPULAR_CALLED,
                sourceId = sourceId,
                sourceName = connector.name,
                status = "CALLED",
                details = "page=$page"
            )

            val result = connector.getPopular(page)
            val validItems = result.items
                .filter { it.title.isNotBlank() && it.mangaUrl.isNotBlank() }
                .map { it.copy(sourceId = sourceId) }

            PipelineLogger.log(
                stage = PipelineStage.MANGA_RESULTS_RECEIVED,
                sourceId = sourceId,
                sourceName = connector.name,
                resultCount = validItems.size,
                status = if (validItems.isNotEmpty()) "SUCCESS" else "EMPTY",
                failureReason = if (validItems.isEmpty()) "getPopular($page) returned 0 valid items" else null
            )

            val now = System.currentTimeMillis()
            val entities = validItems.mapIndexed { idx, summary ->
                val existing = dao.getMangaById(summary.id)
                MangaEntity(
                    id = summary.id,
                    sourceId = sourceId,
                    remoteId = summary.mangaUrl,
                    title = summary.title.trim(),
                    coverUrl = summary.coverUrl?.takeIf { it.isNotBlank() } ?: existing?.coverUrl,
                    description = existing?.description.orEmpty(),
                    status = summary.status ?: existing?.status,
                    author = existing?.author,
                    artist = existing?.artist,
                    contentRating = ContentPolicy.classify(summary.genres, summary.contentRating).name,
                    genresCsv = if (summary.genres.isNotEmpty()) {
                        summary.genres.joinToString(",")
                    } else {
                        existing?.genresCsv.orEmpty()
                    },
                    rating = summary.rating ?: existing?.rating,
                    latestChapter = summary.latestChapter ?: existing?.latestChapter,
                    isSavedInStorage = existing?.isSavedInStorage ?: false,
                    updatedAt = now - idx * 10L
                )
            }

            if (entities.isNotEmpty()) {
                dao.upsertMangaList(entities)
            }

            val allStoredForSource = getMangaBySourceOnce(sourceId)
            // Do not bypass content filters when the database has no visible works.
            val finalItems = allStoredForSource

            PipelineLogger.log(
                stage = PipelineStage.REPOSITORY_UPDATED,
                sourceId = sourceId,
                sourceName = connector.name,
                resultCount = finalItems.size,
                status = if (finalItems.isNotEmpty()) "UPSERTED" else "EMPTY",
                details = "newlyUpserted=${entities.size} totalInDb=${allStoredForSource.size}"
            )

            if (finalItems.isEmpty()) {
                if (verificationManager.isVerified(connector.domain)) {
                    verificationManager.markRequiresWebViewFallback(sourceId, connector.domain, true)
                    Result.failure(IllegalStateException("NO_CONTENT_AFTER_VERIFICATION"))
                } else {
                    Result.failure(IllegalStateException("لم يُعثر على أي أعمال من المصدر ${connector.name}"))
                }
            } else {
                verificationManager.markRequiresWebViewFallback(sourceId, connector.domain, false)
                Result.success(finalItems)
            }
        } catch (e: VerificationRequiredException) {
            PipelineLogger.log(
                stage = PipelineStage.REPOSITORY_UPDATED,
                sourceId = sourceId,
                sourceName = connector.name,
                resultCount = 0,
                status = "VERIFICATION_REQUIRED",
                failureReason = e.detection.reasonAr,
                throwable = e
            )
            Result.failure(e)
        } catch (e: Exception) {
            val cached = getMangaBySourceOnce(sourceId)
            PipelineLogger.log(
                stage = PipelineStage.REPOSITORY_UPDATED,
                sourceId = sourceId,
                sourceName = connector.name,
                resultCount = cached.size,
                status = if (cached.isNotEmpty()) "CACHED_FALLBACK" else "FAILED",
                failureReason = "${e.javaClass.simpleName}: ${e.message}",
                throwable = e
            )
            if (cached.isNotEmpty()) {
                Result.success(cached)
            } else {
                Result.failure(e)
            }
        }
    }

    suspend fun loadMangaDetailsAndSyncChapters(mangaId: String): Result<MangaDetails> {
        val mangaEntity = dao.getMangaById(mangaId)
            ?: return Result.failure(IllegalStateException("لم يتم العثور على المانهوا"))
        if (mangaEntity.contentRating == ContentRating.ADULT_18_PLUS.name &&
            !preferences.settingsFlow.first().adultContentEnabled) {
            return Result.failure(IllegalStateException("محتوى البالغين معطل في الإعدادات"))
        }
        val connector = resolveConnector(mangaEntity.sourceId)

        return try {
            if (connector == null) {
                val cachedChapters = dao.getChaptersByMangaOnce(mangaId).map { it.toDomain() }
                return Result.success(mangaEntity.toDetails(cachedChapters))
            }

            val remoteDetails = connector.getDetails(mangaEntity.remoteId)

            val updatedEntity = mangaEntity.copy(
                title = remoteDetails.title.ifBlank { mangaEntity.title },
                coverUrl = remoteDetails.coverUrl?.takeIf { it.isNotBlank() } ?: mangaEntity.coverUrl,
                description = remoteDetails.description.ifBlank { mangaEntity.description },
                status = remoteDetails.status?.takeIf { it.isNotBlank() } ?: mangaEntity.status,
                author = remoteDetails.author ?: mangaEntity.author,
                artist = remoteDetails.artist ?: mangaEntity.artist,
                contentRating = ContentPolicy.classify(remoteDetails.genres,
                    runCatching { ContentRating.valueOf(mangaEntity.contentRating) }.getOrDefault(ContentRating.GENERAL)).name,
                genresCsv = if (remoteDetails.genres.isNotEmpty()) {
                    remoteDetails.genres.joinToString(",")
                } else {
                    mangaEntity.genresCsv
                },
                rating = remoteDetails.rating ?: mangaEntity.rating,
                latestChapter = remoteDetails.chapters.maxByOrNull { it.chapterNumber ?: 0f }?.let { "فصل ${it.title}" }
                    ?: mangaEntity.latestChapter,
                updatedAt = System.currentTimeMillis()
            )
            dao.upsertManga(updatedEntity)

            val chapterEntities = remoteDetails.chapters.map { ch ->
                ChapterEntity(
                    id = ch.id,
                    mangaId = mangaId,
                    sourceId = mangaEntity.sourceId,
                    remoteId = ch.remoteId,
                    title = ch.title,
                    chapterNumber = ch.chapterNumber,
                    uploadDate = ch.uploadDate,
                    dateLabel = ch.dateLabel,
                    isRead = ch.isRead,
                    isDownloaded = ch.isDownloaded
                )
            }
            if (chapterEntities.isNotEmpty()) {
                dao.syncChaptersPreservingState(mangaId, chapterEntities)
            }

            val updatedChapters = dao.getChaptersByMangaOnce(mangaId).map { it.toDomain() }
            Result.success(
                remoteDetails.copy(
                    id = mangaId,
                    sourceId = mangaEntity.sourceId,
                    coverUrl = updatedEntity.coverUrl,
                    description = updatedEntity.description,
                    chapters = updatedChapters
                )
            )
        } catch (e: Exception) {
            val cachedChapters = dao.getChaptersByMangaOnce(mangaId).map { it.toDomain() }
            if (cachedChapters.isNotEmpty() || mangaEntity.description.isNotBlank()) {
                Result.success(mangaEntity.toDetails(cachedChapters))
            } else {
                Result.failure(e)
            }
        }
    }

    fun observeChaptersForManga(mangaId: String): Flow<List<MangaChapter>> {
        return dao.observeChaptersByManga(mangaId).map { list ->
            list.map { it.toDomain() }
        }
    }

    fun observeMangaEntity(mangaId: String): Flow<MangaEntity?> = dao.observeMangaById(mangaId)

    // ─── READER & OFFLINE PAGES ───
    suspend fun getReaderPages(chapterId: String): Result<List<MangaPage>> {
        val chapter = dao.getChapterById(chapterId)
            ?: return Result.failure(IllegalStateException("الفصل غير موجود"))
        val manga = dao.getMangaById(chapter.mangaId)
        val connector = resolveConnector(chapter.sourceId)

        // Check offline downloaded files first
        val cachedPages = dao.getPagesForChapter(chapterId)
        if (cachedPages.isNotEmpty() && cachedPages.all { !it.localPath.isNullOrBlank() && File(it.localPath).exists() }) {
            return Result.success(
                cachedPages.map {
                    MangaPage(
                        index = it.pageIndex,
                        imageUrl = it.imageUrl,
                        localPath = it.localPath,
                        chapterTitle = "الفصل ${chapter.title}",
                        dialoguePreviewAr = it.dialoguePreviewAr
                    )
                }
            )
        }

        val remotePages = connector?.getPages(chapter.remoteId).orEmpty()
        if (remotePages.isEmpty()) {
            return Result.failure(IllegalStateException("CHAPTER_EMPTY"))
        }

        val resolvedPages = remotePages.map { p ->
            val fileRes = storage.downloadAndSavePage(
                sourceId = chapter.sourceId,
                mangaId = chapter.mangaId,
                chapterNumber = chapter.chapterNumber,
                pageIndex = p.index,
                imageUrl = p.imageUrl,
                mangaTitle = manga?.title ?: p.chapterTitle,
                chapterTitle = "الفصل ${chapter.title}",
                dialogueAr = p.dialoguePreviewAr,
                refererUrl = connector?.baseUrl
            )
            val path = fileRes.getOrNull()?.absolutePath
            MangaPage(
                index = p.index,
                imageUrl = p.imageUrl,
                localPath = path,
                chapterTitle = "الفصل ${chapter.title}",
                dialoguePreviewAr = p.dialoguePreviewAr
            )
        }

        dao.upsertPages(
            resolvedPages.map { p ->
                PageEntity(
                    id = "${chapterId}_p_${p.index}",
                    chapterId = chapterId,
                    pageIndex = p.index,
                    imageUrl = p.imageUrl,
                    localPath = p.localPath,
                    dialoguePreviewAr = p.dialoguePreviewAr
                )
            }
        )
        return Result.success(resolvedPages)
    }

    suspend fun saveReadingProgress(
        mangaId: String,
        chapterId: String,
        chapterTitle: String,
        pageIndex: Int,
        scrollOffset: Int,
        totalPages: Int
    ) {
        dao.updateChapterReadProgress(
            chapterId = chapterId,
            isRead = true,
            lastPage = pageIndex + 1
        )
        dao.upsertReadingHistory(
            ReadingHistoryEntity(
                id = "hist_$mangaId",
                mangaId = mangaId,
                chapterId = chapterId,
                chapterTitle = chapterTitle,
                pageIndex = pageIndex + 1,
                scrollOffset = scrollOffset,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun observeHistoryForManga(mangaId: String): Flow<ReadingHistoryEntity?> =
        dao.observeHistoryForManga(mangaId)

    val readingHistoryWithMangaFlow: Flow<List<Pair<ReadingHistoryEntity, MangaSummary>>> =
        combine(dao.observeReadingHistory(), allMangaFlow) { histList, mangaList ->
            val byId = mangaList.associateBy { it.id }
            histList.mapNotNull { h ->
                val m = byId[h.mangaId] ?: return@mapNotNull null
                h to m
            }
        }

    suspend fun clearReadingHistory() = dao.clearReadingHistory()

    // ─── FAVORITES ───
    val favoritesWithCategoryFlow: Flow<List<Pair<FavoriteEntity, MangaSummary>>> =
        combine(dao.observeFavorites(), allMangaFlow) { favs, mangaList ->
            val byId = mangaList.associateBy { it.id }
            favs.mapNotNull { f ->
                val m = byId[f.mangaId] ?: return@mapNotNull null
                f to m
            }
        }

    fun observeIsFavorite(mangaId: String): Flow<Boolean> = dao.observeIsFavorite(mangaId)

    suspend fun toggleFavorite(mangaId: String, isCurrentlyFav: Boolean, category: String = "الكل") {
        if (isCurrentlyFav) {
            dao.removeFavorite(mangaId)
        } else {
            dao.addFavorite(FavoriteEntity(mangaId = mangaId, category = category))
        }
    }

    // ─── DOWNLOADS & STORAGE ───
    val downloadTasksFlow: Flow<List<DownloadTask>> = dao.observeDownloadTasks().map { list ->
        list.map {
            DownloadTask(
                id = it.id,
                chapterId = it.chapterId,
                mangaId = it.mangaId,
                mangaTitle = it.mangaTitle,
                chapterTitle = it.chapterTitle,
                coverUrl = it.coverUrl,
                totalPages = it.totalPages,
                downloadedPages = it.downloadedPages,
                failedPages = it.failedPages,
                status = runCatching { DownloadStatus.valueOf(it.status) }.getOrDefault(DownloadStatus.WAITING),
                createdAt = it.createdAt
            )
        }
    }

    suspend fun downloadSingleChapter(mangaId: String, chapterId: String) {
        val manga = dao.getMangaById(mangaId) ?: return
        if (manga.contentRating == ContentRating.ADULT_18_PLUS.name &&
            !preferences.settingsFlow.first().adultContentEnabled) return
        val chapter = dao.getChapterById(chapterId) ?: return
        downloadEngine.enqueueChapterDownload(
            mangaId = mangaId,
            mangaTitle = manga.title,
            coverUrl = manga.coverUrl,
            chapter = chapter
        )
    }

    suspend fun downloadChapterBatch(mangaId: String, chapters: List<MangaChapter>) {
        val manga = dao.getMangaById(mangaId) ?: return
        if (manga.contentRating == ContentRating.ADULT_18_PLUS.name &&
            !preferences.settingsFlow.first().adultContentEnabled) return
        val dbChapters = dao.getChaptersByMangaOnce(mangaId).associateBy { it.id }
        for (ch in chapters) {
            val entity = dbChapters[ch.id] ?: continue
            downloadEngine.enqueueChapterDownload(
                mangaId = mangaId,
                mangaTitle = manga.title,
                coverUrl = manga.coverUrl,
                chapter = entity
            )
        }
    }

    suspend fun pauseDownload(taskId: String) = downloadEngine.pauseTask(taskId)
    suspend fun resumeDownload(taskId: String) = downloadEngine.resumeTask(taskId)
    suspend fun cancelDownload(taskId: String) = downloadEngine.cancelTask(taskId)
    suspend fun pauseAllDownloads() = downloadEngine.pauseAllActive()
    suspend fun cancelAllDownloads() = downloadEngine.cancelAllActive()
    suspend fun clearDownloadHistory() = downloadEngine.clearHistory()

    suspend fun removeMangaFromStorage(mangaId: String) {
        val manga = dao.getMangaById(mangaId) ?: return
        val chapters = dao.getChaptersByMangaOnce(mangaId)
        for (ch in chapters) {
            storage.deleteChapterFiles(manga.sourceId, mangaId, ch.chapterNumber)
            dao.updateChapterDownloaded(ch.id, false)
        }
        dao.setMangaSavedInStorage(mangaId, false)
    }

    suspend fun getMangaStorageSizeMb(mangaId: String): Double {
        val manga = dao.getMangaById(mangaId) ?: return 0.0
        return storage.getMangaStorageSizeMb(manga.sourceId, mangaId)
    }

    suspend fun getStorageStats(): StorageStats = storage.computeStorageStats()
    suspend fun clearImageCache(): Long = storage.clearCache()

    private fun SourceEntity.toDomain() = SourceInfo(
        id = id,
        name = name,
        domain = domain,
        baseUrl = baseUrl,
        language = language,
        iconText = iconText,
        iconColorHex = iconColorHex,
        isEnabled = isEnabled,
        status = runCatching { SourceStatus.valueOf(status) }.getOrDefault(SourceStatus.NEEDS_CONFIGURATION),
        version = version,
        lastCheckedAt = lastCheckedAt
    )

    private fun MangaEntity.toSummary() = MangaSummary(
        id = id,
        sourceId = sourceId,
        title = title,
        coverUrl = coverUrl,
        mangaUrl = remoteId,
        latestChapter = latestChapter,
        genres = genresCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        rating = rating,
        status = status,
        contentRating = runCatching { ContentRating.valueOf(contentRating) }.getOrDefault(ContentRating.GENERAL)
    )

    private fun MangaEntity.toDetails(chapters: List<MangaChapter>) = MangaDetails(
        id = id,
        sourceId = sourceId,
        title = title,
        alternativeTitles = emptyList(),
        coverUrl = coverUrl,
        description = description,
        author = author,
        artist = artist,
        status = status,
        genres = genresCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        rating = rating,
        chapters = chapters
    )

    private fun ChapterEntity.toDomain() = MangaChapter(
        id = id,
        mangaId = mangaId,
        sourceId = sourceId,
        remoteId = remoteId,
        title = title,
        chapterNumber = chapterNumber,
        uploadDate = uploadDate,
        dateLabel = dateLabel,
        isRead = isRead,
        isDownloaded = isDownloaded,
        lastReadPage = lastReadPage
    )
}
