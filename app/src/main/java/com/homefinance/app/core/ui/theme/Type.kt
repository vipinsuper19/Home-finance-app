package com.homefinance.app.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

private val Baseline = Typography()

/**
 * Material 3 typography with slightly heavier headings so amounts and titles read clearly.
 *
 * The default (system) font family is kept on purpose: downloadable fonts would need a
 * network request at runtime, which an offline-first finance app must not make.
 */
val AppTypography = Typography(
    displaySmall = Baseline.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = Baseline.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = Baseline.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Baseline.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Baseline.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Baseline.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Baseline.labelLarge.copy(fontWeight = FontWeight.Medium),
)
