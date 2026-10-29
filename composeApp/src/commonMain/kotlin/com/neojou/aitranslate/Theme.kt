package com.neojou.aitranslate

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import org.jetbrains.compose.resources.Font

/**
 * Quiet paper palette. One accent, used for the translate control and the selected language.
 * The scheme is light even when the system is dark.
 */
internal object TranslatePalette {
    val paper = Color(0xFFF6F3EE)
    val page = Color(0xFFFCFBF8)
    val ink = Color(0xFF2A2724)
    val muted = Color(0xFF8D847B)
    val line = Color(0xFFE4DDD4)
    val accent = Color(0xFF3F5E80)
    val clay = Color(0xFF8E4B3A)
}

/**
 * Bundled CJK-capable family — Noto Sans TC Regular (SIL OFL 1.1).
 *
 * Compose Wasm/Desktop draw with Skia, which does **not** pick up browser or
 * CSS fonts. Without a bundled CJK face, Chinese glyphs render as blank.
 */
@Composable
fun appFontFamily(): FontFamily = FontFamily(Font(Res.font.notosanstc_regular))

private fun Typography.withFontFamily(fontFamily: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = fontFamily),
    displayMedium = displayMedium.copy(fontFamily = fontFamily),
    displaySmall = displaySmall.copy(fontFamily = fontFamily),
    headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
    headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
    headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
    titleLarge = titleLarge.copy(fontFamily = fontFamily),
    titleMedium = titleMedium.copy(fontFamily = fontFamily),
    titleSmall = titleSmall.copy(fontFamily = fontFamily),
    bodyLarge = bodyLarge.copy(fontFamily = fontFamily),
    bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
    bodySmall = bodySmall.copy(fontFamily = fontFamily),
    labelLarge = labelLarge.copy(fontFamily = fontFamily),
    labelMedium = labelMedium.copy(fontFamily = fontFamily),
    labelSmall = labelSmall.copy(fontFamily = fontFamily),
)

/**
 * App-wide Material 3 theme with a CJK-capable default typeface.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val fontFamily = appFontFamily()
    val ink = TranslatePalette.ink
    val page = TranslatePalette.page
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = ink,
            onPrimary = page,
            primaryContainer = Color(0xFFE7E2DA),
            onPrimaryContainer = ink,
            secondary = TranslatePalette.muted,
            onSecondary = page,
            secondaryContainer = Color(0xFFE7E2DA),
            onSecondaryContainer = ink,
            tertiary = TranslatePalette.accent,
            onTertiary = page,
            tertiaryContainer = Color(0xFFE6EEF3),
            onTertiaryContainer = ink,
            background = TranslatePalette.paper,
            onBackground = ink,
            surface = page,
            onSurface = ink,
            onSurfaceVariant = TranslatePalette.muted,
            surfaceVariant = TranslatePalette.paper,
            surfaceContainer = TranslatePalette.paper,
            surfaceContainerLow = TranslatePalette.paper,
            surfaceContainerHigh = page,
            surfaceContainerHighest = page,
            surfaceContainerLowest = page,
            outline = TranslatePalette.line,
            outlineVariant = TranslatePalette.line,
            error = TranslatePalette.clay,
            onError = page,
            surfaceTint = Color.Transparent,
        ),
        typography = Typography().withFontFamily(fontFamily),
        content = content,
    )
}
