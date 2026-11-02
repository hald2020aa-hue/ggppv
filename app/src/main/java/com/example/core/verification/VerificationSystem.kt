package com.example.core.verification

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.core.common.PipelineLogger
import com.example.core.common.PipelineStage
import com.example.core.common.normalizeDomain
import com.example.core.storage.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Section 6 & 7: Explicit states for the Universal Website Verification System.
 */
enum class VerificationState(val labelAr: String, val badgeColorHex: Long) {
    NOT_REQUIRED("لا يتطلب تحققاً أمنياً", 0xFF4CAF50),
    REQUIRED("يتطلب هذا الموقع إكمال التحقق الأمني", 0xFFFF9800),
    IN_PROGRESS("جارٍ إكمال التحقق الأمني...", 0xFF2196F3),
    COMPLETED("تم إكمال التحقق الأمني بنجاح", 0xFF4CAF50),
    EXPIRED("انتهت صلاحية جلسة التحقق الأمني", 0xFFFFB300),
    FAILED("فشل التحقق الأمني أو تعذّر الاتصال", 0xFFEF5350),
    CANCELLED("تم إلغاء التحقق الأمني من قبل المستخدم", 0xFF9E9E9E)
}

/**
 * Categories of security verification challenges detected by [VerificationDetector].
 */
enum class VerificationChallengeType(val descriptionAr: String) {
    NONE("صفحة عادية"),
    CLOUDFLARE_CHALLENGE("تحقق Cloudflare الأمني"),
    HUMAN_VERIFICATION("صفحة التحقق البشري (CAPTCHA)"),
    BROWSER_INTEGRITY_CHECK("فحص سلامة المتصفح"),
    SECURITY_WAITING_ROOM("صفحة الانتظار الأمنية"),
    CONTENT_BLOCKING_CHALLENGE("صفحة تحقق تمنع تحميل المحتوى")
}

/**
 * Detailed outcome of inspecting an HTTP response / HTML document for security verification challenges.
 */
data class VerificationDetectionResult(
    val isVerificationRequired: Boolean,
    val challengeType: VerificationChallengeType = VerificationChallengeType.NONE,
    val httpCode: Int = 200,
    val reasonAr: String = "",
    val matchedIndicators: List<String> = emptyList()
)

/**
 * Exception thrown or reported by a [com.example.sourceapi.MangaSource] when a source requires
 * interactive human verification before content can be accessed.
 */
class VerificationRequiredException(
    val sourceId: String,
    val domain: String,
    val targetUrl: String,
    val detection: VerificationDetectionResult
) : IllegalStateException("SECURITY_VERIFICATION_REQUIRED")

/**
 * Section 1: Universal multi-signal detector for website security challenges.
 */
object VerificationDetector {

    private val CHALLENGE_TITLES = listOf(
        "just a moment",
        "attention required",
        "إجراء التحقق من الأمان",
        "تحقق من أنك إنسان",
        "verify you are human",
        "checking your browser",
        "security check",
        "ddos-guard",
        "please wait...",
        "one more step",
        "صفحة الانتظار الأمنية"
    )

    private val CLOUDFLARE_DOM_SIGNALS = listOf(
        "cf-browser-verification",
        "cf-chl-widget",
        "_cf_chl_opt",
        "challenges.cloudflare.com/turnstile",
        "cdn-cgi/challenge-platform",
        "cf-turnstile",
        "cf-spinner-please-wait",
        "إجراء التحقق من الأمان"
    )

    private val HUMAN_CAPTCHA_SIGNALS = listOf(
        "تحقق من أنك إنسان",
        "verify you are human",
        "are you a human",
        "please verify you are a human",
        "hcaptcha.com/1/api.js",
        "g-recaptcha",
        "captcha-delivery.com",
        "complete the security check to access"
    )

    private val BROWSER_CHECK_SIGNALS = listOf(
        "checking your browser before accessing",
        "please wait while we check your browser",
        "browser verification in progress",
        "ddos-guard",
        "sucuri website firewall",
        "enable javascript and cookies to continue",
        "تحقق من المتصفح قبل الدخول"
    )

    private val WAITING_ROOM_SIGNALS = listOf(
        "security waiting room",
        "you are now in line",
        "صفحة الانتظار الأمنية",
        "يرجى الانتظار بينما نتحقق من اتصالك",
        "waiting room powered by"
    )

    fun detect(
        html: String,
        httpCode: Int = 200,
        headers: Map<String, String> = emptyMap(),
        pageTitle: String = ""
    ): VerificationDetectionResult {
        val trimmed = html.trim()
        val cfMitigatedHeader = headers.entries
            .firstOrNull { it.key.equals("cf-mitigated", ignoreCase = true) }
            ?.value.orEmpty()

        if (cfMitigatedHeader.contains("challenge", ignoreCase = true)) {
            return VerificationDetectionResult(
                isVerificationRequired = true,
                challengeType = VerificationChallengeType.CLOUDFLARE_CHALLENGE,
                httpCode = httpCode,
                reasonAr = "يتطلب هذا الموقع إكمال التحقق الأمني (Cloudflare Challenge Header).",
                matchedIndicators = listOf("cf-mitigated: challenge")
            )
        }

        if (trimmed.isEmpty()) {
            return VerificationDetectionResult(
                isVerificationRequired = false,
                challengeType = VerificationChallengeType.NONE,
                httpCode = httpCode,
                reasonAr = "صفحة فارغة وليست صفحة تحقق أمني"
            )
        }

        val lowerHtml = trimmed.lowercase()
        val doc = runCatching { Jsoup.parse(trimmed) }.getOrNull()
        val effectiveTitle = pageTitle.ifBlank { doc?.title().orEmpty() }.trim().lowercase()

        val hasRealMangaContent = doc != null && (
            doc.select("div.page-item-detail, div.bsx, div.c-tabs-item__content, div.manga-item, li.wp-manga-chapter, #chapterlist li, .reading-content img").size >= 2 ||
                doc.select("a[href*=/manga/], a[href*=/series/]").size >= 3
            )
        if (httpCode in 200..299 && hasRealMangaContent) {
            return VerificationDetectionResult(
                isVerificationRequired = false,
                challengeType = VerificationChallengeType.NONE,
                httpCode = httpCode,
                reasonAr = "الصفحة تحتوي على محتوى المانهوا الطبيعي"
            )
        }

        val matched = mutableListOf<String>()

        for (titleSignal in CHALLENGE_TITLES) {
            if (effectiveTitle.contains(titleSignal)) {
                matched.add("title:$titleSignal")
            }
        }

        val cfSignals = CLOUDFLARE_DOM_SIGNALS.filter { lowerHtml.contains(it) }
        matched.addAll(cfSignals)

        val humanSignals = HUMAN_CAPTCHA_SIGNALS.filter { lowerHtml.contains(it) }
        matched.addAll(humanSignals)

        val browserSignals = BROWSER_CHECK_SIGNALS.filter { lowerHtml.contains(it) }
        matched.addAll(browserSignals)

        val waitingSignals = WAITING_ROOM_SIGNALS.filter { lowerHtml.contains(it) }
        matched.addAll(waitingSignals)

        val isChallengeStatusCode = httpCode in listOf(403, 503, 429)
        if (isChallengeStatusCode) {
            if (lowerHtml.contains("ray id") ||
                lowerHtml.contains("cf-ray") ||
                lowerHtml.contains("turnstile") ||
                lowerHtml.contains("challenge") ||
                lowerHtml.contains("captcha") ||
                lowerHtml.contains("security check") ||
                lowerHtml.contains("حماية") ||
                lowerHtml.contains("تحقق")
            ) {
                matched.add("http_$httpCode:security_challenge")
            }
        }

        if (matched.isEmpty()) {
            return VerificationDetectionResult(
                isVerificationRequired = false,
                challengeType = VerificationChallengeType.NONE,
                httpCode = httpCode,
                reasonAr = "لا توجد مؤشرات تحقق أمني"
            )
        }

        val challengeType = when {
            cfSignals.isNotEmpty() || matched.any { it.contains("just a moment") || it.contains("إجراء التحقق من الأمان") } ->
                VerificationChallengeType.CLOUDFLARE_CHALLENGE
            humanSignals.isNotEmpty() ->
                VerificationChallengeType.HUMAN_VERIFICATION
            waitingSignals.isNotEmpty() ->
                VerificationChallengeType.SECURITY_WAITING_ROOM
            browserSignals.isNotEmpty() ->
                VerificationChallengeType.BROWSER_INTEGRITY_CHECK
            else ->
                VerificationChallengeType.CONTENT_BLOCKING_CHALLENGE
        }

        return VerificationDetectionResult(
            isVerificationRequired = true,
            challengeType = challengeType,
            httpCode = httpCode,
            reasonAr = "يتطلب هذا الموقع إكمال التحقق الأمني (${challengeType.descriptionAr})",
            matchedIndicators = matched.distinct()
        )
    }

    fun isNormalVerifiedPage(
        html: String,
        httpCode: Int = 200,
        pageTitle: String = ""
    ): Boolean {
        if (httpCode !in 200..299) return false
        val trimmed = html.trim()
        if (trimmed.length < 40) return false

        val detection = detect(html = trimmed, httpCode = httpCode, pageTitle = pageTitle)
        if (detection.isVerificationRequired) return false

        val doc = runCatching { Jsoup.parse(trimmed) }.getOrNull() ?: return false
        val body = doc.body() ?: return false
        val titleLower = pageTitle.ifBlank { doc.title() }.trim().lowercase()
        if (CHALLENGE_TITLES.any { titleLower.contains(it) }) return false

        val hasStructure = body.select("a, img, div, article, section, main, ul, li, h1, h2, h3").isNotEmpty() ||
            body.text().trim().length >= 16
        return hasStructure
    }
}

data class VerificationSession(
    val sourceId: String,
    val domain: String,
    val state: VerificationState = VerificationState.NOT_REQUIRED,
    val challengeType: VerificationChallengeType = VerificationChallengeType.NONE,
    val verifiedAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
    val lastVerifiedHtml: String? = null,
    val reasonAr: String = "",
    val requiresWebViewFallback: Boolean = false
) {
    fun isCurrentlyValid(nowMs: Long): Boolean {
        return state == VerificationState.COMPLETED && expiresAtMs > nowMs
    }
}

class VerificationSessionManager(
    val defaultSessionTtlMs: Long = 30 * 60 * 1000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val sessionsByDomain = ConcurrentHashMap<String, VerificationSession>()
    private val _sessionsFlow = MutableStateFlow<Map<String, VerificationSession>>(emptyMap())
    val sessionsFlow: StateFlow<Map<String, VerificationSession>> = _sessionsFlow.asStateFlow()

    fun normalizeKey(domainOrUrl: String): String {
        return runCatching { normalizeDomain(domainOrUrl) }
            .getOrDefault(domainOrUrl.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/'))
    }

    fun getSession(domainOrUrl: String): VerificationSession? {
        val key = normalizeKey(domainOrUrl)
        val existing = sessionsByDomain[key] ?: return null
        val now = clock()
        if (existing.state == VerificationState.COMPLETED && existing.expiresAtMs in 1..now) {
            val expired = existing.copy(
                state = VerificationState.EXPIRED,
                lastVerifiedHtml = null,
                reasonAr = "انتهت صلاحية جلسة التحقق الأمني. يرجى إعادة التحقق."
            )
            sessionsByDomain[key] = expired
            publishSnapshot()
            return expired
        }
        return existing
    }

    fun getState(domainOrUrl: String): VerificationState {
        return getSession(domainOrUrl)?.state ?: VerificationState.NOT_REQUIRED
    }

    fun isSessionValid(domainOrUrl: String): Boolean {
        val session = getSession(domainOrUrl) ?: return false
        return session.isCurrentlyValid(clock())
    }

    fun updateSession(
        sourceId: String,
        domain: String,
        state: VerificationState,
        challengeType: VerificationChallengeType = VerificationChallengeType.NONE,
        verifiedHtml: String? = null,
        reasonAr: String = state.labelAr,
        ttlMs: Long = defaultSessionTtlMs,
        requiresWebViewFallback: Boolean = false
    ): VerificationSession {
        val key = normalizeKey(domain)
        val now = clock()
        val existing = sessionsByDomain[key]
        val session = VerificationSession(
            sourceId = sourceId.ifBlank { existing?.sourceId.orEmpty() },
            domain = key,
            state = state,
            challengeType = if (challengeType != VerificationChallengeType.NONE) {
                challengeType
            } else {
                existing?.challengeType ?: VerificationChallengeType.NONE
            },
            verifiedAtMs = if (state == VerificationState.COMPLETED) now else (existing?.verifiedAtMs ?: 0L),
            expiresAtMs = if (state == VerificationState.COMPLETED) now + ttlMs else (existing?.expiresAtMs ?: 0L),
            lastVerifiedHtml = if (state == VerificationState.COMPLETED) verifiedHtml else null,
            reasonAr = reasonAr,
            requiresWebViewFallback = requiresWebViewFallback
        )
        sessionsByDomain[key] = session
        publishSnapshot()
        return session
    }

    fun expireSession(domainOrUrl: String): VerificationSession? {
        val key = normalizeKey(domainOrUrl)
        val existing = sessionsByDomain[key] ?: return null
        val expired = existing.copy(
            state = VerificationState.EXPIRED,
            lastVerifiedHtml = null,
            expiresAtMs = clock() - 1L,
            reasonAr = "انتهت صلاحية جلسة التحقق الأمني"
        )
        sessionsByDomain[key] = expired
        publishSnapshot()
        return expired
    }

    fun serializeMetadata(): String {
        return sessionsByDomain.values.joinToString("\n") { s ->
            "${s.sourceId}|${s.domain}|${s.state.name}|${s.verifiedAtMs}|${s.expiresAtMs}"
        }
    }

    fun restoreFromMetadata(serialized: String) {
        if (serialized.isBlank()) return
        val now = clock()
        serialized.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val parts = line.split('|')
                if (parts.size >= 5) {
                    val sourceId = parts[0]
                    val domain = normalizeKey(parts[1])
                    val savedState = runCatching { VerificationState.valueOf(parts[2]) }
                        .getOrDefault(VerificationState.NOT_REQUIRED)
                    val verifiedAt = parts[3].toLongOrNull() ?: 0L
                    val expiresAt = parts[4].toLongOrNull() ?: 0L
                    val effectiveState = if (savedState == VerificationState.COMPLETED && expiresAt <= now) {
                        VerificationState.EXPIRED
                    } else if (savedState == VerificationState.IN_PROGRESS) {
                        VerificationState.REQUIRED
                    } else {
                        savedState
                    }
                    sessionsByDomain[domain] = VerificationSession(
                        sourceId = sourceId,
                        domain = domain,
                        state = effectiveState,
                        verifiedAtMs = verifiedAt,
                        expiresAtMs = expiresAt,
                        reasonAr = effectiveState.labelAr
                    )
                }
            }
        publishSnapshot()
    }

    private fun publishSnapshot() {
        _sessionsFlow.value = sessionsByDomain.toMap()
    }
}

class VerificationRepository(
    val sessionManager: VerificationSessionManager = VerificationSessionManager(),
    private val preferences: AppPreferences? = null
) {
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val sessionsFlow: StateFlow<Map<String, VerificationSession>> = sessionManager.sessionsFlow

    init {
        if (preferences != null) {
            repoScope.launch {
                val saved = preferences.verificationSessionsMetadataFlow.firstOrNull().orEmpty()
                if (saved.isNotBlank()) {
                    sessionManager.restoreFromMetadata(saved)
                }
            }
        }
    }

    fun getSession(domainOrUrl: String): VerificationSession? = sessionManager.getSession(domainOrUrl)

    fun getState(domainOrUrl: String): VerificationState = sessionManager.getState(domainOrUrl)

    fun isVerified(domainOrUrl: String): Boolean = sessionManager.isSessionValid(domainOrUrl)

    fun saveSession(
        sourceId: String,
        domain: String,
        state: VerificationState,
        challengeType: VerificationChallengeType = VerificationChallengeType.NONE,
        verifiedHtml: String? = null,
        reasonAr: String = state.labelAr,
        ttlMs: Long = sessionManager.defaultSessionTtlMs,
        requiresWebViewFallback: Boolean = false
    ): VerificationSession {
        val updated = sessionManager.updateSession(
            sourceId = sourceId,
            domain = domain,
            state = state,
            challengeType = challengeType,
            verifiedHtml = verifiedHtml,
            reasonAr = reasonAr,
            ttlMs = ttlMs,
            requiresWebViewFallback = requiresWebViewFallback
        )
        persistMetadataAsync()
        return updated
    }

    fun expireSession(domainOrUrl: String): VerificationSession? {
        val expired = sessionManager.expireSession(domainOrUrl)
        persistMetadataAsync()
        return expired
    }

    fun exportMetadataSnapshot(): String = sessionManager.serializeMetadata()

    fun restoreMetadataSnapshot(serialized: String) {
        sessionManager.restoreFromMetadata(serialized)
        persistMetadataAsync()
    }

    private fun persistMetadataAsync() {
        val prefs = preferences ?: return
        val snapshot = sessionManager.serializeMetadata()
        repoScope.launch {
            runCatching { prefs.saveVerificationSessionsMetadata(snapshot) }
        }
    }
}

class VerificationManager(
    val detector: VerificationDetector = VerificationDetector,
    val sessionManager: VerificationSessionManager = VerificationSessionManager(),
    val repository: VerificationRepository = VerificationRepository(sessionManager),
    private val appContext: Context? = null
) {
    val sessionsFlow: StateFlow<Map<String, VerificationSession>> = repository.sessionsFlow

    init {
        if (appContext != null) {
            sharedAppContext = appContext.applicationContext ?: appContext
        }
    }

    companion object {
        const val DEFAULT_MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

        @Volatile
        private var sharedWebViewUserAgent: String? = null

        @Volatile
        private var sharedAppContext: Context? = null

        fun recordActiveUserAgent(userAgent: String?) {
            val clean = userAgent?.trim()?.replace("; wv", "").orEmpty()
            if (clean.isNotBlank()) {
                sharedWebViewUserAgent = clean
            }
        }

        /**
         * Returns the exact User-Agent used by Android WebView so Cloudflare `cf_clearance`
         * cookies bound to the WebView User-Agent remain valid during OkHttp requests.
         */
        fun getUserAgent(context: Context? = sharedAppContext): String {
            sharedWebViewUserAgent?.let { if (it.isNotBlank()) return it }
            if (context != null) {
                val ua = runCatching {
                    WebSettings.getDefaultUserAgent(context).replace("; wv", "").trim()
                }.getOrNull()
                if (!ua.isNullOrBlank()) {
                    sharedWebViewUserAgent = ua
                    return ua
                }
            }
            return DEFAULT_MOBILE_USER_AGENT
        }

        /**
         * Reads any session / Cloudflare clearance cookies stored in Android's CookieManager
         * for [url] or its normalized domain.
         */
        fun getCookieHeader(url: String): String? {
            return try {
                val cm = CookieManager.getInstance() ?: return null
                val direct = cm.getCookie(url)?.trim().orEmpty()
                if (direct.isNotBlank()) return direct
                val domain = normalizeDomain(url)
                if (domain.isNotBlank()) {
                    cm.getCookie("https://$domain")?.trim()?.takeIf { it.isNotBlank() }
                        ?: cm.getCookie("https://www.$domain")?.trim()?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * Evaluates the live DOM of [url] inside an offscreen Android WebView sharing the app's
         * [CookieManager] and TLS stack when a site blocks non-WebView TLS fingerprints or renders
         * its catalog/chapters client-side via JavaScript.
         * Safely returns null in local JVM unit tests where WebView is unavailable.
         */
        @SuppressLint("SetJavaScriptEnabled")
        suspend fun fetchHtmlViaWebView(
            url: String,
            timeoutMs: Long = 10_500L,
            context: Context? = sharedAppContext
        ): String? {
            val ctx = context ?: return null
            val isRobolectric = runCatching {
                android.os.Build.FINGERPRINT?.lowercase()?.contains("robolectric") == true
            }.getOrDefault(false)
            if (isRobolectric || Looper.getMainLooper() == null) return null

            return withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.Main) {
                    var webView: WebView? = null
                    try {
                        suspendCancellableCoroutine<String?> { cont ->
                            val wv = WebView(ctx)
                            webView = wv
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(wv, true)

                            wv.settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                loadsImagesAutomatically = false
                                userAgentString = (userAgentString ?: DEFAULT_MOBILE_USER_AGENT)
                                    .replace("; wv", "")
                                    .also { recordActiveUserAgent(it) }
                            }

                            var completed = false
                            wv.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                    super.onPageFinished(view, loadedUrl)
                                    if (completed || view == null) return
                                    CoroutineScope(Dispatchers.Main).launch {
                                        delay(950L)
                                        if (completed || !cont.isActive) return@launch
                                        view.evaluateJavascript(
                                            "(function(){return document.documentElement ? document.documentElement.outerHTML : '';})();"
                                        ) { rawJsResult ->
                                            if (completed || !cont.isActive) return@evaluateJavascript
                                            val decoded = decodeEvaluateJavascriptString(rawJsResult.orEmpty())
                                            if (decoded.length > 200) {
                                                completed = true
                                                runCatching { cm.flush() }
                                                cont.resume(decoded)
                                            } else {
                                                completed = true
                                                cont.resume(null)
                                            }
                                        }
                                    }
                                }
                            }

                            cont.invokeOnCancellation {
                                runCatching {
                                    wv.stopLoading()
                                    wv.destroy()
                                }
                            }

                            wv.loadUrl(url)
                        }
                    } catch (_: Throwable) {
                        null
                    } finally {
                        runCatching {
                            webView?.stopLoading()
                            webView?.destroy()
                        }
                    }
                }
            }
        }

        private fun decodeEvaluateJavascriptString(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed == "null") return ""
            val unwrapped = if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
                trimmed.substring(1, trimmed.length - 1)
            } else {
                trimmed
            }
            return unwrapped
                .replace("\\u003C", "<")
                .replace("\\u003c", "<")
                .replace("\\u003E", ">")
                .replace("\\u003e", ">")
                .replace("\\u0026", "&")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\")
        }
    }

    fun getUserAgent(): String = Companion.getUserAgent(appContext ?: sharedAppContext)

    fun getCookieHeader(url: String): String? = Companion.getCookieHeader(url)

    suspend fun fetchHtmlViaWebView(
        url: String,
        timeoutMs: Long = 10_500L
    ): String? = Companion.fetchHtmlViaWebView(url, timeoutMs, appContext ?: sharedAppContext)

    fun saveVerifiedHtmlSnapshot(domainOrUrl: String, html: String) {
        val domain = sessionManager.normalizeKey(domainOrUrl)
        if (domain.isBlank() || html.isBlank()) return
        val existing = repository.getSession(domain)
        repository.saveSession(
            sourceId = existing?.sourceId ?: domain,
            domain = domain,
            state = VerificationState.COMPLETED,
            verifiedHtml = html,
            reasonAr = VerificationState.COMPLETED.labelAr
        )
    }

    fun inspectHttpResponse(
        sourceId: String,
        domain: String,
        html: String,
        httpCode: Int,
        headers: Map<String, String> = emptyMap(),
        pageTitle: String = ""
    ): VerificationDetectionResult {
        val result = detector.detect(
            html = html,
            httpCode = httpCode,
            headers = headers,
            pageTitle = pageTitle
        )
        if (result.isVerificationRequired) {
            markVerificationRequired(
                sourceId = sourceId,
                domain = domain,
                challengeType = result.challengeType,
                reasonAr = result.reasonAr
            )
        } else {
            val currentState = getState(domain)
            if (currentState == VerificationState.NOT_REQUIRED) {
                repository.saveSession(
                    sourceId = sourceId,
                    domain = domain,
                    state = VerificationState.NOT_REQUIRED,
                    challengeType = VerificationChallengeType.NONE,
                    reasonAr = VerificationState.NOT_REQUIRED.labelAr
                )
            }
        }
        return result
    }

    fun getState(domainOrUrl: String): VerificationState = repository.getState(domainOrUrl)

    fun getSession(domainOrUrl: String): VerificationSession? = repository.getSession(domainOrUrl)

    fun isVerified(domainOrUrl: String): Boolean = repository.isVerified(domainOrUrl)

    fun getVerifiedHtmlSnapshot(domainOrUrl: String): String? {
        val session = repository.getSession(domainOrUrl) ?: return null
        return if (repository.isVerified(domainOrUrl)) session.lastVerifiedHtml else null
    }

    fun markVerificationRequired(
        sourceId: String,
        domain: String,
        challengeType: VerificationChallengeType = VerificationChallengeType.CLOUDFLARE_CHALLENGE,
        reasonAr: String = "يتطلب هذا الموقع إكمال التحقق الأمني"
    ): VerificationSession {
        PipelineLogger.log(
            stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
            sourceId = sourceId,
            sourceName = domain,
            status = "VERIFICATION_REQUIRED",
            failureReason = reasonAr
        )
        return repository.saveSession(
            sourceId = sourceId,
            domain = domain,
            state = VerificationState.REQUIRED,
            challengeType = challengeType,
            reasonAr = reasonAr
        )
    }

    fun startVerification(sourceId: String, domain: String): VerificationSession {
        PipelineLogger.log(
            stage = PipelineStage.NETWORK_REQUEST_STARTED,
            sourceId = sourceId,
            sourceName = domain,
            status = "VERIFICATION_IN_PROGRESS",
            details = "User opened VerificationWebView for $domain"
        )
        return repository.saveSession(
            sourceId = sourceId,
            domain = domain,
            state = VerificationState.IN_PROGRESS,
            reasonAr = VerificationState.IN_PROGRESS.labelAr
        )
    }

    fun completeVerification(
        sourceId: String,
        domain: String,
        verifiedHtml: String? = null,
        pageTitle: String = "",
        ttlMs: Long = sessionManager.defaultSessionTtlMs
    ): VerificationSession {
        if (!verifiedHtml.isNullOrBlank()) {
            val isNormal = detector.isNormalVerifiedPage(
                html = verifiedHtml,
                httpCode = 200,
                pageTitle = pageTitle
            )
            if (!isNormal) {
                return failVerification(
                    sourceId = sourceId,
                    domain = domain,
                    reasonAr = "لم تنتهِ صفحة التحقق الأمني بعد. يرجى إكمال التحقق داخل الصفحة."
                )
            }
        }

        PipelineLogger.log(
            stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
            sourceId = sourceId,
            sourceName = domain,
            status = "VERIFICATION_COMPLETED",
            details = "Human verification completed for $domain"
        )
        return repository.saveSession(
            sourceId = sourceId,
            domain = domain,
            state = VerificationState.COMPLETED,
            verifiedHtml = verifiedHtml,
            reasonAr = VerificationState.COMPLETED.labelAr,
            ttlMs = ttlMs
        )
    }

    fun cancelVerification(sourceId: String, domain: String): VerificationSession {
        PipelineLogger.log(
            stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
            sourceId = sourceId,
            sourceName = domain,
            status = "VERIFICATION_CANCELLED",
            failureReason = "Cancelled by user"
        )
        return repository.saveSession(
            sourceId = sourceId,
            domain = domain,
            state = VerificationState.CANCELLED,
            reasonAr = VerificationState.CANCELLED.labelAr
        )
    }

    fun failVerification(
        sourceId: String,
        domain: String,
        reasonAr: String = "فشل الاتصال أثناء التحقق الأمني"
    ): VerificationSession {
        PipelineLogger.log(
            stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
            sourceId = sourceId,
            sourceName = domain,
            status = "VERIFICATION_FAILED",
            failureReason = reasonAr
        )
        return repository.saveSession(
            sourceId = sourceId,
            domain = domain,
            state = VerificationState.FAILED,
            reasonAr = reasonAr
        )
    }

    fun expireSession(domain: String): VerificationSession? {
        return repository.expireSession(domain)
    }

    fun markRequiresWebViewFallback(sourceId: String, domain: String, required: Boolean) {
        val current = repository.getSession(domain)
        repository.saveSession(
            sourceId = sourceId,
            domain = domain,
            state = current?.state ?: VerificationState.COMPLETED,
            verifiedHtml = current?.lastVerifiedHtml,
            reasonAr = current?.reasonAr ?: VerificationState.COMPLETED.labelAr,
            requiresWebViewFallback = required
        )
    }
}
