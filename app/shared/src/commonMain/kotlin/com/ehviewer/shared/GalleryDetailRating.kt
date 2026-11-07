package com.ehviewer.shared

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_detail_rating1
import ehviewer.app.shared.generated.resources.gallery_detail_rating10
import ehviewer.app.shared.generated.resources.gallery_detail_rating2
import ehviewer.app.shared.generated.resources.gallery_detail_rating3
import ehviewer.app.shared.generated.resources.gallery_detail_rating4
import ehviewer.app.shared.generated.resources.gallery_detail_rating5
import ehviewer.app.shared.generated.resources.gallery_detail_rating6
import ehviewer.app.shared.generated.resources.gallery_detail_rating7
import ehviewer.app.shared.generated.resources.gallery_detail_rating8
import ehviewer.app.shared.generated.resources.gallery_detail_rating9
import ehviewer.app.shared.generated.resources.gallery_detail_rating_none
import ehviewer.app.shared.generated.resources.gallery_detail_rating_text
import kotlin.math.roundToInt

/** Original detail stars and qualitative text; rating submission is a separate account action. */
@Composable
internal fun GalleryDetailRating(rating: Float, count: Int, modifier: Modifier = Modifier) {
    val description = when ((rating * 2).roundToInt()) {
        1 -> Res.string.gallery_detail_rating1
        2 -> Res.string.gallery_detail_rating2
        3 -> Res.string.gallery_detail_rating3
        4 -> Res.string.gallery_detail_rating4
        5 -> Res.string.gallery_detail_rating5
        6 -> Res.string.gallery_detail_rating6
        7 -> Res.string.gallery_detail_rating7
        8 -> Res.string.gallery_detail_rating8
        9 -> Res.string.gallery_detail_rating9
        10 -> Res.string.gallery_detail_rating10
        else -> Res.string.gallery_detail_rating_none
    }
    OutlinedCard(modifier.fillMaxWidth(), border = galleryCrystalBorder()) {
        Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints {
                // Preserve 48dp stars/12dp intervals at the reference phone width. Only shrink
                // below the original 300dp row so all five stars remain visible in narrow windows.
                val scale = (maxWidth / 300.dp).coerceAtMost(1f)
                GalleryRating(rating, starSize = 48.dp * scale, interval = 12.dp * scale)
            }
            Spacer(Modifier.height(16.dp))
            Text(galleryString(Res.string.gallery_detail_rating_text, galleryString(description), galleryRatingNumber(rating), count))
        }
    }
}

/** Stable two-decimal formatting shared by Native and JVM without changing system locale. */
internal fun galleryRatingNumber(rating: Float): String {
    val hundredths = (rating * 100).roundToInt()
    val absolute = kotlin.math.abs(hundredths)
    return (if (hundredths < 0) "-" else "") + (absolute / 100) + "." + (absolute % 100).toString().padStart(2, '0')
}
