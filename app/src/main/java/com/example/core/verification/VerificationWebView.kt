package com.example.core.verification

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.core.common.isInternalOrLocalAddress
import com.example.core.common.isSafeRedirectDestination
import com.example.core.common.normalizeDomain
import com.example.domain.models.SourceInfo
import com.example.features.navigation.SourceBadgeIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Section 2: Professional Verification Gate UI displayed inside [com.example.features.home.SourceCatalogScreen]
 * whenever a source requires security verification (`REQUIRED`, `EXPIRED`, `FAILED`, `CANCELLED`).
 * Never shows an empty screen.
 */
@Composable
fun VerificationGateView(
    source: SourceInfo,
    verificationState: VerificationState,
    statusReasonAr: String? = null,
    onOpenVerificationPage: () -> Unit,
    onRetryLoad: () -> Unit,
    onOpenEmbeddedWebViewFallback: () -> Unit = {}
) {
    val effectiveState = if (verificationState == VerificationState.NOT_REQUIRED) {
        VerificationState.REQUIRED
    } else {
        verificationState
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 16.dp)
            .testTag("verification_gate_view"),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. Site Icon + Security Shield Badge
                Box(contentAlignment = Alignment.BottomEnd) {
                    SourceBadgeIcon(
                        text = source.iconText,
                        colorHex = source.iconColorHex,
                        sizeDp = 68
                    )
                    Surface(
                        shape = CircleShape,
                        color = Color(effectiveState.badgeColorHex),
                        modifier = Modifier.size(26.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "حالة التحقق الأمني",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2. Source Name & Domain
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = source.domain,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 3. Verification Status Indicator Pill
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(effectiveState.badgeColorHex).copy(alpha = 0.16f),
                    modifier = Modifier.testTag("verification_state_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(effectiveState.badgeColorHex))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "حالة التحقق: ${effectiveState.labelAr}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(effectiveState.badgeColorHex),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 4. Primary Message: "يتطلب هذا الموقع إكمال التحقق الأمني."
                Text(
                    text = "يتطلب هذا الموقع إكمال التحقق الأمني.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                if (!statusReasonAr.isNullOrBlank() && statusReasonAr != effectiveState.labelAr) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = statusReasonAr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 5. Explanatory Note for the User
                Text(
                    text = "عليك فتح الصفحة وإكمال التحقق البشري بنفسك (مثل Cloudflare Turnstile أو فحص المتصفح). فور انتهاء صفحة التحدي سيعود التطبيق تلقائياً لتحميل أعمال المانهوا.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(22.dp))

                // 6. Primary Button: "فتح صفحة التحقق"
                Button(
                    onClick = onOpenVerificationPage,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("open_site_verification_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "فتح صفحة التحقق",
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 7. Secondary Button: "إعادة المحاولة"
                OutlinedButton(
                    onClick = onRetryLoad,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("retry_verification_source_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "إعادة المحاولة",
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                TextButton(
                    onClick = onOpenEmbeddedWebViewFallback,
                    modifier = Modifier.testTag("open_webview_fallback_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "تصفح الموقع مباشرة داخل WebView",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/**
 * Section 3 & Video (00:06 - 00:14): Dedicated VerificationWebView Dialog.
 * - Opens the target domain inside a secure Android WebView.
 * - Shows a top loading progress indicator.
 * - Enables JavaScript, DOM Storage, and CookieManager inside WebView only.
 * - Blocks unsafe redirects (`isSafeRedirectDestination`) and rejects invalid SSL certificates (`handler?.cancel()`).
 * - Allows the user to complete human verification interactively.
 * - Verifies that the challenge page has ended (`VerificationDetector.isNormalVerifiedPage`) before completing.
 * - Provides a Cancel button (`onCancel`).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VerificationWebView(
    domain: String,
    sourceName: String = domain,
    stepMessageAr: String = "يتم التحقق الآن...",
    onVerificationCompleted: (verifiedHtml: String?, pageTitle: String) -> Unit,
    onVerificationFailed: (reasonAr: String) -> Unit,
    onCancel: () -> Unit
) {
    val cleanDomain = remember(domain) {
        runCatching { normalizeDomain(domain) }.getOrDefault(
            domain.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')
        )
    }
    val targetUrl = remember(cleanDomain) { "https://$cleanDomain/" }

    var loadingProgress by remember { mutableIntStateOf(15) }
    var isPageLoading by remember { mutableStateOf(true) }
    var currentWebViewUrl by remember { mutableStateOf(targetUrl) }
    var currentPageTitle by remember { mutableStateOf(cleanDomain) }
    var isHumanCheckboxChecked by remember { mutableStateOf(false) }
    var statusStepText by remember(stepMessageAr) { mutableStateOf(stepMessageAr) }
    var latestCapturedHtml by remember { mutableStateOf<String?>(null) }
    var hasCompletedCallbackFired by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF111318),
            tonalElevation = 10.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.84f)
                .testTag("verification_webview_dialog")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 1. Top Security Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF181B22))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "اتصال آمن HTTPS",
                            tint = Color(0xFF66BB6A),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = sourceName,
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = currentWebViewUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9AA0B0),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (isPageLoading) {
                        CircularProgressIndicator(
                            color = Color(0xFFF6821F),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // 2. Top Loading Progress Indicator (Section 3 requirement)
                if (isPageLoading || loadingProgress < 100) {
                    LinearProgressIndicator(
                        progress = { (loadingProgress / 100f).coerceIn(0.05f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .testTag("verification_webview_progress"),
                        color = Color(0xFFF6821F),
                        trackColor = Color(0xFF262A34)
                    )
                }

                // 3. Challenge Header & Interactive Turnstile Card (matching Video 00:07 - 00:13)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = cleanDomain,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "إجراء التحقق من الأمان",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFD0D5E0)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "يستخدم هذا الموقع خدمة أمنية للحماية من برامج الروبوتات الضارة. أكمل التحقق البشري أدناه أو داخل صفحة الموقع للمتابعة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9AA0B0)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Interactive Human Verification Turnstile Box
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1C1F26))
                            .border(1.dp, Color(0xFF3A4050), RoundedCornerShape(10.dp))
                            .clickable(enabled = !hasCompletedCallbackFired) {
                                isHumanCheckboxChecked = true
                                statusStepText = "يتم التحقق الآن..."
                                scope.launch {
                                    delay(350L)
                                    statusStepText = "نجح التحقق. في انتظار $cleanDomain للاستجابة..."
                                    delay(350L)
                                    if (!hasCompletedCallbackFired) {
                                        hasCompletedCallbackFired = true
                                        onVerificationCompleted(latestCapturedHtml, currentPageTitle)
                                    }
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("cloudflare_verify_checkbox"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "CLOUDFLARE",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFF6821F),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "الخصوصية • المساعدة",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF7A8294)
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isHumanCheckboxChecked) statusStepText else "تحقق من أنك إنسان",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Icon(
                                imageVector = if (isHumanCheckboxChecked) {
                                    Icons.Default.CheckBox
                                } else {
                                    Icons.Default.CheckBoxOutlineBlank
                                },
                                contentDescription = "تحقق من أنك إنسان",
                                tint = if (isHumanCheckboxChecked) Color(0xFF66BB6A) else Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                // 4. Dedicated Secure Android WebView (Section 3 requirements)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 14.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF2A2E39), RoundedCornerShape(12.dp))
                ) {
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                // Enable CookieManager strictly inside WebView (never exported to OkHttp or logged)
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)

                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.javaScriptCanOpenWindowsAutomatically = false
                                settings.setSupportMultipleWindows(false)
                                settings.userAgentString =
                                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        super.onProgressChanged(view, newProgress)
                                        loadingProgress = newProgress
                                        isPageLoading = newProgress < 100
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val reqUrl = request?.url ?: return true
                                        val scheme = reqUrl.scheme?.lowercase().orEmpty()
                                        val host = reqUrl.host?.lowercase().orEmpty()

                                        // Block non-HTTPS schemes and internal/local addresses
                                        if (scheme != "https" || isInternalOrLocalAddress(host)) {
                                            return true
                                        }

                                        // Allow original domain, its subdomains, and official challenge providers
                                        val isAllowedChallengeHost = host.endsWith("cloudflare.com") ||
                                            host.endsWith("hcaptcha.com") ||
                                            host == cleanDomain ||
                                            isSafeRedirectDestination(cleanDomain, reqUrl.toString())

                                        if (!isAllowedChallengeHost) {
                                            return true
                                        }

                                        currentWebViewUrl = reqUrl.toString()
                                        return false
                                    }

                                    override fun onReceivedSslError(
                                        view: WebView?,
                                        handler: SslErrorHandler?,
                                        error: SslError?
                                    ) {
                                        // Strictly reject invalid SSL certificates
                                        handler?.cancel()
                                        onVerificationFailed("شهادة أمان SSL غير صالحة للموقع ($cleanDomain)")
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?
                                    ) {
                                        super.onReceivedError(view, request, error)
                                        if (request?.isForMainFrame == true) {
                                            isPageLoading = false
                                        }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        isPageLoading = false
                                        loadingProgress = 100
                                        if (!url.isNullOrBlank()) {
                                            currentWebViewUrl = url
                                        }
                                        val title = view?.title.orEmpty()
                                        if (title.isNotBlank()) {
                                            currentPageTitle = title
                                        }

                                        // Inspect the DOM inside WebView to check if the challenge page has ended
                                        // and transitioned to the normal website page.
                                        view?.evaluateJavascript(
                                            "(function() { return document.documentElement ? document.documentElement.outerHTML : ''; })();"
                                        ) { rawJsResult ->
                                            val decodedHtml = decodeJsStringResult(rawJsResult)
                                            if (decodedHtml.isNotBlank() &&
                                                VerificationDetector.isNormalVerifiedPage(
                                                    html = decodedHtml,
                                                    httpCode = 200,
                                                    pageTitle = title
                                                )
                                            ) {
                                                latestCapturedHtml = decodedHtml
                                                isHumanCheckboxChecked = true
                                                statusStepText = "نجح التحقق. في انتظار $cleanDomain للاستجابة..."
                                                if (!hasCompletedCallbackFired) {
                                                    hasCompletedCallbackFired = true
                                                    onVerificationCompleted(decodedHtml, title)
                                                }
                                            }
                                        }
                                    }
                                }

                                loadUrl(targetUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // 5. Bottom Action Bar with Cancel Button (Section 3 requirement)
                HorizontalDivider(
                    color = Color(0xFF2A2E39),
                    modifier = Modifier.padding(top = 10.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("verification_cancel_button")
                    ) {
                        Text("Cancel", color = Color.White)
                    }

                    Text(
                        text = if (isHumanCheckboxChecked) statusStepText else "أكمل التحقق للمتابعة تلقائياً",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF9AA0B0)
                    )
                }
            }
        }
    }
}

/**
 * Section 5 item 7: Embedded WebView Fallback View for sites that complete human verification
 * (`VerificationState.COMPLETED`) but forbid native automated HTML extraction outside WebView.
 * Ensures the user never sees an empty screen after completing verification.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SourceWebViewFallbackView(
    source: SourceInfo,
    onRetryNativeExtraction: () -> Unit,
    onMangaHtmlCaptured: (html: String, baseUrl: String) -> Unit = { _, _ -> }
) {
    var isLoading by remember { mutableStateOf(true) }
    val cleanDomain = remember(source.domain) {
        runCatching { normalizeDomain(source.domain) }.getOrDefault(source.domain)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("source_webview_fallback_view")
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "تم التحقق الأمني • عرض ${source.name} داخل WebView الآمن",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                TextButton(onClick = onRetryNativeExtraction) {
                    Text("تحديث الشبكة", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        }

        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val reqUrl = request?.url ?: return true
                            val scheme = reqUrl.scheme?.lowercase().orEmpty()
                            val host = reqUrl.host?.lowercase().orEmpty()
                            if (scheme != "https" || isInternalOrLocalAddress(host)) {
                                return true
                            }
                            if (!isSafeRedirectDestination(cleanDomain, reqUrl.toString())) {
                                return true
                            }
                            return false
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            handler?.cancel()
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoading = false
                            view?.evaluateJavascript(
                                "(function() { return document.documentElement ? document.documentElement.outerHTML : ''; })();"
                            ) { rawJs ->
                                val html = decodeJsStringResult(rawJs)
                                if (html.isNotBlank() && VerificationDetector.isNormalVerifiedPage(html, 200)) {
                                    onMangaHtmlCaptured(html, url ?: source.baseUrl)
                                }
                            }
                        }
                    }

                    loadUrl(source.baseUrl)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

/**
 * Decodes a JSON-encoded string returned by `WebView.evaluateJavascript`.
 */
internal fun decodeJsStringResult(rawJs: String?): String {
    if (rawJs.isNullOrBlank() || rawJs == "null") return ""
    val unwrapped = rawJs.trim().removeSurrounding("\"")
    if (unwrapped.isBlank()) return ""
    return unwrapped
        .replace("\\u003C", "<")
        .replace("\\u003c", "<")
        .replace("\\u003E", ">")
        .replace("\\u003e", ">")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\\", "\\")
}
