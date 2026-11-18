package org.squidify.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.squidify.android.data.ThemeMode

/**
 * Squirtify's expressive Material 3 foundation. Dynamic Material You colors
 * remain available on Android 12+, while the fallback palette keeps the app
 * recognisable and gives every surface a deliberate tonal hierarchy.
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
        shapes = SquidShapes,
        content = content,
    )
}

/** Larger, softer corners for the app's cards, sheets and player surfaces. */
private val SquidShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val M3DarkColors = darkColorScheme(
    primary = Color(0xFFFFB2C8),
    onPrimary = Color(0xFF65002A),
    primaryContainer = Color(0xFF8E1748),
    onPrimaryContainer = Color(0xFFFFD9E3),
    secondary = Color(0xFFE7B9CC),
    onSecondary = Color(0xFF482637),
    secondaryContainer = Color(0xFF603D4F),
    onSecondaryContainer = Color(0xFFFFD9E8),
    tertiary = Color(0xFFD5C1FF),
    onTertiary = Color(0xFF38245E),
    tertiaryContainer = Color(0xFF513B79),
    onTertiaryContainer = Color(0xFFEBDDFF),
    background = Color(0xFF151117),
    onBackground = Color(0xFFF2EAF0),
    surface = Color(0xFF151117),
    onSurface = Color(0xFFF2EAF0),
    surfaceVariant = Color(0xFF51434C),
    onSurfaceVariant = Color(0xFFD7C1CC),
    surfaceContainerLowest = Color(0xFF100D12),
    surfaceContainerLow = Color(0xFF1D1820),
    surfaceContainer = Color(0xFF211C25),
    surfaceContainerHigh = Color(0xFF2B2530),
    surfaceContainerHighest = Color(0xFF362F3A),
    outline = Color(0xFF9F8994),
    outlineVariant = Color(0xFF51434C),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val M3LightColors = lightColorScheme(
    primary = Color(0xFFAD2856),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E4),
    onPrimaryContainer = Color(0xFF3F0018),
    secondary = Color(0xFF755562),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9E8),
    onSecondaryContainer = Color(0xFF2C151F),
    tertiary = Color(0xFF66558A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEBDDFF),
    onTertiaryContainer = Color(0xFF211047),
    background = Color(0xFFFFF7F9),
    onBackground = Color(0xFF21171D),
    surface = Color(0xFFFFF7F9),
    onSurface = Color(0xFF21171D),
    surfaceVariant = Color(0xFFF2DEE6),
    onSurfaceVariant = Color(0xFF51414A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0F5),
    surfaceContainer = Color(0xFFFBEAF0),
    surfaceContainerHigh = Color(0xFFF5E2EA),
    surfaceContainerHighest = Color(0xFFEEDAE3),
    outline = Color(0xFF83717A),
    outlineVariant = Color(0xFFD7C2CC),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFF410002),
)
