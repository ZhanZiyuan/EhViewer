package com.ehviewer.shared

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_reader_crop_borders

@Composable
internal fun GalleryReaderCropButton(crop: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick,
        modifier = Modifier.size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Wide)).semantics { selected = crop },
        enabled = enabled,
        shapes = IconButtonDefaults.shapes(IconButtonDefaults.mediumRoundShape, IconButtonDefaults.mediumPressedShape),
    ) {
        Icon(if (crop) GalleryReaderCropIcons.Crop else GalleryReaderCropIcons.CropOff, galleryString(Res.string.gallery_reader_crop_borders), Modifier.size(IconButtonDefaults.mediumIconSize))
    }
}

/** The existing EhViewer core/ui/icons/filled paths; repository license applies. */
@Suppress("BooleanLiteralArgument")
private object GalleryReaderCropIcons {
    val Crop by lazy {
        ImageVector.Builder("Crop", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17.0F, 15.0F)
                horizontalLineToRelative(2.0F)
                verticalLineTo(7.0F)
                curveToRelative(0.0F, -1.1F, -0.9F, -2.0F, -2.0F, -2.0F)
                horizontalLineTo(9.0F)
                verticalLineToRelative(2.0F)
                horizontalLineToRelative(8.0F)
                verticalLineToRelative(8.0F)
                close()
                moveTo(7.0F, 17.0F)
                verticalLineTo(1.0F)
                horizontalLineTo(5.0F)
                verticalLineToRelative(4.0F)
                horizontalLineTo(1.0F)
                verticalLineToRelative(2.0F)
                horizontalLineToRelative(4.0F)
                verticalLineToRelative(10.0F)
                curveToRelative(0.0F, 1.1F, 0.9F, 2.0F, 2.0F, 2.0F)
                horizontalLineToRelative(10.0F)
                verticalLineToRelative(4.0F)
                horizontalLineToRelative(2.0F)
                verticalLineToRelative(-4.0F)
                horizontalLineToRelative(4.0F)
                verticalLineToRelative(-2.0F)
                horizontalLineTo(7.0F)
                close()
            }
        }.build()
    }
    val CropOff by lazy {
        ImageVector.Builder("CropOff", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(7.0F, 1.0F)
                lineToRelative(-2.0F, 0.0F)
                lineToRelative(0.0F, 1.18F)
                lineToRelative(2.0F, 2.0F)
                lineToRelative(0.0F, -3.18F)
                close()
                moveTo(17.0F, 7.0F)
                verticalLineToRelative(7.18F)
                lineToRelative(0.82F, 0.82F)
                horizontalLineTo(19.0F)
                verticalLineTo(7.0F)
                arcToRelative(2.0059F, 2.0059F, 0.0F, false, false, -2.0F, -2.0F)
                horizontalLineTo(9.0F)
                verticalLineTo(6.18F)
                lineTo(9.82F, 7.0F)
                close()
                moveTo(19.0001F, 19.0F)
                lineToRelative(-2.0F, -2.0F)
                horizontalLineToRelative(0.0F)
                lineTo(2.1F, 2.1F)
                lineTo(0.68F, 3.51F)
                lineTo(2.1707F, 5.0F)
                horizontalLineTo(1.0F)
                verticalLineTo(7.0F)
                horizontalLineTo(4.1718F)
                lineTo(5.0F, 7.8278F)
                verticalLineTo(17.0F)
                arcToRelative(2.0059F, 2.0059F, 0.0F, false, false, 2.0F, 2.0F)
                horizontalLineToRelative(9.1778F)
                lineTo(17.0F, 19.8218F)
                verticalLineTo(23.0F)
                horizontalLineToRelative(2.0F)
                verticalLineTo(21.8207F)
                lineTo(20.49F, 23.31F)
                lineTo(21.9F, 21.9F)
                lineToRelative(-2.9F, -2.9F)
                close()
                moveTo(7.0F, 17.0F)
                verticalLineTo(9.8268F)
                lineTo(14.1768F, 17.0F)
                close()
                moveTo(19.82F, 17.0F)
                lineToRelative(2.0F, 2.0F)
                lineToRelative(1.18F, 0.0F)
                lineToRelative(0.0F, -2.0F)
                lineToRelative(-3.18F, 0.0F)
                close()
            }
        }.build()
    }
}
