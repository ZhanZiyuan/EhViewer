package com.ehviewer.shared

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.roboto_classic
import org.jetbrains.compose.resources.Font

/** The byte-identical Roboto used by the Android baseline, with real weight/italic axes. */
@Composable
internal fun galleryTypography(): Typography {
    val fonts = (100..900 step 100).flatMap { weight ->
        listOf(FontStyle.Normal, FontStyle.Italic).map { style ->
            Font(
                Res.font.roboto_classic,
                weight = FontWeight(weight),
                style = style,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(weight),
                    FontVariation.width(100f),
                    FontVariation.italic(if (style == FontStyle.Italic) 1f else 0f),
                ),
            )
        }
    }
    return remember(fonts) {
        val family = FontFamily(fonts)
        val original = Typography()
        original.copy(
            displayLarge = original.displayLarge.copy(fontFamily = family),
            displayMedium = original.displayMedium.copy(fontFamily = family),
            displaySmall = original.displaySmall.copy(fontFamily = family),
            headlineLarge = original.headlineLarge.copy(fontFamily = family),
            headlineMedium = original.headlineMedium.copy(fontFamily = family),
            headlineSmall = original.headlineSmall.copy(fontFamily = family),
            titleLarge = original.titleLarge.copy(fontFamily = family),
            titleMedium = original.titleMedium.copy(fontFamily = family),
            titleSmall = original.titleSmall.copy(fontFamily = family),
            bodyLarge = original.bodyLarge.copy(fontFamily = family),
            bodyMedium = original.bodyMedium.copy(fontFamily = family),
            bodySmall = original.bodySmall.copy(fontFamily = family),
            labelLarge = original.labelLarge.copy(fontFamily = family),
            labelMedium = original.labelMedium.copy(fontFamily = family),
            labelSmall = original.labelSmall.copy(fontFamily = family),
            displayLargeEmphasized = original.displayLargeEmphasized.copy(fontFamily = family),
            displayMediumEmphasized = original.displayMediumEmphasized.copy(fontFamily = family),
            displaySmallEmphasized = original.displaySmallEmphasized.copy(fontFamily = family),
            headlineLargeEmphasized = original.headlineLargeEmphasized.copy(fontFamily = family),
            headlineMediumEmphasized = original.headlineMediumEmphasized.copy(fontFamily = family),
            headlineSmallEmphasized = original.headlineSmallEmphasized.copy(fontFamily = family),
            titleLargeEmphasized = original.titleLargeEmphasized.copy(fontFamily = family),
            titleMediumEmphasized = original.titleMediumEmphasized.copy(fontFamily = family),
            titleSmallEmphasized = original.titleSmallEmphasized.copy(fontFamily = family),
            bodyLargeEmphasized = original.bodyLargeEmphasized.copy(fontFamily = family),
            bodyMediumEmphasized = original.bodyMediumEmphasized.copy(fontFamily = family),
            bodySmallEmphasized = original.bodySmallEmphasized.copy(fontFamily = family),
            labelLargeEmphasized = original.labelLargeEmphasized.copy(fontFamily = family),
            labelMediumEmphasized = original.labelMediumEmphasized.copy(fontFamily = family),
            labelSmallEmphasized = original.labelSmallEmphasized.copy(fontFamily = family),
        )
    }
}
