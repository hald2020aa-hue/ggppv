package com.example.sourceapi

import com.example.core.common.isSafeRedirectDestination
import com.example.core.common.normalizeDomain
import com.example.core.verification.VerificationManager
import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaDetails
import com.example.domain.models.MangaPage
import com.example.domain.models.MangaSummary
import com.example.domain.models.PageResult
import com.example.domain.models.SourceDiagnosticReport
import com.example.domain.models.SourceStatus
import com.example.sources.ConfigurableHtmlMangaSource
import com.example.sources.DynamicSiteSource
import com.example.sources.cleanExtractedMangaTitle
import com.example.sources.parseNormalizedMangaStatus
import com.example.sources.parseRatingFromDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/** Stored as JSON in Room; selectors are site-specific and never executable script. */
data class SourceConfiguration(
    val domain: String,
    val sourceId: String = "site_${domain.replace('.', '_').replace('-', '_')}",
    val name: String = domain,
    val catalogPath: String = "/manga/",
    val searchPath: String = "/?s={query}&post_type=wp-manga",
    val itemSelector: String = "div.page-item-detail, div.bsx",
    val titleSelector: String = "h3 a, .post-title a",
    val coverSelector: String = ".item-thumb, img",
    val detailLinkSelector: String = "a[href]",
    val descriptionSelector: String = ".summary__content, .entry-content p",
    val genreSelector: String = ".genres-content a, .mgen a",
    val statusSelector: String = ".post-status .summary-content",
    val chapterSelector: String = "li.wp-manga-chapter, #chapterlist li",
    val chapterLinkSelector: String = "a[href]",
    val readerImageSelector: String = ".reading-content img, #readerarea img",
    val nextPageSelector: String = "a.next, a[rel=next]",
    val connectorType: String = "HTML_JSOUP"
) {
    init {
        require(normalizeDomain(domain) == domain) { "Invalid source domain" }
        require(listOf(itemSelector, titleSelector, coverSelector, descriptionSelector,
            genreSelector, statusSelector, chapterSelector, readerImageSelector).all { it.length <= 512 })
        // Paths may temporarily be empty while the user edits Compose TextFields.
        // validatePaths() is called before network access or persistence.
    }

    fun validatePaths() {
        require(catalogPath.startsWith("/") && !catalogPath.startsWith("//")) { "Catalog path must be relative" }
        require(searchPath.startsWith("/") && !searchPath.startsWith("//")) { "Search path must be relative" }
        require(connectorType == "HTML_JSOUP") { "This connector type requires a dedicated implementation" }
    }

    fun toJson(): String = JSONObject().apply {
        put("domain", domain); put("sourceId", sourceId); put("name", name)
        put("catalogPath", catalogPath); put("searchPath", searchPath)
        put("itemSelector", itemSelector); put("titleSelector", titleSelector)
        put("coverSelector", coverSelector); put("detailLinkSelector", detailLinkSelector)
        put("descriptionSelector", descriptionSelector); put("genreSelector", genreSelector)
        put("statusSelector", statusSelector); put("chapterSelector", chapterSelector)
        put("chapterLinkSelector", chapterLinkSelector)
        put("readerImageSelector", readerImageSelector); put("nextPageSelector", nextPageSelector)
        put("connectorType", connectorType)
    }.toString()

    companion object {
        fun fromJson(json: String): SourceConfiguration {
            val obj = JSONObject(json)
            val domain = normalizeDomain(obj.getString("domain"))
            val defaults = SourceConfiguration(domain)
            fun str(key: String, fallback: String): String = obj.optString(key, fallback)
            return defaults.copy(
                sourceId = str("sourceId", defaults.sourceId), name = str("name", defaults.name),
                catalogPath = str("catalogPath", defaults.catalogPath), searchPath = str("searchPath", defaults.searchPath),
                itemSelector = str("itemSelector", defaults.itemSelector), titleSelector = str("titleSelector", defaults.titleSelector),
                coverSelector = str("coverSelector", defaults.coverSelector), detailLinkSelector = str("detailLinkSelector", defaults.detailLinkSelector),
                descriptionSelector = str("descriptionSelector", defaults.descriptionSelector), genreSelector = str("genreSelector", defaults.genreSelector),
                statusSelector = str("statusSelector", defaults.statusSelector), chapterSelector = str("chapterSelector", defaults.chapterSelector),
                chapterLinkSelector = str("chapterLinkSelector", defaults.chapterLinkSelector),
                readerImageSelector = str("readerImageSelector", defaults.readerImageSelector),
                nextPageSelector = str("nextPageSelector", defaults.nextPageSelector), connectorType = str("connectorType", defaults.connectorType)
            )
        }
    }
}

/** Configurable source that reuses the existing reader, details, chapter and network pipeline. */
class DynamicSourceAdapter(val configuration: SourceConfiguration) : ConfigurableHtmlMangaSource(
    id = configuration.sourceId,
    name = configuration.name,
    domain = configuration.domain,
    iconText = configuration.name.take(1).uppercase(),
    iconColorHex = DynamicSiteSource.computeColorForDomain(configuration.domain),
    itemSelector = configuration.itemSelector,
    titleSelector = configuration.titleSelector,
    coverSelector = configuration.coverSelector,
    latestChapterSelector = configuration.chapterSelector,
    detailsTitleSelector = "h1.entry-title, h1.post-title, h1",
    detailsDescSelector = configuration.descriptionSelector,
    detailsGenresSelector = configuration.genreSelector,
    detailsAuthorSelector = ".author-content a",
    detailsStatusSelector = configuration.statusSelector,
    chapterRowSelector = configuration.chapterSelector,
    readerImagesSelector = configuration.readerImageSelector
) {
    override fun buildPopularUrl(page: Int, hostBaseUrl: String): String {
        configuration.validatePaths()
        val path = configuration.catalogPath.replace("{page}", page.toString())
        return hostBaseUrl + if (page > 1 && !path.contains("page=" ) && !configuration.catalogPath.contains("{page}")) {
            path.trimEnd('/') + "/page/$page/"
        } else path
    }

    override fun buildSearchUrl(query: String, page: Int): String {
        configuration.validatePaths()
        return baseUrl + configuration.searchPath
            .replace("{query}", java.net.URLEncoder.encode(query, "UTF-8"))
            .replace("{page}", page.toString())
    }

    private fun readAllowedHtml(url: String): String {
        require(url.startsWith("https://") && isSafeRedirectDestination(configuration.domain, url)) {
            "Only same-site HTTPS URLs are allowed for dynamic source extraction"
        }
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", VerificationManager.getUserAgent())
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            .header("Referer", "https://${configuration.domain}/")
        VerificationManager.getCookieHeader(url)?.let { cookie ->
            reqBuilder.header("Cookie", cookie)
        }
        httpClient.newBuilder().followRedirects(false).build().newCall(reqBuilder.build()).execute().use { result ->
            if (result.isRedirect) throw IllegalStateException("Redirect requires explicit review")
            val body = result.body?.string().orEmpty()
            val challenge = detectSecurityChallenge(body, result.code, result.headers.toMap())
            if (challenge.isVerificationRequired) {
                throw com.example.core.verification.VerificationRequiredException(
                    sourceId = id, domain = domain, targetUrl = url, detection = challenge
                )
            }
            if (!result.isSuccessful) throw IllegalStateException("HTTP ${result.code} from $domain")
            if (!result.header("Content-Type").orEmpty().contains("html", ignoreCase = true)) {
                throw IllegalStateException("Not an HTML document")
            }
            return body
        }
    }

    override suspend fun getPopular(page: Int): PageResult<MangaSummary> = withContext(Dispatchers.IO) {
        val url = buildPopularUrl(page)
        val items = parseCatalog(readAllowedHtml(url), url)
        PageResult(items, page, items.isNotEmpty())
    }

    override suspend fun getLatestUpdates(page: Int): PageResult<MangaSummary> = getPopular(page)

    override suspend fun search(query: String, page: Int): PageResult<MangaSummary> = withContext(Dispatchers.IO) {
        val url = buildSearchUrl(query, page)
        val items = parseCatalog(readAllowedHtml(url), url)
        PageResult(items, page, items.isNotEmpty())
    }

    override suspend fun getDetails(mangaUrl: String): MangaDetails = withContext(Dispatchers.IO) {
        val resolvedUrl = resolveWorkUrl(mangaUrl)
        parseDetails(readAllowedHtml(resolvedUrl), resolvedUrl)
            ?: throw IllegalStateException("No manga details matched configured selectors")
    }

    override suspend fun getChapters(mangaUrl: String): List<MangaChapter> = withContext(Dispatchers.IO) {
        val resolvedUrl = resolveWorkUrl(mangaUrl)
        val html = readAllowedHtml(resolvedUrl)
        parseChapters(html, "${id}_${resolvedUrl.hashCode().toUInt()}", resolvedUrl)
    }

    override suspend fun getPages(chapterUrl: String): List<MangaPage> = withContext(Dispatchers.IO) {
        parseReaderPages(readAllowedHtml(chapterUrl), chapterUrl)
    }

    private fun resolveWorkUrl(mangaIdOrUrl: String): String {
        if (mangaIdOrUrl.startsWith("https://")) return mangaIdOrUrl
        sharedUrlById[mangaIdOrUrl]?.let { return it }
        val slug = mangaIdOrUrl.removePrefix("${id}_")
        val basePath = configuration.catalogPath.trimEnd('/')
        return "https://${configuration.domain}$basePath/$slug/"
    }

    /** Selector-specific parser, without silently falling back to unrelated site layouts. */
    override fun parseCatalog(html: String, pageBaseUri: String): List<MangaSummary> {
        val doc = Jsoup.parse(html, pageBaseUri)
        return doc.select(configuration.itemSelector).mapNotNull { node ->
            val titleNode = node.selectFirst(configuration.titleSelector) ?: return@mapNotNull null
            val link = node.selectFirst(configuration.detailLinkSelector) ?: titleNode
            val href = HtmlImageExtractor.resolveUrl(pageBaseUri, link.attr("href")) ?: return@mapNotNull null
            if (!isSafeRedirectDestination(configuration.domain, href)) return@mapNotNull null
            val rawTitle = titleNode.text().trim().ifBlank { titleNode.attr("title").trim() }
            val title = cleanExtractedMangaTitle(rawTitle)
            if (title.isBlank()) return@mapNotNull null
            val cover = HtmlImageExtractor.extractImageUrl(node.selectFirst(configuration.coverSelector), pageBaseUri)
            val summaryId = "${id}_${href.hashCode().toUInt()}"
            sharedUrlById[summaryId] = href
            MangaSummary(
                id = summaryId,
                sourceId = id,
                title = title,
                coverUrl = cover,
                mangaUrl = href,
                genres = node.select(configuration.genreSelector).map { it.text().trim() }.filter { it.isNotBlank() }
            )
        }.distinctBy { it.mangaUrl }
    }

    override fun parseVerifiedHtmlSnapshot(html: String, pageBaseUri: String): List<MangaSummary> =
        parseCatalog(html, pageBaseUri)

    override fun parseReaderPages(html: String, chapterUrl: String): List<MangaPage> =
        HtmlImageExtractor.extractChapterPagesFromHtml(html, chapterUrl, configuration.readerImageSelector)

    override fun parseChapters(html: String, mangaId: String, mangaUrl: String): List<MangaChapter> {
        val doc = Jsoup.parse(html, mangaUrl)
        return doc.select(configuration.chapterSelector).mapIndexedNotNull { index, row ->
            val link = row.selectFirst(configuration.chapterLinkSelector) ?: return@mapIndexedNotNull null
            val url = HtmlImageExtractor.resolveUrl(mangaUrl, link.attr("href")) ?: return@mapIndexedNotNull null
            if (!isSafeRedirectDestination(configuration.domain, url)) return@mapIndexedNotNull null
            MangaChapter(
                id = "${id}_${url.hashCode().toUInt()}",
                mangaId = mangaId,
                sourceId = id,
                remoteId = url,
                title = link.text().trim().ifBlank { "الفصل ${index + 1}" },
                chapterNumber = null,
                uploadDate = null
            )
        }.distinctBy { it.remoteId }
    }

    override fun parseDetails(html: String, mangaUrl: String): MangaDetails? {
        val document = Jsoup.parse(html, mangaUrl)
        val title = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val mangaId = "${id}_${mangaUrl.hashCode().toUInt()}"
        sharedUrlById[mangaId] = mangaUrl
        val statusRaw = document.select(configuration.statusSelector).text().trim()
        val parsedStatus = parseNormalizedMangaStatus(statusRaw)
        val parsedRating = parseRatingFromDoc(document)
        return MangaDetails(
            id = mangaId,
            sourceId = id,
            title = cleanExtractedMangaTitle(title),
            alternativeTitles = emptyList(),
            coverUrl = HtmlImageExtractor.extractImageUrl(document.selectFirst(configuration.coverSelector), mangaUrl),
            description = document.select(configuration.descriptionSelector).text().trim(),
            author = document.selectFirst(".author-content a")?.text()?.trim()?.takeIf { it.isNotBlank() },
            artist = document.selectFirst(".artist-content a")?.text()?.trim()?.takeIf { it.isNotBlank() },
            status = parsedStatus,
            genres = document.select(configuration.genreSelector).map { it.text().trim() }.filter { it.isNotBlank() },
            rating = parsedRating,
            chapters = parseChapters(html, mangaId, mangaUrl)
        )
    }

    companion object {
        private val sharedUrlById = java.util.concurrent.ConcurrentHashMap<String, String>()
    }
}

data class SourceDiscoveryReport(
    val domain: String,
    val httpCode: Int?,
    val contentType: String,
    val architecture: String,
    val requiresVerification: Boolean,
    val rssAvailable: Boolean,
    val javascriptHeavy: Boolean,
    val paginationDetected: Boolean,
    val sampleCatalogLinks: Int,
    val robotsDisallowsRoot: Boolean?,
    val notes: String
)

/** Passive public-page inspection. Never bypasses bot challenges or executes untrusted JS. */
class SourceDiscoveryEngine {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(9, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun inspect(domainInput: String): SourceDiscoveryReport = withContext(Dispatchers.IO) {
        val domain = normalizeDomain(domainInput)
        val url = "https://$domain/"
        try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", VerificationManager.getUserAgent())
                .header("Accept", "text/html,application/json;q=0.9")
            VerificationManager.getCookieHeader(url)?.let { reqBuilder.header("Cookie", it) }
            client.newCall(reqBuilder.build())
                .execute().use { response ->
                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                    val html = response.body?.string()?.take(400_000).orEmpty()
                    val doc = Jsoup.parse(html, url)
                    val verification = com.example.core.verification.VerificationDetector.detect(html, response.code, response.headers.toMap())
                    val json = contentType.contains("json") || html.trimStart().startsWith("{")
                    val javascriptHeavy = doc.select("#__next, #__nuxt, #app, script[type=module]").isNotEmpty() &&
                        doc.select("a[href*=/manga/], a[href*=/series/]").isEmpty()
                    val redirect = response.header("Location")
                    val safeRedirect = redirect?.let { HtmlImageExtractor.resolveUrl(url, it) }
                        ?.let { isSafeRedirectDestination(domain, it) } ?: false
                    SourceDiscoveryReport(domain, response.code, contentType,
                        if (json) "JSON" else if (contentType.contains("html")) "HTML" else "UNKNOWN",
                        verification.isVerificationRequired,
                        doc.select("link[type*=rss], link[type*=atom]").isNotEmpty(),
                        javascriptHeavy, doc.select("a[rel=next], a.next, .pagination").isNotEmpty(),
                        doc.select("a[href*=/manga/], a[href*=/series/]").size,
                        robotsDisallowsRoot = null, // Unknown: robots and terms require separate review.
                        notes = when {
                            response.isRedirect && !safeRedirect -> "Redirect requires manual review"
                            verification.isVerificationRequired -> "Human verification in WebView required"
                            javascriptHeavy -> "JavaScript rendering may require WebView"
                            json -> "JSON response; API schema needs documented integration"
                            else -> "Public-page structure only; extraction permissions not established"
                        }
                    )
                }
        } catch (e: Exception) {
            SourceDiscoveryReport(domain, null, "", "UNKNOWN", false, false, false,
                false, 0, null, "Network error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

class SourceAdapterFactory(private val registry: SourceRegistry) {
    fun create(config: SourceConfiguration): MangaSource {
        config.validatePaths()
        val registered = registry.findByDomain(config.domain)
        val specialist = (registered as? AliasedMangaSource)?.original ?: registered
        return if (specialist != null && specialist !is DynamicSiteSource && specialist !is DynamicSourceAdapter) {
            specialist
        } else DynamicSourceAdapter(config)
    }
}

class SourceAdapterManager(private val registry: SourceRegistry, private val factory: SourceAdapterFactory) {
    /** Preview and validation must not mutate the active registry. */
    fun candidate(config: SourceConfiguration): MangaSource = factory.create(config)
    fun configure(config: SourceConfiguration): MangaSource = candidate(config).also { registry.register(it) }
    fun find(idOrDomain: String): MangaSource? = registry.findById(idOrDomain) ?: registry.findByDomain(idOrDomain)
}

class SourceTestRunner(private val checker: SourceHealthChecker) {
    suspend fun run(source: MangaSource): SourceDiagnosticReport = checker.checkSourceHealth(source)
}

class UniversalSourceEngine(
    registry: SourceRegistry,
    verificationManager: VerificationManager
) {
    val discovery = SourceDiscoveryEngine()
    val adapters = SourceAdapterManager(registry, SourceAdapterFactory(registry))
    val tests = SourceTestRunner(SourceHealthChecker(verificationManager))
}

/** Allows saved IDs and custom user-chosen source names to wrap their original specialized adapter without losing DB associations or replacing the connector. */
class AliasedMangaSource(
    val original: MangaSource,
    override val id: String,
    override val name: String = original.name
) : MangaSource by original {
    override fun parseVerifiedHtmlSnapshot(html: String, pageBaseUri: String): List<MangaSummary> =
        original.parseVerifiedHtmlSnapshot(html, pageBaseUri).map { it.copy(sourceId = id) }

    override suspend fun getPopular(page: Int): PageResult<MangaSummary> =
        original.getPopular(page).let { result -> result.copy(items = result.items.map { it.copy(sourceId = id) }) }

    override suspend fun getLatestUpdates(page: Int): PageResult<MangaSummary> =
        original.getLatestUpdates(page).let { result -> result.copy(items = result.items.map { it.copy(sourceId = id) }) }

    override suspend fun search(query: String, page: Int): PageResult<MangaSummary> =
        original.search(query, page).let { result -> result.copy(items = result.items.map { it.copy(sourceId = id) }) }

    override suspend fun getDetails(mangaUrl: String): MangaDetails =
        original.getDetails(mangaUrl).let { it.copy(sourceId = id, chapters = it.chapters.map { ch -> ch.copy(sourceId = id) }) }

    override suspend fun getChapters(mangaUrl: String): List<MangaChapter> =
        original.getChapters(mangaUrl).map { it.copy(sourceId = id) }
}
