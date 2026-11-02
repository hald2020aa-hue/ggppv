package com.example.sources

import com.example.core.common.PipelineLogger
import com.example.core.common.PipelineStage
import com.example.core.verification.VerificationDetectionResult
import com.example.core.verification.VerificationManager
import com.example.core.verification.VerificationRequiredException
import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaDetails
import com.example.domain.models.MangaPage
import com.example.domain.models.MangaSummary
import com.example.domain.models.PageResult
import com.example.sourceapi.HtmlImageExtractor
import com.example.sourceapi.MangaSource
import com.example.sourceapi.SourceParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Non-blocking, coroutine-cancellable OkHttp Call extension.
 */
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        runCatching { cancel() }
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) {
                cont.resumeWithException(e)
            }
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) {
                cont.resume(response)
            } else {
                response.close()
            }
        }
    })
}

/**
 * Cleans extracted manga titles by removing leading ranking indices (e.g. "1Magic emperor"),
 * trailing chapter suffixes (e.g. "Magic emperor الفصل 930"), and status badges ("جديد", "HOT").
 */
fun cleanExtractedMangaTitle(raw: String): String {
    var s = raw.replace(Regex("""\s+"""), " ").trim()
    if (s.isEmpty()) return ""
    // Strip leading rank number stuck to title in top-10 widgets, e.g. "1Magic emperor" or "10 Solo Leveling"
    val rankPrefixMatch = Regex("""^(\d{1,2})\s*([A-Za-z\u0600-\u06FF].{2,})$""").find(s)
    if (rankPrefixMatch != null) {
        val candidate = rankPrefixMatch.groupValues[2].trim()
        // Only strip if the original didn't start with a legitimate numeric title like "100 Years..."
        if (!candidate.startsWith("Years", ignoreCase = true) &&
            !candidate.startsWith("Days", ignoreCase = true) &&
            !candidate.startsWith("साल", ignoreCase = true)
        ) {
            s = candidate
        }
    }
    // Strip trailing chapter suffix leaked into anchor text (e.g. "Magic emperor الفصل 930" or "Solo Leveling Chapter 12")
    s = s.replace(Regex("""\s+(?:الفصل|فصل|Chapter|Ch\.?)\s*\d+(?:\.\d+)?.*$""", RegexOption.IGNORE_CASE), "").trim()
    // Strip leading/trailing badge tokens
    s = s.replace(Regex("""^(?:مانهوا|مانجا|هاوا|جديد|HOT|NEW)\s+""", RegexOption.IGNORE_CASE), "").trim()
    return s
}

/**
 * Normalizes manga status strings from Arabic/English site labels into canonical status codes,
 * or returns null when the status is genuinely unknown (never fabricates "OnGoing").
 */
fun parseNormalizedMangaStatus(statusRaw: String): String? {
    val clean = statusRaw.trim()
    if (clean.isEmpty()) return null
    return when {
        clean.contains("مكتمل", ignoreCase = true) ||
            clean.contains("منتهي", ignoreCase = true) ||
            clean.contains("Completed", ignoreCase = true) ||
            clean.contains("Finished", ignoreCase = true) -> "Completed"
        clean.contains("متوقف", ignoreCase = true) ||
            clean.contains("Hiatus", ignoreCase = true) ||
            clean.contains("On Hold", ignoreCase = true) -> "Hiatus"
        clean.contains("ملغى", ignoreCase = true) ||
            clean.contains("ملغي", ignoreCase = true) ||
            clean.contains("Cancelled", ignoreCase = true) ||
            clean.contains("Dropped", ignoreCase = true) -> "Cancelled"
        clean.contains("مستمر", ignoreCase = true) ||
            clean.contains("جاري", ignoreCase = true) ||
            clean.contains("OnGoing", ignoreCase = true) ||
            clean.contains("Ongoing", ignoreCase = true) ||
            clean.contains("Releasing", ignoreCase = true) -> "OnGoing"
        else -> null
    }
}

/**
 * Extracts a real numerical rating (0.1 .. 10.0) from a manga details Document, or null if absent.
 */
fun parseRatingFromDoc(doc: Document): Float? {
    val selectors = listOf(
        "#averagerate",
        "span.score",
        ".post-total-rating .score",
        ".num[itemprop=ratingValue]",
        "[itemprop=ratingValue]",
        ".rating-box .num",
        ".series-rating"
    )
    for (sel in selectors) {
        val el = doc.selectFirst(sel) ?: continue
        val raw = el.attr("content").ifBlank { el.text() }.trim()
        val num = Regex("""(\d+(\.\d+)?)""").find(raw)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        if (num != null && num > 0f && num <= 10f) {
            return num
        }
    }
    return null
}

/**
 * Real HTML + Live API Manga Source Connector Base Class.
 * - Zero hardcoded manga lists or static placeholder data.
 * - Full Android Logcat pipeline tracing via [PipelineLogger].
 * - Connected to Universal Website Verification System ([VerificationManager] & [com.example.core.verification.VerificationDetector]).
 * - Supports WordPress Madara, MangaStream/WPMangaReader, Madara AJAX chapter endpoints,
 *   mirror fallback when a single page is restricted, and live MangaDex REST API v5 bridge.
 */
abstract class ConfigurableHtmlMangaSource(
    override val id: String,
    override val name: String,
    override val domain: String,
    override val iconText: String,
    override val iconColorHex: Long,
    override val requiresCloudflareVerification: Boolean = false,
    protected val mirrorDomains: List<String> = emptyList(),
    protected val itemSelector: String,
    protected val titleSelector: String,
    protected val coverSelector: String,
    protected val latestChapterSelector: String,
    protected val detailsTitleSelector: String,
    protected val detailsDescSelector: String,
    protected val detailsGenresSelector: String,
    protected val detailsAuthorSelector: String,
    protected val detailsStatusSelector: String,
    protected val chapterRowSelector: String,
    protected val readerImagesSelector: String,
    protected val liveApiQueryParams: String = "originalLanguage[]=ko&order[followedCount]=desc"
) : MangaSource, SourceParser {

    override val baseUrl: String = "https://$domain"

    protected var verificationManager: VerificationManager? = null

    // Remembers catalog summaries and latest chapter numbers discovered on the catalog page
    // so that if a site blocks /manga/{slug}/ with "Access Denied" (like mangalik.net) while
    // allowing /manga/ and /manga/{slug}/{chapter}/, getDetails & getChapters still work 100%!
    protected val cachedWorkSummaries = ConcurrentHashMap<String, MangaSummary>()
    protected val cachedWorkChapterHints = ConcurrentHashMap<String, List<Pair<String, String>>>()

    override fun attachVerificationManager(manager: VerificationManager) {
        this.verificationManager = manager
    }

    override fun parseVerifiedHtmlSnapshot(html: String, pageBaseUri: String): List<MangaSummary> {
        return parseMangaSummariesFromHtml(html, pageBaseUri)
    }

    override fun parseCatalog(html: String, pageBaseUri: String): List<MangaSummary> {
        return parseMangaSummariesFromHtml(html, pageBaseUri)
    }

    override fun parseDetails(html: String, mangaUrl: String): MangaDetails? {
        return parseDetailsFromHtml(html, mangaUrl)
    }

    override fun parseChapters(html: String, mangaId: String, mangaUrl: String): List<MangaChapter> {
        return parseChaptersFromDoc(Jsoup.parse(html, mangaUrl), mangaId, mangaUrl)
    }

    override fun parseReaderPages(html: String, chapterUrl: String): List<MangaPage> {
        return HtmlImageExtractor.extractChapterPagesFromHtml(html, chapterUrl, readerImagesSelector)
    }

    protected val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    protected fun buildGetRequest(url: String, referer: String = "$baseUrl/"): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", VerificationManager.getUserAgent())
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7")
            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            .header("Referer", referer)
        val cookieHeader = VerificationManager.getCookieHeader(url)
        if (!cookieHeader.isNullOrBlank()) {
            builder.header("Cookie", cookieHeader)
        }
        return builder.build()
    }

    protected fun extractOrigin(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: domain
            val port = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (_: Exception) {
            baseUrl
        }
    }

    override suspend fun getPopular(page: Int): PageResult<MangaSummary> = withContext(Dispatchers.IO) {
        var lastFailureReason: String? = null
        var lastSecurityChallenge: VerificationDetectionResult? = null

        val vManager = verificationManager
        if (requiresCloudflareVerification && vManager != null && !vManager.isVerified(domain)) {
            val detection = VerificationDetectionResult(
                isVerificationRequired = true,
                challengeType = com.example.core.verification.VerificationChallengeType.CLOUDFLARE_CHALLENGE,
                httpCode = 403,
                reasonAr = "يتطلب هذا الموقع إكمال التحقق الأمني قبل عرض الأعمال"
            )
            vManager.markVerificationRequired(
                sourceId = id,
                domain = domain,
                challengeType = detection.challengeType,
                reasonAr = detection.reasonAr
            )
            throw VerificationRequiredException(
                sourceId = id,
                domain = domain,
                targetUrl = baseUrl,
                detection = detection
            )
        }

        if (vManager != null && page == 1) {
            val verifiedSnapshot = vManager.getVerifiedHtmlSnapshot(domain)
            if (!verifiedSnapshot.isNullOrBlank()) {
                val parsedFromSnapshot = parseMangaSummariesFromHtml(verifiedSnapshot, baseUrl)
                if (parsedFromSnapshot.isNotEmpty()) {
                    PipelineLogger.log(
                        stage = PipelineStage.PARSER_COMPLETED,
                        sourceId = id,
                        sourceName = name,
                        resultCount = parsedFromSnapshot.size,
                        status = "VERIFIED_WEBVIEW_DOM_SUCCESS",
                        details = "Extracted ${parsedFromSnapshot.size} items from verified WebView DOM"
                    )
                    return@withContext PageResult(
                        items = parsedFromSnapshot,
                        page = page,
                        hasNextPage = true
                    )
                }
            }
        }

        // 1. Attempt direct HTML extraction from primary domain + mirrors
        val candidateUrls = buildCandidatePopularUrls(page)
        for (url in candidateUrls) {
            try {
                PipelineLogger.log(
                    stage = PipelineStage.NETWORK_REQUEST_STARTED,
                    sourceId = id,
                    sourceName = name,
                    status = "STARTED",
                    details = "GET $url"
                )
                val response = withTimeoutOrNull(5_500L) {
                    httpClient.newCall(buildGetRequest(url)).awaitResponse()
                }
                if (response == null) {
                    lastFailureReason = "HTTP timeout on $url"
                    PipelineLogger.log(
                        stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                        sourceId = id,
                        sourceName = name,
                        status = "TIMEOUT",
                        failureReason = lastFailureReason,
                        details = url
                    )
                    continue
                }

                response.use { resp ->
                    val code = resp.code
                    val contentType = resp.header("Content-Type").orEmpty()
                    val body = resp.body?.string().orEmpty()
                    val headersMap = resp.headers.toMap()

                    val detection = detectSecurityChallenge(
                        html = body,
                        httpCode = code,
                        headers = headersMap
                    )
                    if (detection.isVerificationRequired) {
                        lastSecurityChallenge = detection
                        lastFailureReason = "Cloudflare challenge ($code) on $url"
                        PipelineLogger.log(
                            stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                            sourceId = id,
                            sourceName = name,
                            status = "CLOUDFLARE_$code",
                            failureReason = lastFailureReason,
                            details = "challengeType=${detection.challengeType} contentType=$contentType"
                        )
                        return@use
                    }

                    if (!resp.isSuccessful) {
                        lastFailureReason = "HTTP $code on $url"
                        PipelineLogger.log(
                            stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                            sourceId = id,
                            sourceName = name,
                            status = "HTTP_$code",
                            failureReason = lastFailureReason,
                            details = "contentType=$contentType"
                        )
                        return@use
                    }

                    PipelineLogger.log(
                        stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                        sourceId = id,
                        sourceName = name,
                        resultCount = body.length,
                        status = "HTTP_200",
                        details = "url=$url bytes=${body.length}"
                    )

                    PipelineLogger.log(
                        stage = PipelineStage.PARSER_STARTED,
                        sourceId = id,
                        sourceName = name,
                        status = "HTML_JSOUP",
                        details = "selector=$itemSelector"
                    )
                    val finalUrl = resp.request.url.toString()
                    val parsed = parseMangaSummariesFromHtml(body, finalUrl)
                    PipelineLogger.log(
                        stage = PipelineStage.PARSER_COMPLETED,
                        sourceId = id,
                        sourceName = name,
                        resultCount = parsed.size,
                        status = if (parsed.isNotEmpty()) "SUCCESS" else "EMPTY",
                        failureReason = if (parsed.isEmpty()) "0 items matched selector [$itemSelector] on $finalUrl" else null
                    )

                    if (parsed.isNotEmpty()) {
                        return@withContext PageResult(
                            items = parsed,
                            page = page,
                            hasNextPage = parsed.size >= 8
                        )
                    } else {
                        lastFailureReason = "HTML parser extracted 0 items from $finalUrl"
                    }
                }
            } catch (e: Exception) {
                lastFailureReason = "${e.javaClass.simpleName}: ${e.message}"
                PipelineLogger.log(
                    stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                    sourceId = id,
                    sourceName = name,
                    status = "FAILED",
                    failureReason = lastFailureReason,
                    details = "url=$url",
                    throwable = e
                )
            }
        }

        // 2. Offscreen WebView DOM fallback when direct OkHttp fails or receives a JS/Cloudflare challenge
        val webViewTargetUrl = candidateUrls.firstOrNull() ?: buildPopularUrl(page)
        val webViewHtml = VerificationManager.fetchHtmlViaWebView(webViewTargetUrl, timeoutMs = 11_000L)
        if (!webViewHtml.isNullOrBlank() && !HtmlImageExtractor.isSecurityVerificationPage(webViewHtml, 200)) {
            val parsedWebView = parseMangaSummariesFromHtml(webViewHtml, webViewTargetUrl)
            if (parsedWebView.isNotEmpty()) {
                vManager?.saveVerifiedHtmlSnapshot(domain, webViewHtml)
                return@withContext PageResult(
                    items = parsedWebView,
                    page = page,
                    hasNextPage = parsedWebView.size >= 8
                )
            }
        }

        if (lastSecurityChallenge != null && vManager?.isVerified(domain) != true) {
            vManager?.markVerificationRequired(
                sourceId = id,
                domain = domain,
                challengeType = lastSecurityChallenge!!.challengeType,
                reasonAr = lastSecurityChallenge!!.reasonAr
            )
            throw VerificationRequiredException(
                sourceId = id,
                domain = domain,
                targetUrl = baseUrl,
                detection = lastSecurityChallenge!!
            )
        }

        // Never substitute MangaDex results for a different source domain.
        throw IllegalStateException(
            lastFailureReason ?: "تعذّر جلب قائمة المانهوا من المصدر $name"
        )
    }

    override suspend fun search(query: String, page: Int): PageResult<MangaSummary> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return@withContext getPopular(page)
        }

        val searchUrl = buildSearchUrl(cleanQuery, page)
        try {
            PipelineLogger.log(
                stage = PipelineStage.NETWORK_REQUEST_STARTED,
                sourceId = id,
                sourceName = name,
                status = "SEARCH_STARTED",
                details = "GET $searchUrl"
            )
            val response = withTimeoutOrNull(5_500L) {
                httpClient.newCall(buildGetRequest(searchUrl)).awaitResponse()
            }
            response?.use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string().orEmpty()
                    if (!HtmlImageExtractor.isSecurityVerificationPage(html, resp.code)) {
                        val parsed = parseMangaSummariesFromHtml(html, searchUrl)
                        if (parsed.isNotEmpty()) {
                            return@withContext PageResult(items = parsed, page = page, hasNextPage = false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            PipelineLogger.log(
                stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                sourceId = id,
                sourceName = name,
                status = "SEARCH_HTML_FAILED",
                failureReason = e.message,
                throwable = e
            )
        }

        PageResult(items = emptyList(), page = page, hasNextPage = false)
    }

    override suspend fun getDetails(mangaUrl: String): MangaDetails = withContext(Dispatchers.IO) {
        val mdUuid = extractMangaDexUuid(mangaUrl)
        if (id == "mangadex" && mdUuid != null) {
            val mdDetails = fetchLiveMangaDexDetails(mdUuid)
            if (mdDetails != null) return@withContext mdDetails
        }

        val slug = mangaUrl.trimEnd('/').substringAfterLast('/').ifBlank { "manga" }
        val mangaId = "${id}_$slug"

        // Candidate URLs: primary URL + mirror domains for the same path
        val candidateUrls = buildCandidateWorkUrls(mangaUrl)
        for (targetUrl in candidateUrls) {
            try {
                PipelineLogger.log(
                    stage = PipelineStage.NETWORK_REQUEST_STARTED,
                    sourceId = id,
                    sourceName = name,
                    status = "DETAILS_STARTED",
                    details = "GET $targetUrl"
                )
                val response = withTimeoutOrNull(7_000L) {
                    httpClient.newCall(buildGetRequest(targetUrl)).awaitResponse()
                }
                response?.use { resp ->
                    if (resp.isSuccessful) {
                        val finalUrl = resp.request.url.toString()
                        val html = resp.body?.string().orEmpty()
                        if (!HtmlImageExtractor.isSecurityVerificationPage(html, resp.code) && !isAccessDeniedHtml(html)) {
                            val details = parseDetailsFromHtml(html, finalUrl)
                            if (details != null) {
                                val chapters = if (details.chapters.isNotEmpty()) {
                                    details.chapters
                                } else {
                                    fetchAjaxMadaraChapters(html, finalUrl, mangaId)
                                        .ifEmpty { synthesizeMadaraChaptersIfAvailable(mangaUrl, mangaId) }
                                }
                                return@withContext details.copy(id = mangaId, chapters = chapters)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                PipelineLogger.log(
                    stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                    sourceId = id,
                    sourceName = name,
                    status = "DETAILS_FAILED",
                    failureReason = e.message,
                    throwable = e
                )
            }
        }

        // Offscreen WebView fallback for details page if direct HTTP was blocked
        val primaryWorkUrl = candidateUrls.firstOrNull() ?: mangaUrl
        val webViewHtml = VerificationManager.fetchHtmlViaWebView(primaryWorkUrl, timeoutMs = 10_000L)
        if (!webViewHtml.isNullOrBlank() && !HtmlImageExtractor.isSecurityVerificationPage(webViewHtml, 200) && !isAccessDeniedHtml(webViewHtml)) {
            val details = parseDetailsFromHtml(webViewHtml, primaryWorkUrl)
            if (details != null) {
                val chapters = if (details.chapters.isNotEmpty()) {
                    details.chapters
                } else {
                    fetchAjaxMadaraChapters(webViewHtml, primaryWorkUrl, mangaId)
                        .ifEmpty { synthesizeMadaraChaptersIfAvailable(mangaUrl, mangaId) }
                }
                return@withContext details.copy(id = mangaId, chapters = chapters)
            }
        }

        // Fallback when /manga/{slug}/ is restricted ("Access Denied" on mangalik.net)
        // but we already extracted the work summary and direct chapter links from /manga/!
        val cachedSummary = cachedWorkSummaries[mangaUrl.trimEnd('/')]
            ?: cachedWorkSummaries.values.firstOrNull { it.id == mangaId }
        val synthesizedChapters = synthesizeMadaraChaptersIfAvailable(mangaUrl, mangaId)
        if (cachedSummary != null || synthesizedChapters.isNotEmpty()) {
            return@withContext MangaDetails(
                id = mangaId,
                sourceId = id,
                title = cachedSummary?.title ?: slug.replace('-', ' ').replaceFirstChar { it.uppercase() },
                alternativeTitles = emptyList(),
                coverUrl = cachedSummary?.coverUrl,
                description = "",
                author = null,
                artist = null,
                status = cachedSummary?.status,
                genres = cachedSummary?.genres.orEmpty(),
                rating = cachedSummary?.rating,
                chapters = synthesizedChapters
            )
        }

        throw IllegalStateException("تعذّر تحميل تفاصيل المانهوا من الرابط: $mangaUrl")
    }

    override suspend fun getChapters(mangaUrl: String): List<MangaChapter> = withContext(Dispatchers.IO) {
        val mdUuid = extractMangaDexUuid(mangaUrl)
        if (id == "mangadex" && mdUuid != null) {
            return@withContext fetchLiveMangaDexChapters(mdUuid, "${id}_$mdUuid")
        }

        val slug = mangaUrl.trimEnd('/').substringAfterLast('/').ifBlank { "work" }
        val mangaId = "${id}_$slug"

        val candidateUrls = buildCandidateWorkUrls(mangaUrl)
        for (targetUrl in candidateUrls) {
            try {
                val response = withTimeoutOrNull(7_000L) {
                    httpClient.newCall(buildGetRequest(targetUrl)).awaitResponse()
                }
                response?.use { resp ->
                    if (resp.isSuccessful) {
                        val finalUrl = resp.request.url.toString()
                        val html = resp.body?.string().orEmpty()
                        if (!HtmlImageExtractor.isSecurityVerificationPage(html, resp.code) && !isAccessDeniedHtml(html)) {
                            val doc = Jsoup.parse(html, finalUrl)
                            val direct = parseChaptersFromDoc(doc, mangaId, finalUrl)
                            if (direct.isNotEmpty()) return@withContext direct
                            val ajax = fetchAjaxMadaraChapters(html, finalUrl, mangaId)
                            if (ajax.isNotEmpty()) return@withContext ajax
                        }
                    }
                }
            } catch (e: Exception) {
                PipelineLogger.log(
                    stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                    sourceId = id,
                    sourceName = name,
                    status = "CHAPTERS_FAILED",
                    failureReason = e.message,
                    throwable = e
                )
            }
        }

        val primaryWorkUrl = candidateUrls.firstOrNull() ?: mangaUrl
        val webViewHtml = VerificationManager.fetchHtmlViaWebView(primaryWorkUrl, timeoutMs = 10_000L)
        if (!webViewHtml.isNullOrBlank() && !HtmlImageExtractor.isSecurityVerificationPage(webViewHtml, 200) && !isAccessDeniedHtml(webViewHtml)) {
            val doc = Jsoup.parse(webViewHtml, primaryWorkUrl)
            val direct = parseChaptersFromDoc(doc, mangaId, primaryWorkUrl)
            if (direct.isNotEmpty()) return@withContext direct
            val ajax = fetchAjaxMadaraChapters(webViewHtml, primaryWorkUrl, mangaId)
            if (ajax.isNotEmpty()) return@withContext ajax
        }

        val synthesized = synthesizeMadaraChaptersIfAvailable(mangaUrl, mangaId)
        if (synthesized.isNotEmpty()) return@withContext synthesized

        emptyList()
    }

    override suspend fun getPages(chapterUrl: String): List<MangaPage> = withContext(Dispatchers.IO) {
        if (id == "mangadex" && chapterUrl.contains("api.mangadex.org/at-home/server/")) {
            return@withContext fetchLiveMangaDexPages(chapterUrl)
        }

        val candidateChapterUrls = buildCandidateWorkUrls(chapterUrl)
        for (targetChapterUrl in candidateChapterUrls) {
            try {
                PipelineLogger.log(
                    stage = PipelineStage.NETWORK_REQUEST_STARTED,
                    sourceId = id,
                    sourceName = name,
                    status = "PAGES_STARTED",
                    details = "GET $targetChapterUrl"
                )
                val response = withTimeoutOrNull(8_000L) {
                    httpClient.newCall(buildGetRequest(targetChapterUrl)).awaitResponse()
                }
                response?.use { resp ->
                    if (resp.isSuccessful) {
                        val finalUrl = resp.request.url.toString()
                        val html = resp.body?.string().orEmpty()
                        if (!HtmlImageExtractor.isSecurityVerificationPage(html, resp.code)) {
                            val extracted = HtmlImageExtractor.extractChapterPagesFromHtml(
                                html = html,
                                baseUri = finalUrl,
                                selector = readerImagesSelector
                            )
                            PipelineLogger.log(
                                stage = PipelineStage.PARSER_COMPLETED,
                                sourceId = id,
                                sourceName = name,
                                resultCount = extracted.size,
                                status = if (extracted.isNotEmpty()) "PAGES_SUCCESS" else "PAGES_EMPTY"
                            )
                            if (extracted.isNotEmpty()) return@withContext extracted
                        }
                    }
                }
            } catch (e: Exception) {
                PipelineLogger.log(
                    stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                    sourceId = id,
                    sourceName = name,
                    status = "PAGES_FAILED",
                    failureReason = e.message,
                    throwable = e
                )
            }
        }

        val webViewHtml = VerificationManager.fetchHtmlViaWebView(chapterUrl, timeoutMs = 10_000L)
        if (!webViewHtml.isNullOrBlank() && !HtmlImageExtractor.isSecurityVerificationPage(webViewHtml, 200)) {
            val extracted = HtmlImageExtractor.extractChapterPagesFromHtml(
                html = webViewHtml,
                baseUri = chapterUrl,
                selector = readerImagesSelector
            )
            if (extracted.isNotEmpty()) return@withContext extracted
        }

        emptyList()
    }

    private fun buildCandidateWorkUrls(mangaUrl: String): List<String> {
        val result = mutableListOf(mangaUrl)
        val path = mangaUrl.substringAfter("://").substringAfter('/', "")
        if (path.isNotBlank()) {
            for (mirror in mirrorDomains) {
                result.add("https://$mirror/$path")
            }
        }
        return result.distinct()
    }

    private fun isAccessDeniedHtml(html: String): Boolean {
        val lower = html.lowercase()
        if (html.length < 3500 && (lower.contains("<h1>access denied</h1>") || lower.contains("<title>access denied</title>"))) {
            return true
        }
        return false
    }

    /**
     * When a WordPress Madara site (e.g., mangalik.net) blocks `/manga/{slug}/` with `Access Denied`
     * while serving `/manga/` (with `.chapter-item a[href]`) and `/manga/{slug}/{chapter}/` (HTTP 200),
     * build the real chapter list from the discovered chapter links and chapter numbers.
     */
    private suspend fun synthesizeMadaraChaptersIfAvailable(mangaUrl: String, mangaId: String): List<MangaChapter> {
        val cleanWorkUrl = mangaUrl.trimEnd('/')
        val hints = cachedWorkChapterHints[cleanWorkUrl].orEmpty()

        // Never invent missing chapter URLs from chapter counts or guesses.
        // Only explicit chapter links observed in the real catalog can be used.
        if (hints.isNotEmpty()) {
            return hints.mapIndexed { idx, (title, href) ->
                val slug = href.trimEnd('/').substringAfterLast('/').ifBlank { "${idx + 1}" }
                val num = Regex("""(\d+(\.\d+)?)""").find(slug)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                    ?: (hints.size - idx).toFloat()
                MangaChapter(
                    id = "${mangaId}_$slug",
                    mangaId = mangaId,
                    sourceId = id,
                    remoteId = href,
                    title = title.ifBlank { "الفصل $slug" },
                    chapterNumber = num,
                    uploadDate = null,
                    dateLabel = ""
                )
            }.sortedBy { it.chapterNumber ?: 0f }
        }

        // No guessed /1/ endpoint and no synthetic publication dates.
        return emptyList()
    }

    protected open fun buildPopularUrl(page: Int, hostBaseUrl: String = baseUrl): String {
        return if (page <= 1) "$hostBaseUrl/manga/" else "$hostBaseUrl/manga/page/$page/"
    }

    protected open fun buildCandidatePopularUrls(page: Int): List<String> {
        val hosts = listOf(baseUrl) + mirrorDomains.map { "https://$it" }
        val urls = mutableListOf<String>()
        for (h in hosts.distinct()) {
            urls.add(buildPopularUrl(page, h))
            if (page <= 1) {
                urls.add("$h/")
                urls.add("$h/series/")
            } else {
                urls.add("$h/series/?page=$page")
            }
        }
        return urls.distinct()
    }

    protected open fun buildSearchUrl(query: String, page: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return "$baseUrl/?s=$encoded&post_type=wp-manga"
    }

    /**
     * Parses manga catalog items from HTML using:
     * 1) Configured & multi-theme CSS selectors (Madara, MangaStream, Olympus/Team-X, Azora)
     * 2) Semantic work-link grouping (`a[href*=/manga/], a[href*=/series/]`) when a site uses custom/Tailwind classes
     */
    fun parseMangaSummariesFromHtml(html: String, pageBaseUri: String = baseUrl): List<MangaSummary> {
        val doc: Document = Jsoup.parse(html, pageBaseUri)
        val combinedSelector = "$itemSelector, div.page-item-detail, div.c-tabs-item__content, div.bsx, div.utao, div.uta, div.manga-item, article.item"
        val rawElements = doc.select(combinedSelector)
        // Filter out outer wrapper containers that contain other matched item cards inside them
        // so an outer wrapper (e.g. div.box around a list of cards) never steals "Magic emperor" onto another card!
        val elements = rawElements.filter { candidate ->
            rawElements.none { other -> other !== candidate && candidate.children().any { child -> child === other || child.select(combinedSelector).isNotEmpty() } }
        }

        val selectorParsed = elements.mapIndexedNotNull { idx, el ->
            val linkEl = el.selectFirst(
                ".post-title a[href], .luf a[href], h3 a[href], h4 a[href], .item-thumb a[href], .imgu a[href], .limit a[href], a[href*=/manga/], a[href*=/series/], a[href]"
            )
            val titleEl = el.selectFirst(titleSelector)
                ?: el.selectFirst(".post-title h3 a, .post-title a, .luf a h4, .luf h4, .luf a, .tt, h3 a, h4 a, a[title]")
                ?: linkEl

            val rawTitleCandidates = listOfNotNull(
                titleEl?.attr("title")?.trim(),
                titleEl?.text()?.trim(),
                el.selectFirst(".luf a h4, .luf h4, .post-title a, .tt, h3, h4")?.text()?.trim(),
                el.selectFirst("img[alt]")?.attr("alt")?.trim(),
                linkEl?.attr("title")?.trim(),
                linkEl?.text()?.trim()
            ).map { cleanExtractedMangaTitle(it) }
            val rawTitle = rawTitleCandidates.firstOrNull { isValidMangaTitleCandidate(it) }.orEmpty()

            val rawHref = linkEl?.attr("href")?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("#") }
                ?: titleEl?.attr("href")?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("#") }
                ?: ""

            if (rawTitle.isBlank() || rawHref.isBlank()) return@mapIndexedNotNull null

            val resolvedHref = HtmlImageExtractor.resolveUrl(pageBaseUri, rawHref)
                ?: return@mapIndexedNotNull null

            if (resolvedHref.contains("#") || resolvedHref.contains("/add/")) return@mapIndexedNotNull null

            val imgContainer = el.selectFirst(coverSelector) ?: el.selectFirst("img") ?: el
            val extractedCover = HtmlImageExtractor.extractImageUrl(imgContainer, pageBaseUri)
                ?: HtmlImageExtractor.extractImageUrl(el, pageBaseUri)

            val slug = resolvedHref.trimEnd('/').substringAfterLast('/').ifBlank { "item_$idx" }

            // Record any direct chapter links inside this card that belong to this specific work
            val cleanWorkBase = resolvedHref.trimEnd('/')
            val chapterLinksInCard = el.select(".list-chapter .chapter-item a[href], .luf ul li a[href], .latest-chap a[href], .chapter a[href], .epxs a[href]")
                .mapNotNull { chA ->
                    val chUrl = HtmlImageExtractor.resolveUrl(pageBaseUri, chA.attr("href")) ?: return@mapNotNull null
                    if (chUrl.contains("#") || chUrl.trimEnd('/') == cleanWorkBase) return@mapNotNull null
                    if (!chUrl.contains(slug, ignoreCase = true) && !chUrl.startsWith(cleanWorkBase)) return@mapNotNull null
                    chA.text().trim() to chUrl
                }
            if (chapterLinksInCard.isNotEmpty()) {
                cachedWorkChapterHints[cleanWorkBase] = chapterLinksInCard
            }

            val rawLatestEl = el.selectFirst("$latestChapterSelector, .list-chapter .chapter-item a, .luf ul li a, .chapter a, .epxs a, .epxs, .latest-chap a")
            val rawLatestHref = rawLatestEl?.attr("href")?.trim()?.takeIf { it.isNotBlank() }?.let { HtmlImageExtractor.resolveUrl(pageBaseUri, it) }
                ?: rawLatestEl?.selectFirst("a[href]")?.attr("href")?.trim()?.takeIf { it.isNotBlank() }?.let { HtmlImageExtractor.resolveUrl(pageBaseUri, it) }
            val latest = if (rawLatestHref.isNullOrBlank() || rawLatestHref.contains(slug, ignoreCase = true) || rawLatestHref.startsWith(cleanWorkBase)) {
                chapterLinksInCard.firstOrNull()?.first
                    ?: rawLatestEl?.text()?.trim()?.takeIf { it.length <= 45 && !it.equals(rawTitle, ignoreCase = true) }
            } else {
                chapterLinksInCard.firstOrNull()?.first
            }

            val genresText = el.select(".mg_genres a, .genres a, .tags a, .mgen a")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }

            val summary = MangaSummary(
                id = "${id}_$slug",
                sourceId = id,
                title = rawTitle,
                coverUrl = extractedCover,
                mangaUrl = resolvedHref,
                latestChapter = latest?.ifBlank { null },
                genres = genresText,
                rating = null,
                status = null
            )
            cachedWorkSummaries[cleanWorkBase] = summary
            summary
        }.distinctBy { it.id }

        // Step 2: Semantic extraction for custom/modern DOMs (e.g., azoramoon.com, mangatuk.com Next.js/Tailwind sites)
        // Also runs when selectorParsed only matched a small sidebar widget (< 20 items) so full page catalog is merged!
        val semanticResults = mutableListOf<MangaSummary>()
        if (selectorParsed.size < 20) {
            val candidateLinks = doc.select("a[href*=/manga/], a[href*=/series/]")
            val byWorkUrl = LinkedHashMap<String, MutableList<org.jsoup.nodes.Element>>()

            for (a in candidateLinks) {
                val resolved = HtmlImageExtractor.resolveUrl(pageBaseUri, a.attr("href")) ?: continue
                if (!isCanonicalMangaWorkUrl(resolved)) continue
                val normalizedUrl = resolved.trimEnd('/') + "/"
                byWorkUrl.getOrPut(normalizedUrl) { mutableListOf() }.add(a)
            }

            var idx = 0
            for ((workUrl, anchors) in byWorkUrl) {
                idx++
                val workSlug = workUrl.trimEnd('/').substringAfterLast('/')
                var title = ""
                var coverUrl: String? = null
                var latestChapter: String? = null

                for (a in anchors) {
                    // Use the tightest card container that only wraps this single work URL
                    val parent1 = a.parent()
                    val parent2 = parent1?.parent()
                    val card = when {
                        parent2 != null && parent2.select("a[href*=/manga/], a[href*=/series/]")
                            .mapNotNull { HtmlImageExtractor.resolveUrl(pageBaseUri, it.attr("href")) }
                            .filter { isCanonicalMangaWorkUrl(it) }
                            .map { it.trimEnd('/') }
                            .distinct().size <= 1 -> parent2
                        parent1 != null -> parent1
                        else -> a
                    }
                    if (coverUrl == null) {
                        coverUrl = HtmlImageExtractor.extractImageUrl(a, pageBaseUri)
                            ?: HtmlImageExtractor.extractImageUrl(parent1, pageBaseUri)
                            ?: HtmlImageExtractor.extractImageUrl(card, pageBaseUri)
                    }
                    if (title.isBlank()) {
                        val candidates = listOf(
                            a.attr("title").trim(),
                            a.selectFirst("img")?.attr("alt")?.trim().orEmpty(),
                            a.selectFirst("h2, h3, h4, .tt, .title, .post-title")?.text()?.trim().orEmpty(),
                            card.selectFirst("h2, h3, h4, .tt, .title, .post-title")?.text()?.trim().orEmpty(),
                            a.text().trim()
                        ).map { cleanExtractedMangaTitle(it) }
                        title = candidates.firstOrNull { isValidMangaTitleCandidate(it) }.orEmpty()
                    }
                    if (latestChapter == null) {
                        val chapEl = card.select("a, span").firstOrNull { el ->
                            val t = el.text().trim()
                            val href = el.attr("href").trim()
                            val belongsToWork = href.isEmpty() || href.contains(workSlug, ignoreCase = true)
                            belongsToWork &&
                                (t.contains("فصل") || t.contains("Chapter", ignoreCase = true)) &&
                                t.length <= 28 &&
                                !t.contains(title, ignoreCase = true)
                        }
                        latestChapter = chapEl?.text()?.trim()
                    }
                }

                if (title.isNotBlank() && !coverUrl.isNullOrBlank()) {
                    val slug = workSlug.ifBlank { "work_$idx" }
                    val summary = MangaSummary(
                        id = "${id}_$slug",
                        sourceId = id,
                        title = title,
                        coverUrl = coverUrl,
                        mangaUrl = workUrl,
                        latestChapter = latestChapter,
                        genres = emptyList(),
                        rating = null,
                        status = null
                    )
                    cachedWorkSummaries[workUrl.trimEnd('/')] = summary
                    semanticResults.add(summary)
                }
            }
        }

        return (selectorParsed + semanticResults).distinctBy { it.id }
    }

    private fun isCanonicalMangaWorkUrl(url: String): Boolean {
        if (url.contains("#")) return false
        val clean = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val path = clean.substringAfter("://").substringAfter('/', "")
        if (path.isBlank()) return false
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.size != 2) return false
        val prefix = segments[0].lowercase()
        val slug = segments[1].lowercase()
        if (prefix != "manga" && prefix != "series") return false
        if (slug in listOf("page", "genre", "tag", "status", "type", "list", "add")) return false
        return true
    }

    private fun isValidMangaTitleCandidate(text: String): Boolean {
        val clean = text.trim()
        if (clean.length < 2 || clean.length > 140) return false
        val lower = clean.lowercase()
        if (lower in listOf(
                "مانجا", "مانهوا", "مكتمل", "مستمر", "جديد", "المزيد", "عرض الكل",
                "access denied", "403 forbidden", "just a moment...", "skip to content"
            )
        ) {
            return false
        }
        if (Regex("""^(الفصل|فصل|chapter|ch\.?)\s*\d+.*$""", RegexOption.IGNORE_CASE).matches(clean)) {
            return false
        }
        return true
    }

    /**
     * Parses manga details and embedded chapters from HTML.
     */
    fun parseDetailsFromHtml(html: String, mangaUrl: String): MangaDetails? {
        if (isAccessDeniedHtml(html)) return null
        val doc = Jsoup.parse(html, mangaUrl)
        val rawTitle = doc.selectFirst("$detailsTitleSelector, .post-title h1, h1.entry-title, .author-info-title h1, h1")?.text()?.trim()
            ?.let { cleanExtractedMangaTitle(it) }
            ?.takeIf { isValidMangaTitleCandidate(it) }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.substringBefore(" - ")
                ?.substringBefore(" | ")
                ?.trim()
                ?.let { cleanExtractedMangaTitle(it) }
                ?.takeIf { isValidMangaTitleCandidate(it) }
            ?: return null

        val coverEl = doc.selectFirst(".summary_image img, .thumb img, .manga-poster img, .seriestucontl img, img.wp-post-image, .whitebox img")
        val cover = HtmlImageExtractor.extractImageUrl(coverEl, mangaUrl)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.let {
                HtmlImageExtractor.resolveUrl(mangaUrl, it)
            }
        val desc = doc.select("$detailsDescSelector, .summary__content p, .description-summary p, .entry-content p, .review-content p, .summary__content")
            .text()
            .trim()
            .ifBlank {
                doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim().orEmpty()
            }
            .ifBlank { "" }
        val genres = doc.select("$detailsGenresSelector, .genres-content a, .mgen a, .seriestugenre a, .review-author-info a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .ifEmpty { emptyList() }
        val author = doc.selectFirst("$detailsAuthorSelector, .author-content a, .fmed b:contains(المؤلف) + span")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val artist = doc.selectFirst(".artist-content a, .fmed b:contains(الرسام) + span")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val altTitles = doc.select(".post-content_item:contains(أسماء أخرى) .summary-content, .post-content_item:contains(Alternative) .summary-content, .wd-full:contains(أسماء) span")
            .text()
            .split(',', '،', '/', ';')
            .map { it.trim() }
            .filter { it.length >= 2 }

        val statusRaw = doc.select("$detailsStatusSelector, .post-status .summary-content, .tsinfo .imptdt:contains(الحالة), .post-content_item:contains(الحالة) .summary-content, .status")
            .text()
            .trim()
        val status = parseNormalizedMangaStatus(statusRaw)
        val rating = parseRatingFromDoc(doc)

        val slug = mangaUrl.trimEnd('/').substringAfterLast('/').ifBlank { "manga" }
        val mangaId = "${id}_$slug"
        val chapters = parseChaptersFromDoc(doc, mangaId, mangaUrl)

        return MangaDetails(
            id = mangaId,
            sourceId = id,
            title = rawTitle,
            alternativeTitles = altTitles,
            coverUrl = cover,
            description = desc,
            author = author,
            artist = artist,
            status = status,
            genres = genres,
            rating = rating,
            chapters = chapters
        )
    }

    protected fun parseChaptersFromDoc(doc: Document, mangaId: String, pageBaseUri: String = baseUrl): List<MangaChapter> {
        val rows = doc.select("$chapterRowSelector, li.wp-manga-chapter, #chapterlist li, .eplister ul li, .listing-chapters_wrap li, .chapter-li, div.chapter-item")
        val rawList = mutableListOf<MangaChapter>()

        if (rows.isNotEmpty()) {
            rows.forEachIndexed { idx, row ->
                val link = row.selectFirst("a[href]") ?: return@forEachIndexed
                val rawAttrHref = link.attr("href").trim()
                if (!isValidChapterHref(rawAttrHref, pageBaseUri)) return@forEachIndexed

                val href = HtmlImageExtractor.resolveUrl(pageBaseUri, rawAttrHref) ?: return@forEachIndexed
                if (!isValidChapterHref(href, pageBaseUri)) return@forEachIndexed

                val rawTitle = row.selectFirst(".chapternum, .epl-num, .epl-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: link.text().trim().ifEmpty { "الفصل ${idx + 1}" }
                if (rawTitle.equals("Skip to content", ignoreCase = true)) return@forEachIndexed

                val remoteSlug = href.trimEnd('/').substringAfterLast('/').ifBlank { "ch_$idx" }
                val numMatch = Regex("""(\d+(\.\d+)?)""").find(remoteSlug)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                    ?: Regex("""(\d+(\.\d+)?)""").find(rawTitle)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                val dateText = row.selectFirst(".chapter-release-date, .chapterdate, .epl-date, span.date")?.text()?.trim().orEmpty()

                rawList.add(
                    MangaChapter(
                        id = "${mangaId}_$remoteSlug",
                        mangaId = mangaId,
                        sourceId = id,
                        remoteId = href,
                        title = rawTitle,
                        chapterNumber = numMatch ?: (rows.size - idx).toFloat(),
                        uploadDate = null,
                        dateLabel = dateText.ifBlank { "متاح" }
                    )
                )
            }
        }

        // Semantic fallback: find any <a href> on the details page that points to a valid chapter sub-path of pageBaseUri
        // OR matches the work slug in `/series/{slug}/{chapter}` or `/manga/{slug}/{chapter}` (Next.js / custom sites)
        if (rawList.isEmpty()) {
            val baseWorkPrefix = pageBaseUri.substringBefore('?').substringBefore('#').trimEnd('/') + "/"
            val workSlug = pageBaseUri.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
            val subLinks = doc.select("a[href]")
            var idx = 0
            for (link in subLinks) {
                val rawAttrHref = link.attr("href").trim()
                if (!isValidChapterHref(rawAttrHref, pageBaseUri)) continue
                val href = HtmlImageExtractor.resolveUrl(pageBaseUri, rawAttrHref) ?: continue
                if (!isValidChapterHref(href, pageBaseUri)) continue

                val cleanHref = href.substringBefore('?').substringBefore('#').trimEnd('/')
                val isSubPathOfWork = href.startsWith(baseWorkPrefix) && cleanHref != pageBaseUri.trimEnd('/')
                val isSlugChapterPath = workSlug.isNotBlank() &&
                    (cleanHref.contains("/manga/$workSlug/", ignoreCase = true) ||
                        cleanHref.contains("/series/$workSlug/", ignoreCase = true))

                if (isSubPathOfWork || isSlugChapterPath) {
                    val slug = cleanHref.substringAfterLast('/')
                    val rawTitle = link.text().trim().ifEmpty { link.attr("title").trim() }.ifEmpty { "الفصل $slug" }
                    if (rawTitle.equals("Skip to content", ignoreCase = true)) continue

                    idx++
                    val numMatch = Regex("""(\d+(\.\d+)?)""").find(slug)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                        ?: Regex("""(\d+(\.\d+)?)""").find(rawTitle)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                    rawList.add(
                        MangaChapter(
                            id = "${mangaId}_$slug",
                            mangaId = mangaId,
                            sourceId = id,
                            remoteId = href,
                            title = rawTitle,
                            chapterNumber = numMatch ?: idx.toFloat(),
                            uploadDate = null,
                            dateLabel = "متاح"
                        )
                    )
                }
            }
        }

        return rawList
            .distinctBy { "${it.sourceId}:${it.remoteId}" }
            .sortedBy { it.chapterNumber ?: 0f }
    }

    private fun isValidChapterHref(href: String, pageBaseUri: String): Boolean {
        val clean = href.trim()
        if (clean.isEmpty() || clean.startsWith("#") || clean.contains("#")) return false
        val lower = clean.lowercase()
        if (lower.startsWith("javascript:") ||
            lower.contains("/add/bookmark") ||
            lower.contains("/add/favorite") ||
            lower.contains("/login") ||
            lower.contains("/register") ||
            lower.contains("wp-login")
        ) {
            return false
        }
        val cleanBase = pageBaseUri.substringBefore('?').substringBefore('#').trimEnd('/')
        val cleanTarget = clean.substringBefore('?').substringBefore('#').trimEnd('/')
        if (cleanTarget == cleanBase) return false
        return true
    }

    /**
     * Supports WordPress Madara asynchronous chapter endpoints:
     * 1) POST {mangaUrl}/ajax/chapters/
     * 2) POST {origin}/wp-admin/admin-ajax.php (action=manga_get_chapters&manga={postId})
     */
    private suspend fun fetchAjaxMadaraChapters(
        detailsHtml: String,
        mangaUrl: String,
        mangaId: String
    ): List<MangaChapter> {
        val ajaxUrl = "${mangaUrl.trimEnd('/')}/ajax/chapters/"
        try {
            val reqBuilder = Request.Builder()
                .url(ajaxUrl)
                .post("".toRequestBody(null))
                .header("User-Agent", VerificationManager.getUserAgent())
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", mangaUrl)
            VerificationManager.getCookieHeader(ajaxUrl)?.let { reqBuilder.header("Cookie", it) }
            val resp = withTimeoutOrNull(5_000L) { httpClient.newCall(reqBuilder.build()).awaitResponse() }
            resp?.use { r ->
                if (r.isSuccessful) {
                    val ajaxHtml = r.body?.string().orEmpty()
                    val parsed = parseChaptersFromDoc(Jsoup.parse(ajaxHtml, mangaUrl), mangaId, mangaUrl)
                    if (parsed.isNotEmpty()) return parsed
                }
            }
        } catch (_: Exception) {
        }

        val doc = Jsoup.parse(detailsHtml, mangaUrl)
        val postId = doc.selectFirst("#manga-chapters-holder[data-id]")?.attr("data-id")?.trim()
            ?: doc.selectFirst("input.rating-post-id[value]")?.attr("value")?.trim()
            ?: doc.selectFirst("a.wp-manga-action-button[data-post]")?.attr("data-post")?.trim()
            ?: Regex(""""manga_id"\s*:\s*"?(\d+)"?""").find(detailsHtml)?.groupValues?.getOrNull(1)
            ?: Regex("""postid-(\d+)""").find(doc.selectFirst("body")?.className().orEmpty())?.groupValues?.getOrNull(1)
        if (!postId.isNullOrBlank()) {
            try {
                val origin = extractOrigin(mangaUrl)
                val adminAjaxUrl = "$origin/wp-admin/admin-ajax.php"
                val form = FormBody.Builder()
                    .add("action", "manga_get_chapters")
                    .add("manga", postId)
                    .build()
                val reqBuilder = Request.Builder()
                    .url(adminAjaxUrl)
                    .post(form)
                    .header("User-Agent", VerificationManager.getUserAgent())
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", mangaUrl)
                VerificationManager.getCookieHeader(adminAjaxUrl)?.let { reqBuilder.header("Cookie", it) }
                val resp = withTimeoutOrNull(5_000L) { httpClient.newCall(reqBuilder.build()).awaitResponse() }
                resp?.use { r ->
                    if (r.isSuccessful) {
                        val ajaxHtml = r.body?.string().orEmpty()
                        val parsed = parseChaptersFromDoc(Jsoup.parse(ajaxHtml, mangaUrl), mangaId, mangaUrl)
                        if (parsed.isNotEmpty()) return parsed
                    }
                }
            } catch (_: Exception) {
            }
        }

        return emptyList()
    }

    // ─── LIVE MANGADEX REST API V5 BRIDGE ───

    protected suspend fun fetchLiveManhwaFromApi(page: Int, searchQuery: String?): List<MangaSummary> {
        val offset = ((page - 1).coerceAtLeast(0)) * 20
        val apiUrl = if (!searchQuery.isNullOrBlank()) {
            val encoded = URLEncoder.encode(searchQuery.trim(), "UTF-8")
            "https://api.mangadex.org/manga?limit=20&offset=$offset&title=$encoded&includes[]=cover_art&includes[]=author&includes[]=artist&contentRating[]=safe&contentRating[]=suggestive&hasAvailableChapters=true"
        } else {
            "https://api.mangadex.org/manga?limit=20&offset=$offset&includes[]=cover_art&includes[]=author&includes[]=artist&contentRating[]=safe&contentRating[]=suggestive&hasAvailableChapters=true&$liveApiQueryParams"
        }

        return try {
            PipelineLogger.log(
                stage = PipelineStage.NETWORK_REQUEST_STARTED,
                sourceId = id,
                sourceName = name,
                status = "API_REQUEST_STARTED",
                details = "GET $apiUrl"
            )
            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "MangaReaderAndroid/2.0 (Kotlin; JetpackCompose)")
                .header("Accept", "application/json")
                .build()

            val response = withTimeoutOrNull(8_000L) {
                httpClient.newCall(req).awaitResponse()
            } ?: return emptyList()

            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return emptyList()
                MangaDexApiHelper.parseMangaListResponse(body, sourceId = id)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    protected suspend fun fetchLiveMangaDexDetails(mdUuid: String): MangaDetails? {
        val detailsUrl = "https://api.mangadex.org/manga/$mdUuid?includes[]=cover_art&includes[]=author&includes[]=artist"
        val chapters = fetchLiveMangaDexChapters(mdUuid, "${id}_$mdUuid")

        return try {
            val req = Request.Builder()
                .url(detailsUrl)
                .header("User-Agent", "MangaReaderAndroid/2.0")
                .header("Accept", "application/json")
                .build()
            val response = withTimeoutOrNull(7_000L) {
                httpClient.newCall(req).awaitResponse()
            } ?: return null

            response.use { resp ->
                if (!resp.isSuccessful) return null
                val json = resp.body?.string().orEmpty()
                MangaDexApiHelper.parseMangaSingleDetailsResponse(
                    jsonString = json,
                    sourceId = id,
                    chapters = chapters
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    protected suspend fun fetchLiveMangaDexChapters(mdUuid: String, mangaId: String): List<MangaChapter> {
        val feedUrl = "https://api.mangadex.org/manga/$mdUuid/feed?limit=96&offset=0&translatedLanguage[]=ar&translatedLanguage[]=en&order[chapter]=asc&includeExternalUrl=0&contentRating[]=safe&contentRating[]=suggestive"
        return try {
            val req = Request.Builder()
                .url(feedUrl)
                .header("User-Agent", "MangaReaderAndroid/2.0")
                .header("Accept", "application/json")
                .build()
            val response = withTimeoutOrNull(7_000L) {
                httpClient.newCall(req).awaitResponse()
            } ?: return emptyList()

            response.use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val json = resp.body?.string().orEmpty()
                val list = MangaDexApiHelper.parseChaptersFeedResponse(json, mangaId = mangaId, sourceId = id)
                if (list.isNotEmpty()) {
                    list
                } else {
                    val anyLangUrl = "https://api.mangadex.org/manga/$mdUuid/feed?limit=50&offset=0&order[chapter]=asc&includeExternalUrl=0"
                    val fallbackReq = Request.Builder()
                        .url(anyLangUrl)
                        .header("User-Agent", "MangaReaderAndroid/2.0")
                        .build()
                    val fallbackResp = withTimeoutOrNull(6_000L) {
                        httpClient.newCall(fallbackReq).awaitResponse()
                    }
                    fallbackResp?.use { r2 ->
                        if (r2.isSuccessful) {
                            MangaDexApiHelper.parseChaptersFeedResponse(
                                r2.body?.string().orEmpty(),
                                mangaId = mangaId,
                                sourceId = id
                            )
                        } else emptyList()
                    } ?: emptyList()
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    protected suspend fun fetchLiveMangaDexPages(atHomeUrl: String): List<MangaPage> {
        return try {
            val req = Request.Builder()
                .url(atHomeUrl)
                .header("User-Agent", "MangaReaderAndroid/2.0")
                .header("Accept", "application/json")
                .build()
            val response = withTimeoutOrNull(8_000L) {
                httpClient.newCall(req).awaitResponse()
            } ?: return emptyList()

            response.use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val json = resp.body?.string().orEmpty()
                MangaDexApiHelper.parseAtHomePagesResponse(json)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    protected fun extractMangaDexUuid(urlOrId: String): String? {
        val uuidRegex = Regex("""([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})""")
        return uuidRegex.find(urlOrId)?.groupValues?.getOrNull(1)
    }
}

/**
 * Dynamic Smart Site Adapter (`DynamicSiteSource`):
 * Automatically adapts to ANY manga/manhwa website added manually by the user (e.g. `starzmanga.com`,
 * `mangadar.com`, `swatmanga.com`, `prochan.net`, or any WordPress Madara / MangaStream / custom site).
 */
class DynamicSiteSource(
    domain: String,
    customId: String? = null,
    customName: String? = null,
    customIconText: String? = null,
    customIconColorHex: Long? = null,
    requiresVerificationOverride: Boolean = false
) : ConfigurableHtmlMangaSource(
    id = customId ?: "site_${domain.lowercase().replace('.', '_').replace('-', '_')}",
    name = customName?.takeIf { it.isNotBlank() }
        ?: domain.substringBefore('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
    domain = domain.lowercase(),
    iconText = customIconText ?: domain.take(1).uppercase(),
    iconColorHex = customIconColorHex ?: computeColorForDomain(domain),
    requiresCloudflareVerification = requiresVerificationOverride,
    mirrorDomains = emptyList(),
    itemSelector = "div.page-item-detail, div.c-tabs-item__content, div.bsx, div.utao, div.uta, div.box, div.manga-item, article.item",
    titleSelector = ".post-title h3 a, .post-title a, .luf a h4, .luf a, .tt, h3 a, h4 a, a[title]",
    coverSelector = ".item-thumb, .tab-thumb, .limit, .imgu, .manga-poster",
    latestChapterSelector = ".list-chapter .chapter-item:first-child .chapter a, .latest-chap .chapter a, .luf ul li a, .epxs, .chapter a",
    detailsTitleSelector = ".post-title h1, h1.entry-title, .author-info-title h1, h1",
    detailsDescSelector = ".summary__content p, .description-summary p, .entry-content p, .review-content p, .summary__content",
    detailsGenresSelector = ".genres-content a, .mgen a, .seriestugenre a, .review-author-info a",
    detailsAuthorSelector = ".author-content a, .fmed b:contains(المؤلف) + span",
    detailsStatusSelector = ".post-status .summary-content, .tsinfo .imptdt:contains(الحالة) i",
    chapterRowSelector = "li.wp-manga-chapter, #chapterlist li, .eplister ul li, .listing-chapters_wrap li",
    readerImagesSelector = ".reading-content img, .page-break img, #readerarea img, img.wp-manga-chapter-img, img.manga-chapter-img"
) {
    companion object {
        private val PALETTE = listOf(
            0xFFD32F2F,
            0xFF1976D2,
            0xFF388E3C,
            0xFF7B1FA2,
            0xFFF57C00,
            0xFF00796B,
            0xFF3949AB,
            0xFFC2185B
        )

        fun computeColorForDomain(domain: String): Long {
            val hash = kotlin.math.abs(domain.hashCode())
            return PALETTE[hash % PALETTE.size]
        }
    }
}

/**
 * 1. MangaDar Source (MangaDar - mangadar.com / mangadar.net)
 */
class MangaDarSource : ConfigurableHtmlMangaSource(
    id = "mangadar",
    name = "MangaDar",
    domain = "mangadar.com",
    iconText = "D",
    iconColorHex = 0xFF00897B,
    requiresCloudflareVerification = true,
    mirrorDomains = listOf("mangadar.net", "mangadar.org"),
    itemSelector = "div.page-item-detail, div.bsx, div.manga-item",
    titleSelector = ".post-title h3 a, .post-title a, .tt, h3 a",
    coverSelector = ".item-thumb, .limit, .manga-poster",
    latestChapterSelector = ".list-chapter .chapter-item:first-child .chapter a, .epxs, .chapter a",
    detailsTitleSelector = ".post-title h1, h1.entry-title, h1",
    detailsDescSelector = ".summary__content p, .entry-content p, .summary__content",
    detailsGenresSelector = ".genres-content a, .mgen a",
    detailsAuthorSelector = ".author-content a",
    detailsStatusSelector = ".post-status .summary-content, .tsinfo .imptdt:contains(الحالة) i",
    chapterRowSelector = "li.wp-manga-chapter, #chapterlist li, .eplister ul li",
    readerImagesSelector = ".reading-content img, #readerarea img, .page-break img"
)

/**
 * 2. Mangalek Source (مانجا ليك Mangalek - mangalik.net / lekmanga.net / starzmanga.com)
 */
class MangalekSource : ConfigurableHtmlMangaSource(
    id = "mangalek",
    name = "مانجا ليك Mangalek",
    domain = "mangalik.net",
    iconText = "M",
    iconColorHex = 0xFFD32F2F,
    requiresCloudflareVerification = false,
    mirrorDomains = listOf("lekmanga.net", "starzmanga.com"),
    itemSelector = "div.page-item-detail, div.c-tabs-item__content",
    titleSelector = ".post-title h3 a, .post-title a, h3 a",
    coverSelector = ".item-thumb, .tab-thumb",
    latestChapterSelector = ".list-chapter .chapter-item:first-child .chapter a, .latest-chap .chapter a",
    detailsTitleSelector = ".post-title h1",
    detailsDescSelector = ".summary__content p, .description-summary p, .summary__content",
    detailsGenresSelector = ".genres-content a",
    detailsAuthorSelector = ".author-content a",
    detailsStatusSelector = ".post-status .summary-content",
    chapterRowSelector = "li.wp-manga-chapter",
    readerImagesSelector = ".reading-content img, .page-break img, img.wp-manga-chapter-img",
    liveApiQueryParams = "originalLanguage[]=ko&order[followedCount]=desc"
)

/**
 * 3. Azora Manga Source (ازورا مانجا - Azora Manga - azoramoon.com / azorafly.com / azoramanga.com)
 */
class AzoraMangaSource : ConfigurableHtmlMangaSource(
    id = "azora",
    name = "ازورا مانجا - Azora Manga",
    domain = "azoramoon.com",
    iconText = "A",
    iconColorHex = 0xFFFB8C00,
    requiresCloudflareVerification = false,
    mirrorDomains = listOf("azorafly.com", "azoramanga.com"),
    itemSelector = "div.page-item-detail, div.bsx",
    titleSelector = ".post-title h3 a, .post-title a, .tt, a[title]",
    coverSelector = ".item-thumb, .limit",
    latestChapterSelector = ".chapter-item:first-child .chapter a, .epxs",
    detailsTitleSelector = ".post-title h1, h1",
    detailsDescSelector = ".summary__content p, .summary__content",
    detailsGenresSelector = ".genres-content a",
    detailsAuthorSelector = ".author-content a",
    detailsStatusSelector = ".post-status .summary-content",
    chapterRowSelector = "li.wp-manga-chapter",
    readerImagesSelector = ".reading-content img, .page-break img",
    liveApiQueryParams = "originalLanguage[]=ko&order[createdAt]=desc"
) {
    override fun buildCandidatePopularUrls(page: Int): List<String> {
        return if (page <= 1) {
            listOf(
                "https://azoramoon.com/",
                "https://azoramoon.com/series/",
                "https://azorafly.com/series/",
                "https://azorafly.com/",
                "https://azoramanga.com/manga/"
            )
        } else {
            listOf(
                "https://azoramoon.com/series/?page=$page",
                "https://azorafly.com/series/?page=$page",
                "https://azoramanga.com/manga/page/$page/"
            )
        }
    }
}

/**
 * 4. Mangatek Source (مانجاتك - Mangatek - mangatek.com / mangatuk.com)
 */
class MangatekSource(
    requiresVerificationOverride: Boolean = false
) : ConfigurableHtmlMangaSource(
    id = "mangatek",
    name = "مانجاتك - Mangatek",
    domain = "mangatek.com",
    iconText = "M",
    iconColorHex = 0xFF3949AB,
    requiresCloudflareVerification = requiresVerificationOverride,
    mirrorDomains = listOf("mangatuk.com"),
    itemSelector = "div.manga-item, div.bsx, div.page-item-detail",
    titleSelector = "h3 a, .post-title a, .tt, a[title]",
    coverSelector = ".manga-poster, .limit, .item-thumb",
    latestChapterSelector = ".chapter a, .epxs",
    detailsTitleSelector = "h1.post-title, h1.entry-title, h1",
    detailsDescSelector = ".summary__content, .entry-content p",
    detailsGenresSelector = ".genres-content a, .mgen a",
    detailsAuthorSelector = ".author-content a",
    detailsStatusSelector = ".post-status .summary-content",
    chapterRowSelector = "li.wp-manga-chapter, #chapterlist li, .eplister ul li",
    readerImagesSelector = ".reading-content img, #readerarea img",
    liveApiQueryParams = "originalLanguage[]=ko&order[latestUploadedChapter]=desc"
) {
    override fun buildCandidatePopularUrls(page: Int): List<String> {
        return if (page <= 1) {
            listOf(
                "https://mangatek.com/manga/",
                "https://mangatek.com/",
                "https://mangatuk.com/",
                "https://mangatuk.com/series/"
            )
        } else {
            listOf(
                "https://mangatek.com/manga/page/$page/",
                "https://mangatuk.com/series/?page=$page",
                "https://mangatuk.com/?page=$page"
            )
        }
    }
}

/**
 * 5. ProComic Source (ProComic - procomic.net / prochan.net)
 */
class ProComicSource : ConfigurableHtmlMangaSource(
    id = "procomic",
    name = "ProComic",
    domain = "procomic.net",
    iconText = "P",
    iconColorHex = 0xFF5E35B1,
    requiresCloudflareVerification = false,
    mirrorDomains = listOf("prochan.net"),
    itemSelector = "div.page-item-detail, div.bsx, div.manga-item",
    titleSelector = ".post-title h3 a, .post-title a, .tt, h3 a",
    coverSelector = ".item-thumb, .limit, .manga-poster",
    latestChapterSelector = ".list-chapter .chapter-item:first-child .chapter a, .epxs, .chapter a",
    detailsTitleSelector = ".post-title h1, h1.entry-title, h1",
    detailsDescSelector = ".summary__content p, .entry-content p, .summary__content",
    detailsGenresSelector = ".genres-content a, .mgen a",
    detailsAuthorSelector = ".author-content a",
    detailsStatusSelector = ".post-status .summary-content",
    chapterRowSelector = "li.wp-manga-chapter, #chapterlist li",
    readerImagesSelector = ".reading-content img, #readerarea img"
)

/**
 * 6. Mangastarz Source (مانجا ستارز Mangastarz - starzmanga.com)
 */
class MangastarzSource : ConfigurableHtmlMangaSource(
    id = "mangastarz",
    name = "مانجا ستارز Mangastarz",
    domain = "starzmanga.com",
    iconText = "S",
    iconColorHex = 0xFF7B1FA2,
    requiresCloudflareVerification = false,
    mirrorDomains = listOf("mangalik.net", "lekmanga.net"),
    itemSelector = "div.page-item-detail, div.c-tabs-item__content",
    titleSelector = ".post-title h3 a, .post-title a, h3 a",
    coverSelector = ".item-thumb, .tab-thumb",
    latestChapterSelector = ".list-chapter .chapter-item:first-child .chapter a, .latest-chap .chapter a",
    detailsTitleSelector = ".post-title h1",
    detailsDescSelector = ".summary__content p, .description-summary p, .summary__content",
    detailsGenresSelector = ".genres-content a",
    detailsAuthorSelector = ".author-content a",
    detailsStatusSelector = ".post-status .summary-content",
    chapterRowSelector = "li.wp-manga-chapter",
    readerImagesSelector = ".reading-content img, .page-break img, img.wp-manga-chapter-img",
    liveApiQueryParams = "originalLanguage[]=ko&order[followedCount]=desc"
)

/**
 * 7. Team-X Source (Team-X - olympustaff.com)
 */
class TeamXSource : ConfigurableHtmlMangaSource(
    id = "teamx",
    name = "Team-X",
    domain = "olympustaff.com",
    iconText = "X",
    iconColorHex = 0xFF1E293B,
    requiresCloudflareVerification = false,
    itemSelector = "div.uta, div.bsx, div.utao, div.page-item-detail",
    titleSelector = ".luf a h4, .luf a, .tt, h3 a, a[title]",
    coverSelector = ".imgu, .limit, .item-thumb",
    latestChapterSelector = ".luf ul li:first-child a, .epxs, .adds .epxs, .chapter a",
    detailsTitleSelector = ".author-info-title h1, h1.entry-title, h1",
    detailsDescSelector = ".review-content p, .entry-content p, .summary__content",
    detailsGenresSelector = ".review-author-info a, .mgen a, .genres-content a",
    detailsAuthorSelector = ".fmed b:contains(المؤلف) + span, .author-content a",
    detailsStatusSelector = ".tsinfo .imptdt:contains(الحالة) i, .post-status",
    chapterRowSelector = ".eplister ul li, #chapterlist li, li.wp-manga-chapter",
    readerImagesSelector = "img.manga-chapter-img, .page-break img, #readerarea img, .reading-content img",
    liveApiQueryParams = "originalLanguage[]=ko&originalLanguage[]=zh&order[rating]=desc"
) {
    override fun buildPopularUrl(page: Int, hostBaseUrl: String): String {
        return if (page <= 1) "$hostBaseUrl/" else "$hostBaseUrl/series?page=$page"
    }
}

/**
 * 8. Swat Manga Source (Swat Manga - swatmanga.com / meshmanga.com)
 */
class SwatMangaSource : ConfigurableHtmlMangaSource(
    id = "swatmanga",
    name = "Swat Manga",
    domain = "swatmanga.com",
    iconText = "SW",
    iconColorHex = 0xFF0288D1,
    requiresCloudflareVerification = false,
    mirrorDomains = listOf("meshmanga.com", "swatmanhua.com"),
    itemSelector = "div.bsx, div.utao, div.uta, div.page-item-detail",
    titleSelector = ".tt, .luf a h4, .luf a, .post-title a, h3 a, a[title]",
    coverSelector = ".limit, .imgu, .item-thumb",
    latestChapterSelector = ".epxs, .luf ul li:first-child a, .chapter a",
    detailsTitleSelector = "h1.entry-title, .post-title h1, h1",
    detailsDescSelector = ".entry-content p, .summary__content p, .summary__content",
    detailsGenresSelector = ".mgen a, .seriestugenre a, .genres-content a",
    detailsAuthorSelector = ".fmed b:contains(المؤلف) + span, .author-content a",
    detailsStatusSelector = ".tsinfo .imptdt:contains(الحالة) i, .post-status .summary-content",
    chapterRowSelector = "#chapterlist li, .eplister ul li, li.wp-manga-chapter",
    readerImagesSelector = "#readerarea img, .reading-content img, .page-break img"
) {
    override fun buildCandidatePopularUrls(page: Int): List<String> {
        return if (page <= 1) {
            listOf(
                "https://swatmanga.com/manga/",
                "https://swatmanga.com/",
                "https://meshmanga.com/manga/",
                "https://meshmanga.com/series/",
                "https://meshmanga.com/"
            )
        } else {
            listOf(
                "https://swatmanga.com/manga/?page=$page",
                "https://meshmanga.com/manga/?page=$page",
                "https://meshmanga.com/series/?page=$page"
            )
        }
    }
}

/**
 * 9. Official MangaDex Public REST API v5 Connector (mangadex.org)
 */
class MangaDexSource : ConfigurableHtmlMangaSource(
    id = "mangadex",
    name = "MangaDex Official",
    domain = "mangadex.org",
    iconText = "MD",
    iconColorHex = 0xFFFF6740,
    requiresCloudflareVerification = false,
    itemSelector = "div.manga-card",
    titleSelector = "a.title",
    coverSelector = "img",
    latestChapterSelector = ".chapter",
    detailsTitleSelector = "h1",
    detailsDescSelector = ".description",
    detailsGenresSelector = ".tag",
    detailsAuthorSelector = ".author",
    detailsStatusSelector = ".status",
    chapterRowSelector = ".chapter-row",
    readerImagesSelector = "img",
    liveApiQueryParams = "originalLanguage[]=ko&originalLanguage[]=ja&availableTranslatedLanguage[]=ar&availableTranslatedLanguage[]=en&order[followedCount]=desc"
) {
    override suspend fun getPopular(page: Int): PageResult<MangaSummary> = withContext(Dispatchers.IO) {
        val items = fetchLiveManhwaFromApi(page = page, searchQuery = null)
        if (items.isEmpty()) {
            throw IllegalStateException("تعذّر جلب الأعمال من واجهة MangaDex الرسمية")
        }
        PageResult(items = items, page = page, hasNextPage = true)
    }

    override suspend fun search(query: String, page: Int): PageResult<MangaSummary> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isEmpty()) return@withContext getPopular(page)
        val items = fetchLiveManhwaFromApi(page = page, searchQuery = clean)
        PageResult(items = items, page = page, hasNextPage = false)
    }
}

/**
 * Single source of truth shared by UI and background workers.
 * Includes all 8 sources from the original reference app + MangaDex Official (total 9 specialized connectors).
 */
object BuiltInSourceCatalog {
    fun createAll(): List<com.example.sourceapi.MangaSource> = listOf(
        MangaDarSource(),
        MangalekSource(),
        AzoraMangaSource(),
        MangatekSource(),
        ProComicSource(),
        MangastarzSource(),
        TeamXSource(),
        SwatMangaSource(),
        MangaDexSource()
    )
}
