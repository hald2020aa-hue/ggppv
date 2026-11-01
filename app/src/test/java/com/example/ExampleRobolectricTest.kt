package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.ChapterEntity
import com.example.core.database.MangaDatabase
import com.example.core.database.MangaEntity
import com.example.core.database.ReadingHistoryEntity
import com.example.core.database.SourceEntity
import com.example.core.verification.VerificationChallengeType
import com.example.core.verification.VerificationDetectionResult
import com.example.core.verification.VerificationManager
import com.example.core.verification.VerificationRepository
import com.example.core.verification.VerificationRequiredException
import com.example.core.verification.VerificationSessionManager
import com.example.core.verification.VerificationState
import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaDetails
import com.example.domain.models.MangaPage
import com.example.domain.models.MangaSummary
import com.example.domain.models.PageResult
import com.example.sourceapi.MangaSource
import com.example.sourceapi.SourceManager
import com.example.sourceapi.SourceRegistry
import com.example.sourceapi.SourceValidator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: MangaDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `read string from context`() {
        val appName = context.getString(R.string.app_name)
        assertEquals("Manga Reader", appName)
    }

    @Test
    fun `room database prevents duplicate chapters and preserves reading state`() = runTest {
        val dao = db.mangaDao()

        val source = SourceEntity(
            id = "mangalek",
            name = "مانجا ليك Mangalek",
            domain = "mangalik.net",
            baseUrl = "https://mangalik.net",
            language = "ar",
            iconUrl = null,
            isEnabled = true,
            isSupported = true,
            version = 1,
            lastCheckedAt = 1000L
        )
        dao.upsertSource(source)

        val manga = MangaEntity(
            id = "mangalek_murim",
            sourceId = "mangalek",
            remoteId = "https://mangalik.net/manga/murim/",
            title = "Murim Psychopath",
            coverUrl = null,
            description = "قصة موريم",
            status = "OnGoing",
            author = "Tigers",
            artist = "Kirose"
        )
        dao.upsertManga(manga)

        val ch1 = ChapterEntity(
            id = "mangalek_murim_ch1",
            mangaId = "mangalek_murim",
            sourceId = "mangalek",
            remoteId = "ch_1",
            title = "1",
            chapterNumber = 1f,
            uploadDate = 1000L,
            isRead = true,
            isDownloaded = true,
            lastReadPage = 14
        )
        dao.upsertChapters(listOf(ch1))

        // Syncing updated chapter list from remote should preserve isRead, isDownloaded, and lastReadPage
        val incomingCh1 = ch1.copy(isRead = false, isDownloaded = false, lastReadPage = 0)
        val incomingCh2 = ChapterEntity(
            id = "mangalek_murim_ch2",
            mangaId = "mangalek_murim",
            sourceId = "mangalek",
            remoteId = "ch_2",
            title = "2",
            chapterNumber = 2f,
            uploadDate = 2000L
        )
        dao.syncChaptersPreservingState("mangalek_murim", listOf(incomingCh1, incomingCh2))

        val storedChapters = dao.getChaptersByMangaOnce("mangalek_murim")
        assertEquals(2, storedChapters.size)
        assertTrue(storedChapters[0].isRead)
        assertTrue(storedChapters[0].isDownloaded)
        assertEquals(14, storedChapters[0].lastReadPage)

        // Save and restore reading history
        dao.upsertReadingHistory(
            ReadingHistoryEntity(
                id = "hist_mangalek_murim",
                mangaId = "mangalek_murim",
                chapterId = "mangalek_murim_ch1",
                chapterTitle = "1",
                pageIndex = 14,
                scrollOffset = 320,
                updatedAt = 5000L
            )
        )
        val history = dao.observeHistoryForManga("mangalek_murim").first()
        assertEquals(14, history?.pageIndex)
        assertEquals(320, history?.scrollOffset)
    }

    @Ignore("Requires live site connectivity; not a deterministic unit test")
    @Test
    fun `mangaRepository starts with zero preinstalled sources and adds sites manually`() = runBlocking {
        val registry = SourceRegistry(emptyList())
        val validator = SourceValidator(registry)
        val storage = com.example.core.storage.LocalChapterStorage(context)
        val downloadEngine = com.example.core.downloads.DownloadEngine(context, db, storage, registry)
        val repository = com.example.data.repositories.MangaRepository(
            dao = db.mangaDao(),
            registry = registry,
            validator = validator,
            storage = storage,
            downloadEngine = downloadEngine,
            preferences = com.example.core.storage.AppPreferences(context)
        )

        com.example.core.common.PipelineLogger.clear()
        repository.ensureDefaultSourcesRegistered()

        // Must start with 0 pre-added sources in Room!
        val initialSourcesInDb = db.mangaDao().getAllSourcesOnce()
        assertEquals(0, initialSourcesInDb.size)

        // Now user manually validates and adds a site (e.g., mangalik.net)
        val validation = repository.validateSiteDomain("mangalik.net").getOrThrow()
        val addRes = repository.confirmAndAddSource(validation, "مانجا ليك")
        assertTrue("Manual site addition must succeed", addRes.isSuccess)

        val sourcesAfterManualAdd = db.mangaDao().getAllSourcesOnce()
        assertEquals(1, sourcesAfterManualAdd.size)
        assertEquals("mangalik.net", sourcesAfterManualAdd.first().domain)

        val storedInRoom = repository.getMangaBySourceOnce(sourcesAfterManualAdd.first().id)
        assertTrue("Room database must contain synced manga for manually added site", storedInRoom.isNotEmpty())
    }

    @Test
    fun `scenario9 and endToEnd repository verification flow separates verification success from extraction result`() = runBlocking {
        val sessionManager = VerificationSessionManager()
        val verificationRepository = VerificationRepository(sessionManager)
        val verificationManager = VerificationManager(
            sessionManager = sessionManager,
            repository = verificationRepository
        )

        // Source that requires verification, and returns 0 items when no HTML snapshot is provided
        val emptyAfterVerifySource = object : MangaSource {
            override val id: String = "protected_empty_source"
            override val name: String = "موقع محمي بدون أعمال مستخرجة"
            override val baseUrl: String = "https://protected-empty.example.com"
            override val domain: String = "protected-empty.example.com"
            override val iconText: String = "PE"
            override val iconColorHex: Long = 0xFF607D8B
            override val requiresCloudflareVerification: Boolean = true

            override suspend fun getPopular(page: Int): PageResult<MangaSummary> {
                if (!verificationManager.isVerified(domain)) {
                    throw VerificationRequiredException(
                        sourceId = id,
                        domain = domain,
                        targetUrl = baseUrl,
                        detection = VerificationDetectionResult(
                            isVerificationRequired = true,
                            challengeType = VerificationChallengeType.CLOUDFLARE_CHALLENGE,
                            httpCode = 403,
                            reasonAr = "يتطلب هذا الموقع إكمال التحقق الأمني."
                        )
                    )
                }
                return PageResult(items = emptyList(), page = page, hasNextPage = false)
            }

            override suspend fun search(query: String, page: Int): PageResult<MangaSummary> = getPopular(page)
            override suspend fun getDetails(mangaUrl: String): MangaDetails = throw UnsupportedOperationException()
            override suspend fun getChapters(mangaUrl: String): List<MangaChapter> = emptyList()
            override suspend fun getPages(chapterUrl: String): List<MangaPage> = emptyList()
        }

        val mangatekProtected = com.example.sources.MangatekSource(requiresVerificationOverride = true)
        val registry = SourceRegistry(listOf(mangatekProtected, emptyAfterVerifySource))
        val validator = SourceValidator(registry, verificationManager)
        val sourceManager = SourceManager(registry, validator, verificationManager)
        val storage = com.example.core.storage.LocalChapterStorage(context)
        val downloadEngine = com.example.core.downloads.DownloadEngine(context, db, storage, registry)
        val repository = com.example.data.repositories.MangaRepository(
            dao = db.mangaDao(),
            registry = registry,
            validator = validator,
            storage = storage,
            downloadEngine = downloadEngine,
            preferences = com.example.core.storage.AppPreferences(context),
            verificationManager = verificationManager,
            sourceManager = sourceManager
        )

        repository.ensureDefaultSourcesRegistered()

        // 1. Before verification: Mangatek returns failure with VerificationRequiredException
        val beforeVerifyResult = repository.refreshSourceCatalog("mangatek", 1)
        assertTrue(beforeVerifyResult.isFailure)
        assertTrue(beforeVerifyResult.exceptionOrNull() is VerificationRequiredException)
        assertEquals(
            VerificationState.REQUIRED,
            repository.getSourceVerificationState("mangatek")
        )

        // 2. Complete verification for Mangatek -> extraction succeeds and persists to Room
        val verifiedMangatekHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>مانجاتك - Mangatek</title></head>
            <body>
                <div class="page-item-detail">
                    <div class="item-thumb">
                        <a href="https://mangatek.com/manga/reaper-of-the-drifting-moon/">
                            <img src="https://mangatek.com/covers/reaper.webp" />
                        </a>
                    </div>
                    <div class="post-title">
                        <h3><a href="https://mangatek.com/manga/reaper-of-the-drifting-moon/">Reaper of the Drifting Moon</a></h3>
                    </div>
                    <div class="chapter-item"><span class="chapter">الفصل 95</span></div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val completedSession = verificationManager.completeVerification("mangatek", "mangatek.com", verifiedMangatekHtml)
        assertEquals(VerificationState.COMPLETED, completedSession.state)
        assertEquals(VerificationState.COMPLETED, repository.getSourceVerificationState("mangatek"))

        val afterVerifyResult = repository.refreshSourceCatalog("mangatek", 1)
        assertTrue("Mangatek refresh must succeed after verification", afterVerifyResult.isSuccess)
        assertEquals(1, afterVerifyResult.getOrThrow().size)
        assertEquals("Reaper of the Drifting Moon", afterVerifyResult.getOrThrow().first().title)

        // 3. Scenario 9: Separate verification success (COMPLETED) from manga extraction (0 items after verification)
        val validEmptySiteHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>موقع محمي - الرئيسية</title></head>
            <body><div class="site-container"><p>تم التحقق بنجاح ولكن المحتوى محمل بـ Canvas مخصص</p></div></body>
            </html>
        """.trimIndent()
        val emptySiteSession = verificationManager.completeVerification(
            "protected_empty_source",
            "protected-empty.example.com",
            validEmptySiteHtml
        )
        assertEquals("Verification itself must succeed", VerificationState.COMPLETED, emptySiteSession.state)
        assertEquals(
            VerificationState.COMPLETED,
            repository.getSourceVerificationState("protected_empty_source")
        )

        val emptyExtractionResult = repository.refreshSourceCatalog("protected_empty_source", 1)
        assertTrue("Extraction with 0 items must fail with NO_CONTENT_AFTER_VERIFICATION", emptyExtractionResult.isFailure)
        assertEquals(
            "NO_CONTENT_AFTER_VERIFICATION",
            emptyExtractionResult.exceptionOrNull()?.message
        )
        // Crucially, verification state remains COMPLETED (not reset to REQUIRED)
        assertEquals(
            VerificationState.COMPLETED,
            repository.getSourceVerificationState("protected_empty_source")
        )
        assertTrue(verificationManager.isVerified("protected-empty.example.com"))
    }
}
