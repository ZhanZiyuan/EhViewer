package com.ehviewer.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.materialkolor.hct.Hct
import com.materialkolor.utils.ColorUtils
import kotlin.math.roundToInt

// Values from Android 1.15.10's GalleryInfo.kt, EhUtils.kt, Card.kt and RatingWidget.kt.

internal const val GALLERY_COVER_RATIO = 0.67f

/**
 * Android sizes list cards from list_tile_size (40) * 9 pixels, at least 120dp. Desktop density 1
 * would make that 360dp, so the tablet height (180dp at density 2) caps it.
 */
internal fun galleryListCardHeight(density: Density, thumbnailSize: Int = 40): Dp {
    val reference = with(density) { (40 * 9).toDp() }.coerceIn(120.dp, 180.dp)
    return (reference * (thumbnailSize / 40f)).coerceAtLeast(120.dp)
}

/** gallery_list_margin_h: 12dp, 20dp from values-sw600dp and 76dp from values-sw720dp-land. */
internal fun galleryListMarginH(width: Dp, height: Dp): Dp {
    val smallest = minOf(width, height)
    return when {
        smallest >= 720.dp && width > height -> 76.dp
        smallest >= 600.dp -> 20.dp
        else -> 12.dp
    }
}

/** gallery_list_margin_v and gallery_list_interval. */
internal val GalleryListMarginV = 4.dp
internal val GalleryListInterval = 12.dp

private val categories = listOf(
    0x1 to ("misc" to 0xfff06292),
    0x2 to ("doujinshi" to 0xfff44336),
    0x4 to ("manga" to 0xffff9800),
    0x8 to ("artistcg" to 0xfffbc02d),
    0x10 to ("gamecg" to 0xff4caf50),
    0x20 to ("imageset" to 0xff3f51b5),
    0x40 to ("cosplay" to 0xff9c27b0),
    0x80 to ("asianporn" to 0xff9575cd),
    0x100 to ("non-h" to 0xff2196f3),
    0x200 to ("western" to 0xff8bc34a),
    0x400 to ("private" to 0xff000000),
).toMap()

internal fun galleryCategoryName(category: Int) = categories[category]?.first ?: "unknown"

/** Category color harmonized with primaryContainer, as Android's default harmonize_category_color does. */
@Composable
@ReadOnlyComposable
internal fun galleryCategoryColor(category: Int): Color {
    val original = Color(categories[category]?.second ?: 0xff000000)
    if (!LocalGalleryAppearance.current.harmonizeCategoryColor) return original
    val source = Hct.fromInt(original.toArgb())
    val container = Hct.fromInt(MaterialTheme.colorScheme.primaryContainer.toArgb())
    return Color(Hct.from(source.hue, container.chroma, container.tone).toInt())
}

@Composable
@ReadOnlyComposable
internal fun galleryCategoryTextColor(color: Color): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark == ColorUtils.lstarFromArgb(color.toArgb()) > 70) MaterialTheme.colorScheme.surface else LocalContentColor.current
}

/** CrystalCard: an outlined card whose outline is transparent. */
@Composable
internal fun galleryCrystalBorder(): BorderStroke {
    val border = CardDefaults.outlinedCardBorder()
    return remember(border) { border.copy(brush = SolidColor(Color.Transparent)) }
}

private val ratingYellow = Color(0xfff9a825)

/** GalleryListCardRating: five 16dp stars, 1dp apart, in half steps. */
@Composable
internal fun GalleryRating(rating: Float, modifier: Modifier = Modifier, starSize: Dp = 16.dp, interval: Dp = 1.dp) {
    val halves = (rating * 2).roundToInt().coerceIn(0, 10)
    Row(modifier) {
        repeat(5) {
            val icon = when {
                it * 2 + 1 < halves -> GalleryIcons.Star
                it * 2 + 1 == halves -> GalleryIcons.StarHalf
                else -> GalleryIcons.StarOutline
            }
            Icon(icon, contentDescription = null, modifier = Modifier.size(starSize), tint = ratingYellow)
            Spacer(Modifier.width(interval))
        }
    }
}
