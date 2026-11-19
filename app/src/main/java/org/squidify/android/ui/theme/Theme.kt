package org.squidify.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.squidify.android.data.ThemeMode

/** Squirtify's bold Material 3 Expressive design foundation. */
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
        dark -> SquidDarkColors
        else -> SquidLightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = SquidTypography,
        shapes = SquidShapes,
        content = content,
    )
}

private val SquidLightColors = lightColorScheme(
    primary = Color(0xFF5548C8), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6E0FF), onPrimaryContainer = Color(0xFF1B1460),
    secondary = Color(0xFF006B64), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB8F1E9), onSecondaryContainer = Color(0xFF00201D),
    tertiary = Color(0xFF9B432E), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD0), onTertiaryContainer = Color(0xFF3B0902),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAF8FF), onBackground = Color(0xFF1B1B23),
    surface = Color(0xFFFAF8FF), onSurface = Color(0xFF1B1B23),
    surfaceVariant = Color(0xFFE8E5F0), onSurfaceVariant = Color(0xFF484653),
    outline = Color(0xFF797583), outlineVariant = Color(0xFFCAC6D5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F1FA),
    surfaceContainer = Color(0xFFEFECF5),
    surfaceContainerHigh = Color(0xFFE9E6F0),
    surfaceContainerHighest = Color(0xFFE3E0EA),
)

private val SquidDarkColors = darkColorScheme(
    primary = Color(0xFFC8BFFF), onPrimary = Color(0xFF2D237C),
    primaryContainer = Color(0xFF453A9E), onPrimaryContainer = Color(0xFFE8E2FF),
    secondary = Color(0xFF80D8CF), onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005048), onSecondaryContainer = Color(0xFF9DF2E8),
    tertiary = Color(0xFFFFB59F), onTertiary = Color(0xFF5C1607),
    tertiaryContainer = Color(0xFF7D2E1C), onTertiaryContainer = Color(0xFFFFDAD0),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121319), onBackground = Color(0xFFE6E1EB),
    surface = Color(0xFF121319), onSurface = Color(0xFFE6E1EB),
    surfaceVariant = Color(0xFF464650), onSurfaceVariant = Color(0xFFC8C4D3),
    outline = Color(0xFF918D9D), outlineVariant = Color(0xFF484651),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1A1B22),
    surfaceContainer = Color(0xFF1F2027),
    surfaceContainerHigh = Color(0xFF292A32),
    surfaceContainerHighest = Color(0xFF34353E),
)

private val SquidTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(
            fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1.8).sp,
        ),
        displayMedium = base.displayMedium.copy(
            fontSize = 46.sp, lineHeight = 52.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1.4).sp,
        ),
        displaySmall = base.displaySmall.copy(
            fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1.2).sp,
        ),
        headlineLarge = base.headlineLarge.copy(
            fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.9).sp,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontSize = 29.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.65).sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp,
        ),
        titleMedium = base.titleMedium.copy(
            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
        ),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    )
}

private val SquidShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(40.dp),
)
