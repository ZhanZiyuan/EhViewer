package com.ehviewer.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_back
import ehviewer.app.shared.generated.resources.gallery_go_to_page
import ehviewer.app.shared.generated.resources.gallery_reader_hide_controls
import ehviewer.app.shared.generated.resources.gallery_reader_seek
import ehviewer.app.shared.generated.resources.gallery_reader_show_controls
import kotlin.math.roundToInt

/** Reading drops both the library's width cap and its keyline padding. */
internal fun galleryPageModifier(searchBarPage: Boolean, immersive: Boolean, edgeToEdge: Boolean = false) = if (searchBarPage || immersive) {
    Modifier.fillMaxSize()
} else {
    val page = Modifier.widthIn(max = 1040.dp).fillMaxSize()
    if (edgeToEdge) page else page.padding(horizontal = 16.dp, vertical = 12.dp)
}

internal class GalleryReaderChromeState {
    var visible by mutableStateOf(false)
    fun toggle() {
        visible = !visible
    }
}

@Composable
internal fun rememberGalleryReaderChromeState() = remember { GalleryReaderChromeState() }

/** Original Android's full canvas and sliding app bars, shared by all real reader sources. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GalleryReaderChrome(
    title: String,
    index: Int,
    pages: Int,
    state: GalleryReaderChromeState,
    onBack: () -> Unit,
    onJump: () -> Unit,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
    seekEnabled: Boolean = true,
    onKey: (KeyEvent) -> Boolean = { false },
    rtl: Boolean = false,
    status: @Composable () -> Unit = {},
    navigation: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val container = BottomAppBarDefaults.containerColor.copy(alpha = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) 0.9f else 0.95f)
    val safe = WindowInsets.safeDrawing
    val showLabel = galleryString(Res.string.gallery_reader_show_controls)
    val hideLabel = galleryString(Res.string.gallery_reader_hide_controls)
    Box(modifier.onKeyEvent(onKey).focusable().fillMaxSize().background(Color.Black)) {
        content()
        if (!state.visible) {
            // PageIndicatorText uses outlined, bold numbers directly over the image. A clickable
            // wrapper keeps the shared reader's accessible reveal action without button padding.
            val indicatorStyle = TextStyle(color = Color(235, 235, 235), fontSize = MaterialTheme.typography.bodySmall.fontSize, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Box(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(safe.only(WindowInsetsSides.Bottom))) {
                Box(Modifier.clickable(role = Role.Button, onClick = state::toggle).semantics(mergeDescendants = true) { contentDescription = showLabel }, contentAlignment = Alignment.Center) {
                    Text("${index + 1} / $pages", style = indicatorStyle.copy(color = Color(45, 45, 45), drawStyle = Stroke(width = 4f)), modifier = Modifier.clearAndSetSemantics {})
                    Text("${index + 1} / $pages", style = indicatorStyle)
                }
            }
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal))) {
            AnimatedVisibility(state.visible, enter = slideInVertically(tween(200)) { -it }, exit = slideOutVertically(tween(200)) { -it }) {
                TopAppBar(
                    title = {
                        Text(
                            title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics {
                                customActions = listOf(
                                    CustomAccessibilityAction(hideLabel) {
                                        state.visible = false
                                        true
                                    },
                                )
                            },
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = container),
                    expandedHeight = maxOf(64.dp, (64 * LocalDensity.current.fontScale).dp),
                    navigationIcon = { IconButton(onClick = onBack) { Icon(GalleryIcons.ArrowBack, galleryString(Res.string.gallery_back)) } },
                )
            }
            Surface(color = container) { status() }
        }
        AnimatedVisibility(
            state.visible,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal)),
            enter = slideInVertically(tween(200)) { it },
            exit = slideOutVertically(tween(200)) { it },
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val outerKeyline = if (maxWidth >= 840.dp) 24.dp else 16.dp
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var seeking by remember { mutableStateOf(false) }
                    var slider by remember(index) { mutableFloatStateOf(index.toFloat()) }
                    val jumpLabel = galleryString(Res.string.gallery_go_to_page)
                    val seekLabel = galleryString(Res.string.gallery_reader_seek)
                    // Original ChapterNavigator: rounded seek surface, 16/24dp outer keyline,
                    // 16dp inner keyline and an 8dp gap above the separate bottom action bar.
                    Row(Modifier.fillMaxWidth().padding(horizontal = outerKeyline).clip(CircleShape).background(container).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        navigation()
                        TextButton(onClick = onJump, enabled = seekEnabled, modifier = Modifier.widthIn(max = 144.dp).semantics { contentDescription = jumpLabel }) { Text("${index + 1} / $pages", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                        if (pages > 1) {
                            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                                Slider(
                                    value = if (seeking) slider else index.toFloat(),
                                    onValueChange = {
                                        seeking = true
                                        slider = it
                                    },
                                    onValueChangeFinished = {
                                        seeking = false
                                        onSeek(slider.roundToInt().coerceIn(0, pages - 1))
                                    },
                                    valueRange = 0f..(pages - 1).toFloat(),
                                    // Keep the validated continuous drag path: no per-page fraction
                                    // allocation, while arrow keys below commit exact integer pages.
                                    enabled = seekEnabled,
                                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp).onPreviewKeyEvent { event ->
                                        val delta = when (event.key) {
                                            Key.DirectionLeft -> if (rtl) 1 else -1
                                            Key.DirectionRight -> if (rtl) -1 else 1
                                            Key.DirectionUp -> 1
                                            Key.DirectionDown -> -1
                                            else -> return@onPreviewKeyEvent false
                                        }
                                        if (!seekEnabled) return@onPreviewKeyEvent false
                                        if (event.type == KeyEventType.KeyDown) {
                                            val target = (index.toLong() + delta).coerceIn(0, pages.toLong() - 1).toInt()
                                            if (target != index) onSeek(target)
                                        }
                                        true
                                    }.semantics { contentDescription = seekLabel },
                                )
                            }
                        }
                    }
                    Surface(color = container) {
                        Row(Modifier.fillMaxWidth().windowInsetsPadding(safe.only(WindowInsetsSides.Bottom)).heightIn(min = BottomAppBarDefaults.FlexibleBottomAppBarHeight), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically, content = actions)
                    }
                }
            }
        }
    }
}

@Composable
internal fun GalleryReaderAction(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, modifier = Modifier.size(64.dp, 56.dp), enabled = enabled) { Icon(icon, label) }
}
