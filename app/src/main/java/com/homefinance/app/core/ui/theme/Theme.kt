package com.homefinance.app.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The Home Finance design system.
 *
 * Only a light scheme is defined: the approved design direction is a clean white background
 * with an emerald-green primary colour. Dark-mode support is intentionally deferred to a
 * later task so the shipped palette is exactly the one that was signed off.
 *
 * Dynamic colour is deliberately not used either, so the brand green is identical on every
 * device instead of being replaced by the wallpaper-derived palette.
 */
private val HomeFinanceLightColorScheme = lightColorScheme(
    primary = EmeraldPrimary,
    onPrimary = EmeraldOnPrimary,
    primaryContainer = EmeraldPrimaryContainer,
    onPrimaryContainer = EmeraldOnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    error = ErrorRed,
    onError = OnErrorRed,
    errorContainer = ErrorRedContainer,
    onErrorContainer = OnErrorRedContainer,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    scrim = ScrimLight,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,
    inversePrimary = InversePrimaryLight,
    surfaceDim = SurfaceDimLight,
    surfaceBright = SurfaceBrightLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
)

@Composable
fun HomeFinanceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HomeFinanceLightColorScheme,
        typography = AppTypography,
        content = content,
    )
}
