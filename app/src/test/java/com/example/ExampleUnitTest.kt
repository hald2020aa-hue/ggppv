package com.example

import com.example.core.common.PipelineLogger
import com.example.core.common.PipelineStage
import com.example.core.common.isSafeRedirectDestination
import com.example.core.common.normalizeDomain
import com.example.core.verification.VerificationChallengeType
import com.example.core.verification.VerificationDetector
import com.example.core.verification.VerificationManager
import com.example.core.verification.VerificationRepository
import com.example.core.verification.VerificationRequiredException
import com.example.core.verification.VerificationSessionManager
import com.example.core.verification.VerificationState
import com.example.domain.models.DownloadStatus
import com.example.domain.models.DownloadTask
import com.example.domain.models.SourceStatus
import com.example.sourceapi.HtmlImageExtractor
import com.example.sourceapi.SourceRegistry
import com.example.sourceapi.SourceValidator
import com.example.sources.AzoraMangaSource
import com.example.sources.MangaDexApiHelper
import com.example.sources.MangaDexSource
import com.example.sources.MangalekSource
import com.example.sources.MangatekSource
import com.example.sources.TeamXSource
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun normalizeDomain_cleansAndExtractsValidHost() {
        assertEquals("mangalik.net", normalizeDomain("https://www.mangalik.net/manga/murim/?page=2"))
        assertEquals("olympustaff.com", normalizeDomain("  olympustaff.com/chapters/1  "))
        assertEquals("mangatek.com", normalizeDomain("HTTPS://MANGATEK.COM/"))
    }

    @Test
    fun normalizeDomain_upgradesHttpLinkToHttpsHost() {
        assertEquals("mangalik.net", normalizeDomain("http://mangalik.net"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeDomain_rejectsLocalhostAndInternalNetworks() {
        normalizeDomain("https://192.168.1.10/admin")
    }

    @Test
    fun redirectValidation_allowsSubdomainAndBlocksCrossOrigin() {
        assertTrue(isSafeRedirectDestination("mangalik.net", "https://cdn.mangalik.net/img/01.webp"))
        assertFalse(isSafeRedirectDestination("mangalik.net", "https://malicious-site.org/login"))
    }

    @Test
    fun htmlImageExtractor_supportsLazyAttributesCssBackgroundAndArabicPaths() {
        val sampleHtml = """
            <div class="reading-content">
                <img class="wp-manga-chapter-img" data-src="/uploads/مانهوا القمة/001.webp" src="data:image/gif;base64,R0lGOD" />
                <img class="wp-manga-chapter-img" data-lazy-src="https://cdn.mangalik.net/ch1/002.webp" />
                <img class="wp-manga-chapter-img" data-original="//cdn.mangalik.net/ch1/003.webp" />
                <img class="wp-manga-chapter-img" srcset="https://cdn.mangalik.net/ch1/004.webp 1x, https://cdn.mangalik.net/ch1/004_hd.webp 2x" />
                <div class="cover-box" style="background-image: url('/covers/hero_cover.jpg');"></div>
            </div>
        """.trimIndent()

        val pages = HtmlImageExtractor.extractChapterPagesFromHtml(sampleHtml, "https://mangalik.net")
        assertEquals(4, pages.size)
        assertTrue(pages[0].imageUrl.startsWith("https://mangalik.net/uploads/"))
        assertEquals("https://cdn.mangalik.net/ch1/002.webp", pages[1].imageUrl)
        assertEquals("https://cdn.mangalik.net/ch1/003.webp", pages[2].imageUrl)
        assertEquals("https://cdn.mangalik.net/ch1/004.webp", pages[3].imageUrl)

        val doc = Jsoup.parse(sampleHtml, "https://mangalik.net")
        val bgCover = HtmlImageExtractor.extractImageUrl(doc.selectFirst(".cover-box"), "https://mangalik.net")
        assertEquals("https://mangalik.net/covers/hero_cover.jpg", bgCover)
    }

    @Test
    fun htmlParser_extractsMadaraAndMangaStreamItemsAndDetailsAccurately() {
        val madaraHtml = """
            <div class="page-item-detail">
                <div class="item-thumb">
                    <a href="/manga/solo-leveling/"><img data-src="/uploads/covers/solo.webp" /></a>
                </div>
                <div class="item-summary">
                    <div class="post-title"><h3><a href="/manga/solo-leveling/">Solo Leveling</a></h3></div>
                    <div class="list-chapter"><div class="chapter-item"><span class="chapter"><a href="/manga/solo-leveling/ch-200/">الفصل 200</a></span></div></div>
                </div>
            </div>
        """.trimIndent()

        val mangalek = MangalekSource()
        val madaraParsed = mangalek.parseMangaSummariesFromHtml(madaraHtml, "https://mangalik.net")
        assertEquals(1, madaraParsed.size)
        assertEquals("Solo Leveling", madaraParsed[0].title)
        assertEquals("https://mangalik.net/manga/solo-leveling/", madaraParsed[0].mangaUrl)
        assertEquals("https://mangalik.net/uploads/covers/solo.webp", madaraParsed[0].coverUrl)
        assertEquals("الفصل 200", madaraParsed[0].latestChapter)

        // Verify MangaStream / Team-X structure where .tt is a child <div> inside <a href="...">
        val mangaStreamHtml = """
            <div class="bsx">
                <a href="https://olympustaff.com/series/return-of-mount-hua/">
                    <div class="limit"><img data-lazy-src="/covers/mount_hua.jpg" /></div>
                    <div class="bigor">
                        <div class="tt">Return of the Mount Hua Sect</div>
                        <div class="adds"><div class="epxs">الفصل 115</div></div>
                    </div>
                </a>
            </div>
        """.trimIndent()

        val teamX = TeamXSource()
        val streamParsed = teamX.parseMangaSummariesFromHtml(mangaStreamHtml, "https://olympustaff.com")
        assertEquals(1, streamParsed.size)
        assertEquals("Return of the Mount Hua Sect", streamParsed[0].title)
        assertEquals("https://olympustaff.com/series/return-of-mount-hua/", streamParsed[0].mangaUrl)
        assertEquals("https://olympustaff.com/covers/mount_hua.jpg", streamParsed[0].coverUrl)
        assertEquals("الفصل 115", streamParsed[0].latestChapter)
    }

    @Test
    fun mangaDexApiHelper_parsesRealJsonV5CatalogDetailsChaptersAndPages() {
        val sampleCatalogJson = """
            {
              "result": "ok",
              "data": [
                {
                  "id": "a77742b1-befd-49a4-bff5-1ad4e6b0ef7b",
                  "type": "manga",
                  "attributes": {
                    "title": { "en": "Omniscient Reader's Viewpoint" },
                    "altTitles": [{ "ar": "وجهة نظر القارئ الشامل" }],
                    "description": { "en": "Kim Dokja is the sole reader of a web novel." },
                    "status": "ongoing",
                    "lastChapter": "220",
                    "tags": [
                      { "attributes": { "name": { "en": "Action" } } },
                      { "attributes": { "name": { "en": "Fantasy" } } }
                    ]
                  },
                  "relationships": [
                    {
                      "id": "cov-1",
                      "type": "cover_art",
                      "attributes": { "fileName": "cover_hash_01.jpg" }
                    },
                    {
                      "id": "auth-1",
                      "type": "author",
                      "attributes": { "name": "Sing N Song" }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val items = MangaDexApiHelper.parseMangaListResponse(sampleCatalogJson, "mangadex")
        assertEquals(1, items.size)
        assertEquals("Omniscient Reader's Viewpoint", items[0].title)
        assertEquals(
            "https://uploads.mangadex.org/covers/a77742b1-befd-49a4-bff5-1ad4e6b0ef7b/cover_hash_01.jpg.512.jpg",
            items[0].coverUrl
        )
        assertTrue(items[0].genres.contains("اكشن"))
        assertTrue(items[0].genres.contains("فانتازيا"))

        val sampleFeedJson = """
            {
              "result": "ok",
              "data": [
                {
                  "id": "c1111111-2222-3333-4444-555555555555",
                  "type": "chapter",
                  "attributes": {
                    "chapter": "1",
                    "title": "Prologue",
                    "translatedLanguage": "ar",
                    "publishAt": "2026-10-01T12:00:00+00:00",
                    "pages": 15
                  }
                }
              ]
            }
        """.trimIndent()
        val chapters = MangaDexApiHelper.parseChaptersFeedResponse(sampleFeedJson, items[0].id, "mangadex")
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
        assertEquals("https://api.mangadex.org/at-home/server/c1111111-2222-3333-4444-555555555555", chapters[0].remoteId)

        val sampleAtHomeJson = """
            {
              "result": "ok",
              "baseUrl": "https://uploads.mangadex.org",
              "chapter": {
                "hash": "abc123hash",
                "data": ["1.jpg", "2.jpg"],
                "dataSaver": ["1-saver.jpg", "2-saver.jpg"]
              }
            }
        """.trimIndent()
        val pages = MangaDexApiHelper.parseAtHomePagesResponse(sampleAtHomeJson)
        assertEquals(2, pages.size)
        assertEquals("https://uploads.mangadex.org/data-saver/abc123hash/1-saver.jpg", pages[0].imageUrl)
    }

    @Ignore("Requires real external network and must be run as an explicit integration test")
    @Test
    fun sourceValidator_distinguishesSupportedUnsupportedAndCloudflareSources() = runBlocking {
        val registry = SourceRegistry(
            listOf(
                MangalekSource(),
                TeamXSource(),
                MangatekSource(requiresVerificationOverride = true)
            )
        )
        val validator = SourceValidator(registry)

        // 1. Unsupported source (random-unregistered.com)
        val unsupportedRes = validator.validateDomainCompatibility("random-unregistered.com").getOrThrow()
        assertEquals(SourceStatus.UNSUPPORTED, unsupportedRes.status)

        // 2. Cloudflare source before human verification (mangatek.com)
        val beforeVerify = validator.validateDomainCompatibility("mangatek.com").getOrThrow()
        assertEquals(SourceStatus.REQUIRES_VERIFICATION, beforeVerify.status)
    }

    // =========================================================================
    // UNIVERSAL WEBSITE VERIFICATION SYSTEM — 10 REQUIRED SCENARIOS (SECTION 9)
    // =========================================================================

    @Test
    fun scenario1_siteNotRequiringVerification_andEmptyPageAreNotMisclassified() {
        var nowMs = 1_000_000L
        val sessionManager = VerificationSessionManager(clock = { nowMs })
        val repo = VerificationRepository(sessionManager)
        val manager = VerificationManager(sessionManager = sessionManager, repository = repo)

        // Normal manga catalog HTML (even if it mentions Cloudflare analytics in footer)
        val normalMangaHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>مانجا ليك - القائمة الرئيسية</title></head>
            <body>
                <div class="page-item-detail">
                    <div class="post-title"><h3><a href="/manga/solo-leveling/">Solo Leveling</a></h3></div>
                </div>
                <div class="page-item-detail">
                    <div class="post-title"><h3><a href="/manga/omniscient-reader/">Omniscient Reader</a></h3></div>
                </div>
                <footer>Protected by Cloudflare Analytics</footer>
            </body>
            </html>
        """.trimIndent()

        val result = manager.inspectHttpResponse(
            sourceId = "mangalek",
            domain = "mangalik.net",
            html = normalMangaHtml,
            httpCode = 200
        )
        assertFalse("Normal manga page must not require verification", result.isVerificationRequired)
        assertEquals(VerificationChallengeType.NONE, result.challengeType)
        assertEquals(VerificationState.NOT_REQUIRED, manager.getState("mangalik.net"))

        // Empty page must NOT be misclassified as a security verification page
        val emptyPageResult = VerificationDetector.detect(html = "   ", httpCode = 200)
        assertFalse("Empty page must not be classified as verification challenge", emptyPageResult.isVerificationRequired)
    }

    @Test
    fun scenario2_and_scenario10_siteRequiringVerification_andCloudflareChallengeDetection() {
        val sessionManager = VerificationSessionManager()
        val repo = VerificationRepository(sessionManager)
        val manager = VerificationManager(sessionManager = sessionManager, repository = repo)

        // 1. Cloudflare Turnstile Challenge (as seen in reference video for mangatek.com)
        val cloudflareChallengeHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>Just a moment...</title></head>
            <body>
                <div id="challenge-running">Verifying you are human. This may take a few seconds.</div>
                <div class="cf-turnstile" data-sitekey="0x4AAAAAA"></div>
                <script src="/cdn-cgi/challenge-platform/h/g/orchestrate/chl_page/v1"></script>
            </body>
            </html>
        """.trimIndent()

        val cfDetection = manager.inspectHttpResponse(
            sourceId = "mangatek",
            domain = "mangatek.com",
            html = cloudflareChallengeHtml,
            httpCode = 403,
            headers = mapOf("cf-mitigated" to "challenge")
        )
        assertTrue("Cloudflare challenge page must be detected", cfDetection.isVerificationRequired)
        assertEquals(VerificationChallengeType.CLOUDFLARE_CHALLENGE, cfDetection.challengeType)
        assertEquals(VerificationState.REQUIRED, manager.getState("mangatek.com"))
        assertFalse(manager.isVerified("mangatek.com"))

        // 2. Non-Cloudflare Generic Human CAPTCHA Gate (proving detector does not rely solely on Cloudflare name)
        val customCaptchaHtml = """
            <html>
            <head><title>تحقق من أنك إنسان - فحص الأمان</title></head>
            <body>
                <form action="/verify-human">
                    <div class="g-recaptcha" data-sitekey="abc"></div>
                    <p>يرجى إكمال التحقق الأمني للمتابعة إلى الموقع</p>
                </form>
            </body>
            </html>
        """.trimIndent()
        val captchaDetection = VerificationDetector.detect(customCaptchaHtml, httpCode = 403)
        assertTrue("Non-Cloudflare human verification gate must also be detected", captchaDetection.isVerificationRequired)
        assertEquals(VerificationChallengeType.HUMAN_VERIFICATION, captchaDetection.challengeType)

        // 3. Browser Integrity / Security Waiting Room
        val waitingRoomHtml = """
            <html>
            <head><title>Checking your browser before accessing site</title></head>
            <body>
                <div id="ddos-guard">Please wait while we check your browser before accessing...</div>
            </body>
            </html>
        """.trimIndent()
        val waitingRoomDetection = VerificationDetector.detect(waitingRoomHtml, httpCode = 503)
        assertTrue("Browser integrity / waiting room must be detected", waitingRoomDetection.isVerificationRequired)
    }

    @Test
    fun scenario3_and_scenario8_verificationSuccess_andReenteringSourceDuringValidSession() = runBlocking {
        var nowMs = 5_000_000L
        val sessionManager = VerificationSessionManager(defaultSessionTtlMs = 30 * 60 * 1000L, clock = { nowMs })
        val repo = VerificationRepository(sessionManager)
        val manager = VerificationManager(sessionManager = sessionManager, repository = repo)

        val mangatek = MangatekSource(requiresVerificationOverride = true)
        mangatek.attachVerificationManager(manager)

        // Initially requires verification -> getPopular throws VerificationRequiredException
        manager.markVerificationRequired("mangatek", "mangatek.com")
        assertEquals(VerificationState.REQUIRED, manager.getState("mangatek.com"))

        var threwVerificationRequired = false
        try {
            mangatek.getPopular(1)
        } catch (e: VerificationRequiredException) {
            threwVerificationRequired = true
            assertEquals("mangatek", e.sourceId)
        }
        assertTrue("Unverified source must throw VerificationRequiredException before verification", threwVerificationRequired)

        // Start verification in WebView -> IN_PROGRESS
        manager.startVerification("mangatek", "mangatek.com")
        assertEquals(VerificationState.IN_PROGRESS, manager.getState("mangatek.com"))

        // Complete verification with real verified HTML from WebView -> COMPLETED
        val verifiedMangatekHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>مانجاتك - Mangatek</title></head>
            <body>
                <div class="page-item-detail">
                    <div class="item-thumb">
                        <a href="https://mangatek.com/manga/nano-machine/"><img src="https://mangatek.com/covers/nano.webp" /></a>
                    </div>
                    <div class="post-title"><h3><a href="https://mangatek.com/manga/nano-machine/">Nano Machine</a></h3></div>
                    <div class="chapter-item"><span class="chapter"><a href="https://mangatek.com/manga/nano-machine/180/">الفصل 180</a></span></div>
                </div>
            </body>
            </html>
        """.trimIndent()

        assertTrue(VerificationDetector.isNormalVerifiedPage(verifiedMangatekHtml, 200, "مانجاتك - Mangatek"))
        val session = manager.completeVerification("mangatek", "mangatek.com", verifiedMangatekHtml, "مانجاتك - Mangatek")
        assertEquals(VerificationState.COMPLETED, session.state)
        assertEquals(VerificationState.COMPLETED, manager.getState("mangatek.com"))
        assertTrue(manager.isVerified("mangatek.com"))

        // Scenario 8: Re-entering the source 10 minutes later while session is still valid
        nowMs += 10 * 60 * 1000L
        assertEquals(VerificationState.COMPLETED, manager.getState("mangatek.com"))
        assertTrue("Valid session must remain verified without re-prompting", manager.isVerified("mangatek.com"))

        val pageAfterVerify = mangatek.getPopular(1)
        assertTrue("Mangatek must return extracted manga after verification", pageAfterVerify.items.isNotEmpty())
        assertEquals("Nano Machine", pageAfterVerify.items.first().title)
    }

    @Test
    fun scenario4_verificationCancelledByUser() {
        val sessionManager = VerificationSessionManager()
        val repo = VerificationRepository(sessionManager)
        val manager = VerificationManager(sessionManager = sessionManager, repository = repo)

        manager.markVerificationRequired("mangatek", "mangatek.com")
        manager.startVerification("mangatek", "mangatek.com")
        assertEquals(VerificationState.IN_PROGRESS, manager.getState("mangatek.com"))

        manager.cancelVerification("mangatek", "mangatek.com")
        assertEquals(VerificationState.CANCELLED, manager.getState("mangatek.com"))
        assertFalse("Cancelled verification must not be treated as verified", manager.isVerified("mangatek.com"))
    }

    @Test
    fun scenario5_sessionExpiration_transitionsFromCompletedToExpired() {
        var nowMs = 10_000_000L
        val ttlMs = 30 * 60 * 1000L // 30 minutes
        val sessionManager = VerificationSessionManager(defaultSessionTtlMs = ttlMs, clock = { nowMs })
        val repo = VerificationRepository(sessionManager)
        val manager = VerificationManager(sessionManager = sessionManager, repository = repo)

        val validHtml = """
            <html><head><title>Mangatek Home</title></head>
            <body><div class="page-item-detail"><a href="/manga/1">Work 1</a></div></body></html>
        """.trimIndent()

        manager.completeVerification("mangatek", "mangatek.com", validHtml)
        assertEquals(VerificationState.COMPLETED, manager.getState("mangatek.com"))
        assertTrue(manager.isVerified("mangatek.com"))

        // Advance clock past TTL (31 minutes later)
        nowMs += 31 * 60 * 1000L
        assertEquals(VerificationState.EXPIRED, manager.getState("mangatek.com"))
        assertFalse("Session must not be valid after TTL expires", manager.isVerified("mangatek.com"))
    }

    @Test
    fun scenario6_connectionFailureDuringVerification() {
        val sessionManager = VerificationSessionManager()
        val repo = VerificationRepository(sessionManager)
        val manager = VerificationManager(sessionManager = sessionManager, repository = repo)

        manager.startVerification("mangatek", "mangatek.com")
        assertEquals(VerificationState.IN_PROGRESS, manager.getState("mangatek.com"))

        manager.failVerification("mangatek", "mangatek.com", "فشل الاتصال أثناء التحقق الأمني.")
        assertEquals(VerificationState.FAILED, manager.getState("mangatek.com"))
        assertFalse(manager.isVerified("mangatek.com"))
    }

    @Test
    fun scenario7_reopeningApp_restoresPersistedSessionMetadataWithoutSensitiveTokens() {
        var nowMs = 20_000_000L
        val firstRunSessionManager = VerificationSessionManager(defaultSessionTtlMs = 30 * 60 * 1000L, clock = { nowMs })
        val validHtml = """
            <html><head><title>Mangatek</title></head>
            <body><div class="page-item-detail"><a href="/manga/a">Manga A</a></div></body></html>
        """.trimIndent()
        firstRunSessionManager.updateSession(
            sourceId = "mangatek",
            domain = "mangatek.com",
            state = VerificationState.COMPLETED,
            verifiedHtml = validHtml
        )

        val serialized = firstRunSessionManager.serializeMetadata()
        assertTrue("Serialized metadata must contain sourceId", serialized.contains("mangatek"))
        assertFalse("Serialized metadata must NEVER store HTML or cookies", serialized.contains("Manga A"))

        // Simulate reopening app 5 minutes later with a fresh VerificationSessionManager instance
        nowMs += 5 * 60 * 1000L
        val secondRunSessionManager = VerificationSessionManager(defaultSessionTtlMs = 30 * 60 * 1000L, clock = { nowMs })
        secondRunSessionManager.restoreFromMetadata(serialized)

        assertEquals(
            VerificationState.COMPLETED,
            secondRunSessionManager.getState("mangatek.com")
        )
        assertTrue(secondRunSessionManager.isSessionValid("mangatek.com"))
    }

    @Ignore("Requires real external network and must be run as an explicit integration test")
    @Test
    fun allBuiltInSources_liveNetworkAndPipelineVerification() = runBlocking {
        PipelineLogger.clear()
        val sources = listOf(
            MangaDexSource(),
            MangalekSource(),
            TeamXSource(),
            MangatekSource(),
            AzoraMangaSource()
        )

        for (source in sources) {
            PipelineLogger.log(
                stage = PipelineStage.SOURCE_SELECTED,
                sourceId = source.id,
                sourceName = source.name,
                status = "TEST_SELECT"
            )
            val catalog = source.getPopular(1)
            assertTrue("Catalog for ${source.id} (${source.name}) must not be empty", catalog.items.isNotEmpty())

            val first = catalog.items.first()
            assertTrue("Title for ${source.id} must not be blank", first.title.isNotBlank())
            assertNotNull("Cover URL for ${source.id} must not be null", first.coverUrl)
            assertTrue("Cover URL for ${source.id} must start with https://", first.coverUrl!!.startsWith("https://"))

            val logs = PipelineLogger.getLogsForSource(source.id)
            assertTrue("Logs for ${source.id} must include NETWORK_REQUEST_STARTED", logs.any { it.stage == PipelineStage.NETWORK_REQUEST_STARTED })
            assertTrue("Logs for ${source.id} must include NETWORK_RESPONSE_RECEIVED", logs.any { it.stage == PipelineStage.NETWORK_RESPONSE_RECEIVED })
            assertTrue("Logs for ${source.id} must include PARSER_STARTED", logs.any { it.stage == PipelineStage.PARSER_STARTED })
            assertTrue("Logs for ${source.id} must include PARSER_COMPLETED", logs.any { it.stage == PipelineStage.PARSER_COMPLETED })
        }

        // Also test full end-to-end details -> chapters -> reader pages on the primary MangaDex work
        val mdSource = MangaDexSource()
        val popular = mdSource.getPopular(1)
        val firstWork = popular.items.first()
        val details = mdSource.getDetails(firstWork.mangaUrl)
        assertTrue("Details title must not be blank", details.title.isNotBlank())
        assertTrue("Chapters must not be empty", details.chapters.isNotEmpty())

        val firstChapter = details.chapters.first()
        val pages = mdSource.getPages(firstChapter.remoteId)
        assertTrue("Reader pages must not be empty", pages.isNotEmpty())
        assertTrue("Reader page image URL must start with https://", pages.first().imageUrl.startsWith("https://"))
    }

    @Test
    fun downloadTask_calculatesProgressCorrectly() {
        val task = DownloadTask(
            id = "dl_1",
            chapterId = "ch_1",
            mangaId = "m_1",
            mangaTitle = "Solo Leveling",
            chapterTitle = "الفصل 1",
            coverUrl = null,
            totalPages = 100,
            downloadedPages = 48,
            failedPages = 0,
            status = DownloadStatus.DOWNLOADING
        )
        assertEquals(48, task.progress)
    }

    @Ignore("Requires real external network and must be run as an explicit integration test")
    @Test
    fun manualSiteOnboarding_supportsAnyDomainAndRtlUrlCleaning() = runBlocking {
        // 1. Test RTL copy-paste URL cleaning (/https://starzmanga.com)
        assertEquals("starzmanga.com", normalizeDomain("/https://starzmanga.com"))
        assertEquals("starzmanga.com", normalizeDomain("  /https://starzmanga.com/  "))
        assertEquals("starzmanga.com", normalizeDomain("http://starzmanga.com/manga/"))

        // 2. Test empty SourceRegistry (0 pre-installed sites) + dynamic manual addition of starzmanga.com
        val emptyRegistry = SourceRegistry(emptyList())
        assertTrue(emptyRegistry.getAllRegistered().isEmpty())

        val validator = SourceValidator(emptyRegistry)
        val validationRes = validator.validateDomainCompatibility("/https://starzmanga.com")
        assertTrue("Manual domain validation must succeed", validationRes.isSuccess)

        val step = validationRes.getOrThrow()
        assertEquals("starzmanga.com", step.normalizedDomain)
        assertNotNull("Dynamic adapter must be created and matched", step.matchedSource)
        assertTrue("Registry must now contain the newly added site", emptyRegistry.getAllRegistered().isNotEmpty())
    }
}

