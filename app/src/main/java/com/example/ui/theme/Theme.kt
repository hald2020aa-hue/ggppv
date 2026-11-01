package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.example.domain.models.AppThemeStyle

@Composable
fun MangaReaderTheme(
    themeStyle: AppThemeStyle = AppThemeStyle.DEFAULT,
    pureDarkMode: Boolean = false,
    rtlLayout: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val bg = if (pureDarkMode) PureBlackBackground else DarkBackground
    val surface = if (pureDarkMode) Color(0xFF0A0C10) else DarkSurface
    val surfaceVariant = if (pureDarkMode) Color(0xFF141720) else DarkSurfaceVariant

    val (primary, container) = when (themeStyle) {
        AppThemeStyle.DEFAULT -> PeriwinklePrimary to PeriwinkleContainer
        AppThemeStyle.DYNAMIC -> PeriwinklePrimary to PeriwinkleContainer
        AppThemeStyle.GREEN -> GreenAccent to GreenContainer
        AppThemeStyle.PURPLE -> PurpleAccent to PurpleContainer
        AppThemeStyle.MIDNIGHT -> MidnightAccent to MidnightContainer
        AppThemeStyle.RED -> RedAccent to RedContainer
        AppThemeStyle.TAKO -> TakoAccent to TakoContainer
        AppThemeStyle.TEAL -> TealAccent to TealContainer
        AppThemeStyle.TIDAL_WAVE -> TidalWaveAccent to TidalWaveContainer
        AppThemeStyle.YIN_YANG -> YinYangAccent to YinYangContainer
        AppThemeStyle.YOTSUBA -> YotsubaAccent to YotsubaContainer
    }

    val baseScheme = if (themeStyle == AppThemeStyle.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context).copy(
            background = bg,
            surface = surface
        )
    } else {
        darkColorScheme(
            primary = primary,
            onPrimary = Color(0xFF0B1528),
            primaryContainer = container,
            onPrimaryContainer = TextPrimary,
            secondary = primary,
            onSecondary = Color(0xFF0B1528),
            secondaryContainer = container,
            onSecondaryContainer = TextPrimary,
            background = bg,
            onBackground = TextPrimary,
            surface = surface,
            onSurface = TextPrimary,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = TextSecondary,
            outline = Color(0xFF394052)
        )
    }

    val direction = if (rtlLayout) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        MaterialTheme(
            colorScheme = baseScheme,
            typography = Typography,
            content = content
        )
    }
}
