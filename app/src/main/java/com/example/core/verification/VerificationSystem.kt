package com.example.core.verification

import com.example.core.common.PipelineLogger
import com.example.core.common.PipelineStage
import com.example.core.common.normalizeDomain
import com.example.core.storage.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap

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
 * - Detects Cloudflare Verification, Human Verification (hCaptcha/reCAPTCHA/Turnstile),
 *   Browser Integrity Checks (DDoS-Guard/Sucuri/Browser Check), Security Waiting Rooms,
 *   and Content-Blocking Challenge pages.
 * - Does NOT rely on the word "Cloudflare" alone (e.g., ignores normal 200 OK manga pages that use
 *   Cloudflare CDN scripts in their footer).
 * - Does NOT classify empty HTML pages or normal "0 results" pages as verification challenges.
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
        // Rule 1: Never treat an empty or blank response body as a verification page unless headers explicitly say so
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

        // Check if the page already contains real manga catalog/chapter DOM elements.
        // If HTTP 200 and real manga cards/chapters exist, a mere "cloudflare" script in the footer is NOT a challenge!
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

        // 1. Check Challenge Titles
        for (titleSignal in CHALLENGE_TITLES) {
            if (effectiveTitle.contains(titleSignal)) {
                matched.add("title:$titleSignal")
            }
        }

        // 2. Check Cloudflare Turnstile / Challenge Widget signals
        val cfSignals = CLOUDFLARE_DOM_SIGNALS.filter { lowerHtml.contains(it) }
        matched.addAll(cfSignals)

        // 3. Check Human CAPTCHA signals
        val humanSignals = HUMAN_CAPTCHA_SIGNALS.filter { lowerHtml.contains(it) }
        matched.addAll(humanSignals)

        // 4. Check Browser Integrity signals
        val browserSignals = BROWSER_CHECK_SIGNALS.filter { lowerHtml.contains(it) }
        matched.addAll(browserSignals)

        // 5. Check Security Waiting Room signals
        val waitingSignals = WAITING_ROOM_SIGNALS.filter { lowerHtml.contains(it) }
        matched.addAll(waitingSignals)

        // 6. Check HTTP 403 / 503 / 429 combined with security challenge markers (not relying on the word "cloudflare" alone)
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

    /**
     * Section 4: Verifies that the security challenge page has ended and the loaded HTML
     * represents a normal website page before marking verification as [VerificationState.COMPLETED].
     */
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

        // Ensure the body has real structure (links, divs, images, or meaningful text)
        val hasStructure = body.select("a, img, div, article, section, main, ul, li, h1, h2, h3").isNotEmpty() ||
            body.text().trim().length >= 16
        return hasStructure
    }
}

/**
 * Represents the verification state and non-sensitive session metadata for a single source/domain.
 * Never stores or logs raw cookies or authentication tokens.
 */
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

/**
 * Section 4 & 6: Manages verification sessions, expiration checks, and verified WebView HTML snapshots.
 * Supports an injectable [clock] for deterministic unit testing of session expiration (`EXPIRED`).
 */
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

    /**
     * Serializes non-sensitive session metadata (`sourceId|domain|state|verifiedAtMs|expiresAtMs`)
     * for local DataStore persistence across app restarts. Never serializes cookies, tokens, or HTML.
     */
    fun serializeMetadata(): String {
        return sessionsByDomain.values.joinToString("\n") { s ->
            "${s.sourceId}|${s.domain}|${s.state.name}|${s.verifiedAtMs}|${s.expiresAtMs}"
        }
    }

    /**
     * Restores persisted session metadata when the app is re-opened (`إعادة فتح التطبيق`),
     * automatically transitioning any expired `COMPLETED` session to `EXPIRED`.
     */
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

/**
 * Section 6: Repository managing verification sessions and persisting non-sensitive session metadata.
 */
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

/**
 * Section 6: Central orchestrator for the Universal Website Verification System.
 * Connects [VerificationDetector], [VerificationSessionManager], and [VerificationRepository]
 * with [com.example.sourceapi.MangaSource], [com.example.data.repositories.MangaRepository],
 * and [com.example.features.MainViewModel].
 */
class VerificationManager(
    val detector: VerificationDetector = VerificationDetector,
    val sessionManager: VerificationSessionManager = VerificationSessionManager(),
    val repository: VerificationRepository = VerificationRepository(sessionManager)
) {
    val sessionsFlow: StateFlow<Map<String, VerificationSession>> = repository.sessionsFlow

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
            // If a previously completed session hits a challenge again, mark it REQUIRED
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

    /**
     * Completes verification ONLY after verifying that the challenge page has ended
     * and the loaded HTML is a normal page (when [verifiedHtml] is provided).
     */
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
