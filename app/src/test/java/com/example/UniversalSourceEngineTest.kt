package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.common.ContentPolicy
import com.example.core.database.MangaDatabase
import com.example.domain.models.ContentRating
import com.example.domain.models.SourceStatus
import com.example.sourceapi.AliasedMangaSource
import com.example.sourceapi.DynamicSourceAdapter
import com.example.sourceapi.SourceAdapterFactory
import com.example.sourceapi.SourceConfiguration
import com.example.sourceapi.SourceRegistry
import com.example.sources.MangalekSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fixture-only tests: no claim of live site availability or successful APK rendering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UniversalSourceEngineTest {
    private val domain = "fictional-example.org"
    private val configuration = SourceConfiguration(
        domain = domain, sourceId = "site_fictional_example_org", name = "Test",
        catalogPath = "/catalog/", itemSelector = ".work",
        titleSelector = ".title", detailLinkSelector = "a.detail",
        coverSelector = "img.cover", genreSelector = ".genre",
        chapterSelector = "li.chapter", chapterLinkSelector = "a.chapter-link",
        readerImageSelector = "#reader img.page"
    )

    @Test fun jsonConfigurationRoundTripPreservesSelectors() {
        val result = SourceConfiguration.fromJson(configuration.toJson())
        assertEquals(configuration, result)
        assertEquals(domain, result.domain)
    }

    @Test fun parserUsesSiteSpecificSelectorsAndDoesNotMakeUpEntries() {
        val adapter = DynamicSourceAdapter(configuration)
        val html = """
            <div class="work"><a class="detail" href="/series/a"><span class="title">Story A</span></a>
                <img class="cover" data-src="/img/a.webp"><span class="genre">Fantasy</span></div>
            <div class="irrelevant"><a href="/series/b">Story B</a></div>
        """.trimIndent()
        val items = adapter.parseCatalog(html, "https://$domain/catalog/")
        assertEquals(1, items.size)
        assertEquals("Story A", items.single().title)
        assertEquals("https://$domain/series/a", items.single().mangaUrl)
        assertEquals("https://$domain/img/a.webp", items.single().coverUrl)
        assertEquals(listOf("Fantasy"), items.single().genres)
        assertTrue(adapter.parseCatalog("<h1>Nothing</h1>", "https://$domain/").isEmpty())
    }

    @Test fun parserGetsOnlyExplicitChapterLinksAndReaderImages() {
        val adapter = DynamicSourceAdapter(configuration)
        val mangaUrl = "https://$domain/series/a"
        val chapters = adapter.parseChapters(
            """<li class="chapter"><a class="chapter-link" href="/read/1">Chapter 1</a></li>""",
            "site_fictional_example_org_series_a", mangaUrl
        )
        assertEquals(1, chapters.size)
        assertEquals("https://$domain/read/1", chapters[0].remoteId)
        assertNull(chapters[0].uploadDate)
        assertTrue(adapter.parseChapters("<div>No chapters</div>", "id", mangaUrl).isEmpty())
        val pages = adapter.parseReaderPages(
            """<div id="reader"><img class="page" data-src="/img/001.webp"><img class="page" src="/img/002.png"></div>""",
            "https://$domain/read/1"
        )
        assertEquals(2, pages.size)
        assertEquals("https://$domain/img/001.webp", pages[0].imageUrl)
    }

    @Test fun specialistIsNotReplacedByDynamicConfig() {
        val original = MangalekSource()
        val registry = SourceRegistry(listOf(original))
        val config = SourceConfiguration(domain = original.domain, sourceId = original.id, name = "Renamed")
        assertSame(original, SourceAdapterFactory(registry).create(config))
        assertSame(original, registry.findByDomain(original.domain))
    }

    @Test fun renamedSourceAliasDoesNotRemoveOriginalBuiltIn() {
        val original = MangalekSource()
        val registry = SourceRegistry(listOf(original))
        registry.register(AliasedMangaSource(original, "custom_renamed"))
        assertSame(original, registry.findById(original.id))
        assertEquals("custom_renamed", registry.findByDomain(original.domain)?.id)
        registry.unregister("custom_renamed")
        assertSame(original, registry.findByDomain(original.domain))
    }

    @Test fun editorMayTemporarilyHoldEmptyPathButCannotUseItForExtraction() {
        val draft = configuration.copy(catalogPath = "")
        assertTrue(runCatching { draft.validatePaths() }.isFailure)
    }

    @Test fun explicitAgeTagsClassifyWithoutAssumingEveryWorkIsAdult() {
        assertEquals(ContentRating.ADULT_18_PLUS, ContentPolicy.classify(listOf("Adult")))
        assertEquals(ContentRating.MATURE, ContentPolicy.classify(listOf("Violence")))
        assertEquals(ContentRating.GENERAL, ContentPolicy.classify(listOf("Fantasy")))
        assertEquals(SourceStatus.PARTIALLY_SUPPORTED.name, "PARTIALLY_SUPPORTED")
    }

    @Test fun roomV3HasSourceConfigAndContentRatingWithoutDeletingExistingTables() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            database.openHelper.writableDatabase.query("PRAGMA table_info(manga)").use { cursor ->
                val names = mutableListOf<String>()
                while (cursor.moveToNext()) names.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                assertTrue(names.contains("contentRating"))
            }
            database.openHelper.writableDatabase.query("SELECT * FROM source_configs").use { cursor ->
                assertEquals(0, cursor.count)
            }
        } finally {
            database.close()
        }
    }

    @Test fun builtInSourceCatalogIncludesAllReferenceAndPreservedConnectors() {
        val sources = com.example.sources.BuiltInSourceCatalog.createAll()
        val ids = sources.map { it.id }.toSet()
        assertEquals(
            setOf(
                "mangadar",
                "mangalek",
                "azora",
                "mangatek",
                "procomic",
                "mangastarz",
                "teamx",
                "swatmanga",
                "mangadex"
            ),
            ids
        )
    }

    @Test fun cardIsolationPreventsTitleCoverAndLatestChapterContamination() {
        val mangatek = com.example.sources.MangatekSource()
        val html = """
            <div class="box">
                <div class="bs">
                    <div class="bsx">
                        <a href="https://mangatek.com/manga/magic-emperor">
                            <img src="https://mangatek.com/covers/magic-emperor.jpg" />
                            <div class="tt">1Magic emperor</div>
                        </a>
                        <div class="epxs"><a href="https://mangatek.com/manga/magic-emperor/chapter-1">Magic emperor الفصل 1</a></div>
                    </div>
                    <div class="bsx">
                        <a href="https://mangatek.com/manga/nano-machine">
                            <img src="https://mangatek.com/covers/nano-machine.jpg" />
                            <div class="tt">2Nano Machine الفصل 210</div>
                        </a>
                    </div>
                </div>
            </div>
        """.trimIndent()
        val parsed = mangatek.parseCatalog(html, "https://mangatek.com/")
        assertEquals(2, parsed.size)
        val first = parsed.first { it.id.contains("magic-emperor") }
        val second = parsed.first { it.id.contains("nano-machine") }

        assertEquals("Magic emperor", first.title)
        assertEquals("https://mangatek.com/covers/magic-emperor.jpg", first.coverUrl)
        assertEquals("Magic emperor الفصل 1", first.latestChapter)

        assertEquals("Nano Machine", second.title)
        assertEquals("https://mangatek.com/covers/nano-machine.jpg", second.coverUrl)
        // Must NOT inherit "Magic emperor الفصل 1" from sibling or parent wrapper!
        assertNull(second.latestChapter)
    }

    @Test fun statusAndRatingParsersNeverInventFakeDefaults() {
        val docWithoutMeta = org.jsoup.Jsoup.parse("<html><body><h1>Work Title</h1></body></html>")
        assertNull(com.example.sources.parseNormalizedMangaStatus(""))
        assertNull(com.example.sources.parseRatingFromDoc(docWithoutMeta))

        assertEquals("Completed", com.example.sources.parseNormalizedMangaStatus("مكتملة"))
        assertEquals("Hiatus", com.example.sources.parseNormalizedMangaStatus("متوقفة"))
        assertEquals("OnGoing", com.example.sources.parseNormalizedMangaStatus("مستمرة"))

        val ratedDoc = org.jsoup.Jsoup.parse(
            """<div class="post-total-rating"><span class="score">4.85</span></div>"""
        )
        assertEquals(4.85f, com.example.sources.parseRatingFromDoc(ratedDoc) ?: 0f, 0.01f)
    }

    @Test fun localChapterStorageValidatesRealRasterImageMagicBytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = com.example.core.storage.LocalChapterStorage(context)
        val htmlBytes = "<html><body>Cloudflare Challenge</body></html>".toByteArray()
        assertFalse(storage.isLikelyRasterImageBytes(htmlBytes))

        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
        )
        assertTrue(storage.isLikelyRasterImageBytes(pngHeader))
    }
}

