package com.example.core.images

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.File
import java.net.URI

/**
 * Builds an HTTP-header-aware Coil ImageRequest that:
 * - Resolves local file paths or remote HTTPS URLs
 * - Attaches standard mobile browser User-Agent and origin Referer header
 *   so hotlink-protected manga CDNs do not reject cover/page requests with HTTP 403
 * - Enables disk and memory caching
 * - Supports bounded retry via retryAttempt key
 */
object MangaImageRequestBuilder {


    fun create(
        context: Context,
        imageUrlOrPath: String?,
        refererUrl: String? = null,
        retryAttempt: Int = 0
    ): ImageRequest {
        val clean = imageUrlOrPath?.trim().orEmpty()
        val dataModel: Any? = when {
            clean.isEmpty() -> null
            clean.startsWith("/") -> File(clean)
            clean.startsWith("file://") -> File(clean.removePrefix("file://"))
            else -> clean
        }

        val computedReferer = refererUrl?.takeIf { it.startsWith("https://") }
            ?: extractOriginReferer(clean)

        return ImageRequest.Builder(context)
            .data(dataModel)
            .crossfade(true)
            .diskCachePolicy(if (retryAttempt > 0) CachePolicy.DISABLED else CachePolicy.ENABLED)
            .memoryCachePolicy(if (retryAttempt > 0) CachePolicy.DISABLED else CachePolicy.ENABLED)
            .networkCachePolicy(if (retryAttempt > 0) CachePolicy.DISABLED else CachePolicy.ENABLED)
            .apply {
                if (clean.startsWith("https://") || clean.startsWith("http://")) {
                    addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    if (!computedReferer.isNullOrBlank()) {
                        addHeader("Referer", computedReferer)
                    }
                }
            }
            .build()
    }

    fun build(
        context: Context,
        data: Any?,
        refererUrl: String? = null,
        crossfade: Boolean = false
    ): ImageRequest {
        val urlStr = (data as? String)?.trim().orEmpty()
        val computedReferer = refererUrl?.takeIf { it.startsWith("https://") }
            ?: extractOriginReferer(urlStr)
        return ImageRequest.Builder(context)
            .data(data)
            .crossfade(crossfade)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .apply {
                if (urlStr.startsWith("https://") || urlStr.startsWith("http://")) {
                    addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    if (!computedReferer.isNullOrBlank()) {
                        addHeader("Referer", computedReferer)
                    }
                }
            }
            .build()
    }

    private fun extractOriginReferer(url: String): String? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        return try {
            val uri = URI(url)
            val host = uri.host ?: return null
            "https://$host/"
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Unified Cover Image Component with automatic bounded retry (up to 2 retries on transient failure)
 * and a rich, styled poster fallback so cards never show raw broken-image placeholders.
 */
@Composable
fun MangaCoverImage(
    coverUrl: String? = null,
    imageUrl: String? = null,
    title: String = "",
    modifier: Modifier = Modifier,
    refererUrl: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    allowManualRetry: Boolean = false,
    isSensitive: Boolean = false
) {
    val resolvedUrl = coverUrl ?: imageUrl
    val context = LocalContext.current
    val contentSettings by remember(context) { com.example.core.storage.AppPreferences(context).settingsFlow }
        .collectAsState(initial = com.example.core.storage.UserSettings())
    if (isSensitive && contentSettings.hideSensitiveCovers) {
        Box(modifier = modifier.background(Color.DarkGray), contentAlignment = Alignment.Center) {
            Text("غلاف 18+ مخفي", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        return
    }
    var retryCount by remember(resolvedUrl) { mutableIntStateOf(0) }

    val palette = remember(title) {
        val hash = kotlin.math.abs(title.hashCode())
        val gradients = listOf(
            listOf(Color(0xFF1E1B4B), Color(0xFF312E81), Color(0xFF1E293B)),
            listOf(Color(0xFF3B0764), Color(0xFF4C1D95), Color(0xFF1E1B4B)),
            listOf(Color(0xFF14532D), Color(0xFF065F46), Color(0xFF0F172A)),
            listOf(Color(0xFF450A0A), Color(0xFF7F1D1D), Color(0xFF18181B)),
            listOf(Color(0xFF0C4A6E), Color(0xFF1E3A8A), Color(0xFF0F172A))
        )
        gradients[hash % gradients.size]
    }

    if (resolvedUrl.isNullOrBlank()) {
        StylizedCoverFallback(
            title = title,
            gradientColors = palette,
            showRetryBadge = false,
            onRetry = {},
            modifier = modifier
        )
        return
    }

    val request = remember(resolvedUrl, refererUrl, retryCount) {
        MangaImageRequestBuilder.create(
            context = context,
            imageUrlOrPath = resolvedUrl,
            refererUrl = refererUrl,
            retryAttempt = retryCount
        )
    }

    SubcomposeAsyncImage(
        model = request,
        contentDescription = title,
        contentScale = contentScale,
        modifier = modifier,
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(palette)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        error = {
            // Auto-retry once on transient network/CDN hiccup
            if (retryCount < 1) {
                retryCount += 1
            }
            StylizedCoverFallback(
                title = title,
                gradientColors = palette,
                showRetryBadge = allowManualRetry && retryCount < 3,
                onRetry = { if (retryCount < 3) retryCount += 1 },
                modifier = Modifier.fillMaxSize()
            )
        }
    )
}

@Composable
private fun StylizedCoverFallback(
    title: String,
    gradientColors: List<Color>,
    showRetryBadge: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Brush.verticalGradient(gradientColors))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.MenuBook,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "الغلاف غير متاح",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (showRetryBadge) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.45f),
                    modifier = Modifier.clickable(onClick = onRetry)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "إعادة تحميل الغلاف",
                        tint = Color.White,
                        modifier = Modifier
                            .padding(6.dp)
                            .size(16.dp)
                    )
                }
            }
        }
    }
}
