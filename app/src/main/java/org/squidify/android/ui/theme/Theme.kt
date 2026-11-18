package org.squidify.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import org.squidify.android.data.ThemeMode

/**
 * Pure Material 3 theming: baseline schemes, optionally tinted by the
 * user's wallpaper (Material You dynamic color, Android 12+).
 */
@Composable
fun SquidTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> M3DarkColors
        else -> M3LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

private val M3DarkColors = darkColorScheme(
    primary = Color(0xFFFFB2BE),
    onPrimary = Color(0xFF65001A),
    primaryContainer = Color(0xFF8F1834),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFFE5BDC4),
    onSecondary = Color(0xFF442930),
    secondaryContainer = Color(0xFF5D3F46),
    onSecondaryContainer = Color(0xFFFFD9DF),
    tertiary = Color(0xFFE6C38B),
    onTertiary = Color(0xFF432C05),
    background = Color(0xFF181114),
    surface = Color(0xFF181114),
    surfaceVariant = Color(0xFF514146),
    onSurfaceVariant = Color(0xFFD6C2C7),
    outline = Color(0xFF9B8C91),
)

private val M3LightColors = lightColorScheme(
    primary = Color(0xFFB4233B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9DF),
    onPrimaryContainer = Color(0xFF40000E),
    secondary = Color(0xFF77565D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9DF),
    onSecondaryContainer = Color(0xFF2C151B),
    tertiary = Color(0xFF775A2C),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFF8F7),
    surface = Color(0xFFFFF8F7),
    surfaceVariant = Color(0xFFF4DDE0),
    onSurfaceVariant = Color(0xFF514347),
    outline = Color(0xFF847378),
)
