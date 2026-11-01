package com.example.sourceapi

import com.example.core.common.normalizeDomain
import com.example.core.verification.VerificationChallengeType
import com.example.core.verification.VerificationDetectionResult
import com.example.core.verification.VerificationDetector
import com.example.core.verification.VerificationManager
import com.example.core.verification.VerificationState
import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaDetails
import com.example.domain.models.MangaPage
import com.example.domain.models.MangaSummary
import com.example.domain.models.PageResult
import com.example.domain.models.SourceCapability
import com.example.domain.models.SourceDiagnosticReport
import com.example.domain.models.SourceStatus
import com.example.sources.DynamicSiteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Independent HTML/JSON parser contract per source or engine.
 */
interface SourceParser {
    fun parseCatalog(html: String, pageBaseUri: String): List<MangaSummary>
    fun parseDetails(html: String, mangaUrl: String): MangaDetails?
    fun parseChapters(html: String, mangaId: String, mangaUrl: String): List<MangaChapter>
    fun parseReaderPages(html: String, chapterUrl: String): List<MangaPage>
}

/**
 * Unified SourceAdapter & MangaSource contract.
 * Every source implements this independently without coupling UI to HTML structure,
 * and can report its need for security verification via [VerificationDetector] / [VerificationManager].
 */
interface MangaSource {
    val id: String
    val name: String
    val baseUrl: String
    val domain: String
    val iconText: String
    val iconColorHex: Long
    val requiresCloudflareVerification: Boolean
        get() = false

    val capabilities: Set<SourceCapability>
        get() = setOf(
            SourceCapability.POPULAR_MANGA,
            SourceCapability.LATEST_UPDATES,
            SourceCapability.SEARCH_MANGA,
            SourceCapability.MANGA_DETAILS,
            SourceCapability.MANGA_CHAPTERS,
            SourceCapability.CHAPTER_PAGES,
            SourceCapability.PAGINATION,
            SourceCapability.CATEGORIES,
            SourceCapability.FILTERS
        )

    fun attachVerificationManager(manager: VerificationManager) {}

    fun detectSecurityChallenge(
        html: String,
        httpCode: Int = 200,
        headers: Map<String, String> = emptyMap(),
        pageTitle: String = ""
    ): VerificationDetectionResult {
        return VerificationDetector.detect(
            html = html,
            httpCode = httpCode,
            headers = headers,
            pageTitle = pageTitle
        )
    }

    fun parseVerifiedHtmlSnapshot(html: String, pageBaseUri: String = baseUrl): List<MangaSummary> = emptyList()

    suspend fun getPopular(page: Int): PageResult<MangaSummary>
    suspend fun getLatestUpdates(page: Int): PageResult<MangaSummary> = getPopular(page)
    suspend fun search(query: String, page: Int): PageResult<MangaSummary>
    suspend fun getDetails(mangaUrl: String): MangaDetails
    suspend fun getChapters(mangaUrl: String): List<MangaChapter>
    suspend fun getPages(chapterUrl: String): List<MangaPage>
}

typealias SourceAdapter = MangaSource

/**
 * Multi-attribute HTML Image Extractor using Jsoup & OkHttp HttpUrl.
 * Supports: src, data-src, data-lazy-src, data-original, data-bg, srcset, data-srcset, and inline style background-image.
 * Properly encodes Arabic characters and spaces in relative/absolute URLs so covers never fail with URISyntaxException.
 */
object HtmlImageExtractor {

    private val IMAGE_ATTRIBUTES = listOf(
        "data-src",
        "data-lazy-src",
        "data-original",
        "data-bg",
        "data-srcset",
        "srcset",
        "src"
    )

    private val BG_URL_REGEX = Regex("""url\(['"]?([^'")]+)['"]?\)""")

    fun extractImageUrl(element: Element?, baseUri: String): String? {
        if (element == null) return null

        // 1. Check standard and lazy-loading attributes on the element or child <img>
        val target = if (element.tagName().equals("img", ignoreCase = true)) {
            element
        } else {
            element.selectFirst("img") ?: element
        }

        for (attr in IMAGE_ATTRIBUTES) {
            val raw = target.attr(attr).trim()
            if (isValidRawImageCandidate(raw)) {
                val candidate = if (attr.endsWith("srcset")) {
                    parseFirstUrlFromSrcset(raw)
                } else {
                    raw
                }
                val resolved = resolveUrl(baseUri, candidate)
                if (resolved != null && !isDecorativeOrLogoUrl(resolved)) return resolved
            }
        }

        // 2. Check inline style="background-image: url(...)" on element or children
        val styledEl = if (element.attr("style").contains("url(")) {
            element
        } else {
            element.selectFirst("[style*=url(]")
        }
        if (styledEl != null) {
            val match = BG_URL_REGEX.find(styledEl.attr("style"))
            val bgUrl = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (isValidRawImageCandidate(bgUrl)) {
                val resolved = resolveUrl(baseUri, bgUrl)
                if (resolved != null && !isDecorativeOrLogoUrl(resolved)) return resolved
            }
        }

        return null
    }

    private fun isValidRawImageCandidate(raw: String): Boolean {
        if (raw.isBlank()) return false
        val lower = raw.lowercase()
        if (lower.startsWith("data:image/svg") ||
            lower.startsWith("data:image/gif") ||
            lower.contains("blank.gif") ||
            lower.contains("placeholder") ||
            lower.contains("loading.gif") ||
            lower.startsWith("javascript:")
        ) {
            return false
        }
        return true
    }

    fun isDecorativeOrLogoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith("logo.png") ||
            lower.endsWith("teamx.png") ||
            lower.endsWith("mangalek.png") ||
            lower.endsWith("btn_close.gif") ||
            lower.endsWith("discord.svg") ||
            lower.contains("/themes/madara/images/") ||
            lower.contains("favicon") ||
            lower.contains("avatar")
    }

    private fun parseFirstUrlFromSrcset(srcset: String): String {
        return srcset
            .lines()
            .joinToString(" ")
            .split(',')
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
    }

    fun extractChapterPagesFromHtml(
        html: String,
        baseUri: String,
        selector: String = ".reading-content img, .page-break img, .chapter-content img, #readerarea img, img.wp-manga-chapter-img, img.manga-chapter-img"
    ): List<MangaPage> {
        val doc: Document = Jsoup.parse(html, baseUri)
        val elements = doc.select(selector)
        val urls = mutableListOf<String>()
        for (el in elements) {
            val url = extractImageUrl(el, baseUri)
            if (!url.isNullOrBlank() &&
                (url.startsWith("https://") || url.startsWith("http://")) &&
                !isDecorativeOrLogoUrl(url)
            ) {
                urls.add(url)
            }
        }

        // Next.js / JSON embedded image array fallback (e.g., azoramoon.com / custom React readers)
        if (urls.isEmpty()) {
            val jsonImgRegex = Regex("""https?:\\?/\\?/[^"'\s<>]+\.(?:webp|jpg|jpeg|png|avif)""", RegexOption.IGNORE_CASE)
            for (match in jsonImgRegex.findAll(html)) {
                val cleaned = match.value.replace("\\/", "/")
                val resolved = resolveUrl(baseUri, cleaned)
                if (!resolved.isNullOrBlank() && !isDecorativeOrLogoUrl(resolved) && !resolved.contains("cover", ignoreCase = true)) {
                    urls.add(resolved)
                }
            }
        }

        return urls.distinct().mapIndexed { index, imageUrl ->
            MangaPage(index = index, imageUrl = imageUrl)
        }
    }

    fun isSecurityVerificationPage(html: String, httpCode: Int = 200): Boolean {
        return VerificationDetector.detect(html = html, httpCode = httpCode).isVerificationRequired
    }

    /**
     * Resolves relative, protocol-relative, and unencoded Arabic/space URLs against baseUri
     * and normalizes http:// to https://.
     */
    fun resolveUrl(baseUri: String, relativeOrAbsolute: String): String? {
        val clean = relativeOrAbsolute
            .trim()
            .replace("\n", "")
            .replace("\r", "")
            .trim()
        if (clean.isEmpty()) return null

        val normalizedBase = if (baseUri.startsWith("http://")) {
            "https://" + baseUri.removePrefix("http://")
        } else if (!baseUri.startsWith("https://")) {
            "https://$baseUri"
        } else {
            baseUri
        }

        // First try OkHttp's WHATWG/RFC-compliant HttpUrl resolver (handles Arabic & spaces gracefully)
        val baseHttpUrl = normalizedBase.toHttpUrlOrNull()
        if (baseHttpUrl != null) {
            val resolvedHttpUrl = baseHttpUrl.resolve(clean)
            if (resolvedHttpUrl != null) {
                return resolvedHttpUrl.newBuilder().scheme("https").build().toString()
            }
        }

        // Fallback URI resolution with space encoding
        return try {
            val safeClean = clean.replace(" ", "%20")
            when {
                safeClean.startsWith("https://") -> safeClean
                safeClean.startsWith("//") -> "https:$safeClean"
                safeClean.startsWith("http://") -> "https://" + safeClean.removePrefix("http://")
                else -> URI(normalizedBase).resolve(safeClean).toString()
            }
        } catch (e: Exception) {
            null
        }
    }
}

data class SourceValidationStepResult(
    val normalizedDomain: String,
    val status: SourceStatus,
    val matchedSource: MangaSource?,
    val suggestedName: String,
    val messageAr: String,
    val diagnosticReport: SourceDiagnosticReport? = null
)

class SourceRegistry(sources: List<MangaSource>) {
    private val builtInByDomain = sources.associateBy { it.domain.lowercase() }
    private val byId = sources.associateBy { it.id }.toMutableMap()
    private val byDomain = sources.associateBy { it.domain.lowercase() }.toMutableMap()

    @Synchronized fun register(source: MangaSource) {
        // Preserve all six built-ins by ID when a saved alias or configurable source is registered.
        // Remove only stale non-built-in aliases for this domain.
        byId.values.filter { old -> old.domain.equals(source.domain, ignoreCase = true) &&
            old.id != source.id && builtInByDomain[old.domain.lowercase()]?.id != old.id }
            .forEach { byId.remove(it.id) }
        byId[source.id]?.let { old ->
            if (old.domain != source.domain) byDomain.remove(old.domain.lowercase())
        }
        byId[source.id] = source
        byDomain[source.domain.lowercase()] = source
    }

    @Synchronized fun unregister(sourceId: String) {
        val removed = byId.remove(sourceId)
        if (removed != null && byDomain[removed.domain.lowercase()]?.id == sourceId) {
            byDomain.remove(removed.domain.lowercase())
        }
        if (removed != null) builtInByDomain[removed.domain.lowercase()]?.let { register(it) }
    }

    fun findById(id: String): MangaSource? = byId[id]

    fun findByDomain(domainInput: String): MangaSource? {
        val clean = runCatching { normalizeDomain(domainInput) }.getOrNull() ?: return null
        return byDomain[clean] // Strict exact host matching; no unrelated suffix aliases.
    }

    fun getAllRegistered(): List<MangaSource> = byId.values.toList()
}

/**
 * Live Health & End-to-End Extraction Checker for any [MangaSource].
 * Tests:
 * 1) Connection & Verification state
 * 2) Catalog extraction (`getPopular(1)`)
 * 3) Cover URL validity
 * 4) Details & Chapters extraction (`getChapters`)
 * 5) Reader Page extraction (`getPages`)
 */
class SourceHealthChecker(private val verificationManager: VerificationManager) {
    private val imageClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(false).build()

    private fun checkImage(url: String?): Boolean {
        if (url.isNullOrBlank() || !url.startsWith("https://")) return false
        if (runCatching { normalizeDomain(url) }.isFailure) return false
        return try {
            imageClient.newCall(Request.Builder().url(url).header("Range", "bytes=0-2047").build()).execute().use {
                val bytes = it.body?.byteStream()?.use { stream -> ByteArray(16).let { b -> b.copyOf(stream.read(b).coerceAtLeast(0)) } } ?: ByteArray(0)
                val contentType = it.header("Content-Type").orEmpty()
                it.isSuccessful && bytes.isNotEmpty() && (contentType.startsWith("image/") ||
                    bytes.take(3) == listOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) ||
                    bytes.take(4) == "RIFF".toByteArray().toList() ||
                    bytes.take(4) == "\u0089PNG".toByteArray().toList())
            }
        } catch (_: Exception) { false }
    }

    suspend fun checkSourceHealth(source: MangaSource): SourceDiagnosticReport = withContext(Dispatchers.IO) {
        source.attachVerificationManager(verificationManager)
        val state = verificationManager.getState(source.domain)
        fun report(status: SourceStatus, error: String?, count: Int = 0,
                   cover: String? = null, coverOk: Boolean = false, detailsOk: Boolean = false,
                   chapters: Int = 0, pages: Int = 0, searchOk: Boolean = false): SourceDiagnosticReport =
            SourceDiagnosticReport(
                sourceId = source.id, sourceName = source.name, domain = source.domain,
                connectionStatusAr = if (status == SourceStatus.NETWORK_ERROR) "تعذّر الاتصال" else "لم يتم إثبات اتصال HTTP كامل",
                verificationStatusAr = state.labelAr, adapterStatus = status,
                siteArchitecture = if (source is com.example.sources.MangaDexSource) "REST API" else "HTML + Jsoup",
                extractedMangaCount = count, sampleCoverUrl = cover, imagesStatusOk = coverOk,
                loadedCoverCount = if (coverOk) 1 else 0, detailsStatusOk = detailsOk,
                searchStatusOk = searchOk, chaptersStatusOk = chapters > 0, extractedChaptersCount = chapters,
                pagesStatusOk = pages > 0, extractedPagesCount = pages,
                capabilities = source.capabilities, lastError = error
            )
        try {
            val popular = withTimeoutOrNull(16_000L) { source.getPopular(1) }
                ?: return@withContext report(SourceStatus.NETWORK_ERROR, "Catalog timeout")
            val items = popular.items.filter { it.title.isNotBlank() && it.mangaUrl.startsWith("https://") }
            if (items.isEmpty()) return@withContext report(SourceStatus.NEEDS_CONFIGURATION, "0 real catalog entries parsed")
            val sample = items.first()
            val details = withTimeoutOrNull(10_000L) { runCatching { source.getDetails(sample.mangaUrl) }.getOrNull() }
            val detailsOk = details?.title?.isNotBlank() == true
            val chapters = withTimeoutOrNull(10_000L) {
                runCatching { source.getChapters(sample.mangaUrl) }.getOrDefault(emptyList())
            }.orEmpty()
            val chapter = chapters.firstOrNull()
            val pages = if (chapter == null) emptyList() else withTimeoutOrNull(10_000L) {
                runCatching { source.getPages(chapter.remoteId) }.getOrDefault(emptyList())
            }.orEmpty()
            val coverOk = withTimeoutOrNull(7_000L) { checkImage(sample.coverUrl) } ?: false
            val pageOk = if (pages.isEmpty()) false else
                withTimeoutOrNull(7_000L) { checkImage(pages.first().imageUrl) } ?: false
            val searchOk = withTimeoutOrNull(10_000L) {
                runCatching { source.search(sample.title.take(20), 1).items.isNotEmpty() }.getOrDefault(false)
            } ?: false
            val fullyWorking = detailsOk && chapters.isNotEmpty() && pages.isNotEmpty() && coverOk && pageOk && searchOk
            val status = if (fullyWorking) SourceStatus.SUPPORTED else SourceStatus.PARTIALLY_SUPPORTED
            report(status, if (fullyWorking) null else "Incomplete: details=$detailsOk chapters=${chapters.size} pages=${pages.size} cover=$coverOk pageImage=$pageOk search=$searchOk",
                items.size, sample.coverUrl, coverOk, detailsOk, chapters.size, if (pageOk) pages.size else 0, searchOk)
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            val status = when {
                e is com.example.core.verification.VerificationRequiredException || message.contains("SECURITY_VERIFICATION_REQUIRED") -> SourceStatus.NEEDS_VERIFICATION
                message.contains("timeout", true) || message.contains("host", true) || message.contains("HTTP 5") -> SourceStatus.NETWORK_ERROR
                else -> SourceStatus.NEEDS_CONFIGURATION
            }
            report(status, "${e.javaClass.simpleName}: $message")
        }
    }
}

class SourceValidator(
    private val registry: SourceRegistry,
    val verificationManager: VerificationManager = VerificationManager()
) {
    val healthChecker = SourceHealthChecker(verificationManager)

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    init {
        registry.getAllRegistered().forEach { source ->
            source.attachVerificationManager(verificationManager)
        }
    }

    fun markDomainHumanVerified(domain: String, verifiedHtml: String? = null) {
        val clean = runCatching { normalizeDomain(domain) }.getOrNull() ?: return
        val matched = registry.findByDomain(clean)
        verificationManager.completeVerification(
            sourceId = matched?.id ?: clean,
            domain = clean,
            verifiedHtml = verifiedHtml
        )
    }

    fun isDomainHumanVerified(domain: String): Boolean {
        val clean = runCatching { normalizeDomain(domain) }.getOrNull() ?: return false
        return verificationManager.isVerified(clean)
    }

    fun getVerificationState(domain: String): VerificationState {
        val clean = runCatching { normalizeDomain(domain) }.getOrDefault(domain)
        return verificationManager.getState(clean)
    }

    /**
     * Smart Site Onboarding & Validation System:
     * 1. Cleans and validates domain syntax (`starzmanga.com`, `/https://starzmanga.com`, etc.).
     * 2. Finds an existing registered adapter OR dynamically creates a [DynamicSiteSource] for any new manga domain.
     * 3. Inspects reachability, security verification requirements, HTML/API structure, manga list, covers, details, and chapters.
     * 4. Registers the adapter into [registry] so the user can add ANY valid manga site manually.
     */
    suspend fun validateDomainCompatibility(rawInput: String): Result<SourceValidationStepResult> = withContext(Dispatchers.IO) {
        val normalized = try {
            normalizeDomain(rawInput)
        } catch (e: IllegalArgumentException) {
            return@withContext Result.failure(e)
        }

        // 1. Check if a known or previously registered adapter exists for this domain;
        //    if not, create and register a smart DynamicSiteSource adapter for this domain!
        var connector = registry.findByDomain(normalized)
        if (connector == null) {
            val probedTitle = probeSiteTitleAndVerify(normalized)
            val dynamicAdapter = DynamicSiteSource(
                domain = normalized,
                customName = probedTitle
            )
            dynamicAdapter.attachVerificationManager(verificationManager)
            registry.register(dynamicAdapter)
            connector = dynamicAdapter
        } else {
            connector.attachVerificationManager(verificationManager)
        }

        if (connector.requiresCloudflareVerification && !isDomainHumanVerified(normalized)) {
            verificationManager.markVerificationRequired(
                sourceId = connector.id,
                domain = normalized,
                challengeType = VerificationChallengeType.CLOUDFLARE_CHALLENGE,
                reasonAr = "يتطلب هذا الموقع إكمال التحقق الأمني"
            )
            return@withContext Result.success(
                SourceValidationStepResult(
                    normalizedDomain = normalized,
                    status = SourceStatus.REQUIRES_VERIFICATION,
                    matchedSource = connector,
                    suggestedName = connector.name,
                    messageAr = "يتطلب هذا الموقع إكمال التحقق الأمني."
                )
            )
        }

        val discovered = SourceDiscoveryEngine().inspect(normalized)
        if (discovered.requiresVerification) {
            verificationManager.markVerificationRequired(sourceId = connector.id, domain = normalized,
                reasonAr = "الموقع يتطلب تحققاً بشرياً")
            return@withContext Result.success(SourceValidationStepResult(normalized,
                SourceStatus.NEEDS_VERIFICATION, connector, connector.name, discovered.notes))
        }
        if (discovered.httpCode == null) {
            return@withContext Result.success(SourceValidationStepResult(normalized,
                SourceStatus.NETWORK_ERROR, connector, connector.name, discovered.notes))
        }
        if (discovered.httpCode !in 200..299) {
            return@withContext Result.success(SourceValidationStepResult(normalized,
                SourceStatus.WEBVIEW_ONLY, connector, connector.name,
                "HTTP ${discovered.httpCode}: لا يمكن إثبات نجاح الاستخراج، جرّب المتصفح الداخلي"))
        }
        val report = healthChecker.checkSourceHealth(connector)
        val status = if (report.adapterStatus == SourceStatus.NEEDS_CONFIGURATION && discovered.javascriptHeavy)
            SourceStatus.WEBVIEW_ONLY else report.adapterStatus
        Result.success(SourceValidationStepResult(normalized, status, connector, connector.name,
            "أعمال: ${report.extractedMangaCount}، فصول: ${report.extractedChaptersCount}، صفحات: ${report.extractedPagesCount}، الحالة: ${status.name}",
            report.copy(adapterStatus = status, siteArchitecture = discovered.architecture)))
    }

    private fun probeSiteTitleAndVerify(domain: String): String {
        val defaultTitle = domain
            .substringBefore('.')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return try {
            val req = Request.Builder()
                .url("https://$domain/")
                .build()
            probeClient.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                val detection = VerificationDetector.detect(
                    html = html,
                    httpCode = resp.code,
                    headers = resp.headers.toMap()
                )
                if (detection.isVerificationRequired) {
                    verificationManager.markVerificationRequired(
                        sourceId = "custom_${domain.replace('.', '_')}",
                        domain = domain,
                        challengeType = detection.challengeType,
                        reasonAr = detection.reasonAr
                    )
                    return defaultTitle
                }
                val rawTitle = Jsoup.parse(html).title().trim()
                val cleaned = rawTitle
                    .substringBefore(" - ")
                    .substringBefore(" | ")
                    .substringBefore(" – ")
                    .trim()
                if (cleaned.length in 2..45 && !cleaned.contains("Just a moment", ignoreCase = true)) {
                    cleaned
                } else {
                    defaultTitle
                }
            }
        } catch (_: Exception) {
            defaultTitle
        }
    }
}

/**
 * SourceManager coordinating [SourceRegistry], [SourceValidator], [SourceHealthChecker], and [VerificationManager].
 */
class SourceManager(
    val registry: SourceRegistry,
    val validator: SourceValidator,
    val verificationManager: VerificationManager = validator.verificationManager
) {
    val healthChecker: SourceHealthChecker = validator.healthChecker

    init {
        registry.getAllRegistered().forEach { source ->
            source.attachVerificationManager(verificationManager)
        }
    }

    fun getSourceById(sourceId: String): MangaSource? = registry.findById(sourceId)

    fun getSourceByDomain(domain: String): MangaSource? = registry.findByDomain(domain)

    fun getAllSources(): List<MangaSource> = registry.getAllRegistered()

    fun getVerificationStateForSource(sourceIdOrDomain: String): VerificationState {
        val source = registry.findById(sourceIdOrDomain) ?: registry.findByDomain(sourceIdOrDomain)
        val key = source?.domain ?: sourceIdOrDomain
        val currentState = verificationManager.getState(key)
        if (currentState == VerificationState.NOT_REQUIRED && source?.requiresCloudflareVerification == true) {
            return VerificationState.REQUIRED
        }
        return currentState
    }

    suspend fun runDiagnosticsForSource(sourceIdOrDomain: String): SourceDiagnosticReport? {
        val source = registry.findById(sourceIdOrDomain) ?: registry.findByDomain(sourceIdOrDomain) ?: return null
        return healthChecker.checkSourceHealth(source)
    }
}
