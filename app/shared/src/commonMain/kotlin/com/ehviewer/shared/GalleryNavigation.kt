package com.ehviewer.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_account
import ehviewer.app.shared.generated.resources.gallery_downloads
import ehviewer.app.shared.generated.resources.gallery_galleries
import ehviewer.app.shared.generated.resources.gallery_history
import ehviewer.app.shared.generated.resources.gallery_local_library
import ehviewer.app.shared.generated.resources.gallery_open_navigation
import ehviewer.app.shared.generated.resources.gallery_settings
import kotlinx.coroutines.launch

/** Top-level destinations, in the order of Android's original navigation drawer. */
internal enum class GalleryDestination { GALLERIES, HISTORY, DOWNLOADS, LOCAL_LIBRARY, ACCOUNT, SETTINGS }

/** Material's compact/medium boundary: narrower windows use the modal drawer, wider ones a rail. */
internal val GalleryRailMinWidth = 600.dp

private val LocalGalleryImmersive = staticCompositionLocalOf<MutableIntState?> { null }

/** Readers occupy the whole window, like Android's reader, while they are composed. */
@Composable
internal fun GalleryImmersive() {
    val immersive = LocalGalleryImmersive.current ?: return
    DisposableEffect(immersive) {
        immersive.intValue++
        onDispose { immersive.intValue-- }
    }
}

/**
 * The same adaptive navigation for phones, tablets and desktop windows. [content] stays in one
 * composition slot across width changes, so search results and screen state survive resizing.
 * It receives the drawer opener while the modal drawer is the navigation, or null beside the rail.
 */
@Composable
internal fun GalleryNavigationShell(destinations: List<GalleryDestination>, selected: GalleryDestination, onSelect: (GalleryDestination) -> Unit, content: @Composable (openNavigation: (() -> Unit)?) -> Unit) {
    val immersive = remember { mutableIntStateOf(0) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= GalleryRailMinWidth
        LaunchedEffect(wide) { if (wide) drawer.snapTo(DrawerValue.Closed) }
        // Registered when the drawer opens, so Back closes it before any screen underneath.
        if (drawer.isOpen) BackHandler { scope.launch { drawer.close() } }
        ModalNavigationDrawer(
            drawerContent = {
                ModalDrawerSheet(drawerState = drawer) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("EhViewer", Modifier.padding(horizontal = 28.dp, vertical = 16.dp), style = MaterialTheme.typography.titleLarge)
                        destinations.forEach { destination ->
                            NavigationDrawerItem(
                                label = { Text(destination.label()) },
                                selected = destination == selected,
                                onClick = {
                                    scope.launch { drawer.close() }
                                    onSelect(destination)
                                },
                                modifier = Modifier.padding(horizontal = 12.dp),
                                icon = { Icon(destination.icon, contentDescription = null) },
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            },
            drawerState = drawer,
            gesturesEnabled = !wide && drawer.isOpen,
        ) {
            Row(Modifier.fillMaxSize()) {
                if (wide && immersive.intValue == 0) {
                    NavigationRail(Modifier.fillMaxHeight()) {
                        Spacer(Modifier.height(8.dp))
                        destinations.forEach { destination ->
                            NavigationRailItem(
                                selected = destination == selected,
                                onClick = { onSelect(destination) },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(destination.label(), maxLines = 1) },
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    CompositionLocalProvider(LocalGalleryImmersive provides immersive) {
                        content(if (wide) null else ({ scope.launch { drawer.open() } }))
                    }
                }
            }
        }
    }
}

/** Opens the compact drawer; wider windows show the rail instead. */
@Composable
internal fun GalleryNavigationButton(openNavigation: () -> Unit) {
    IconButton(onClick = openNavigation) {
        Icon(GalleryIcons.Menu, contentDescription = galleryString(Res.string.gallery_open_navigation))
    }
}

@Composable
private fun GalleryDestination.label() = galleryString(
    when (this) {
        GalleryDestination.GALLERIES -> Res.string.gallery_galleries
        GalleryDestination.HISTORY -> Res.string.gallery_history
        GalleryDestination.DOWNLOADS -> Res.string.gallery_downloads
        GalleryDestination.LOCAL_LIBRARY -> Res.string.gallery_local_library
        GalleryDestination.ACCOUNT -> Res.string.gallery_account
        GalleryDestination.SETTINGS -> Res.string.gallery_settings
    },
)

private val GalleryDestination.icon
    get() = when (this) {
        GalleryDestination.GALLERIES -> GalleryIcons.Home
        GalleryDestination.HISTORY -> GalleryIcons.History
        GalleryDestination.DOWNLOADS -> GalleryIcons.Download
        GalleryDestination.LOCAL_LIBRARY -> GalleryIcons.LibraryBooks
        GalleryDestination.ACCOUNT -> GalleryIcons.AccountCircle
        GalleryDestination.SETTINGS -> GalleryIcons.Settings
    }

/** Apache-2.0 Material Symbols path data, kept local so the shared UI avoids the large icon artifact. */
internal object GalleryIcons {
    val Menu by lazy { icon("Menu", "M3,18h18v-2H3v2zM3,13h18v-2H3v2zM3,6v2h18V6H3z") }
    val Home by lazy { icon("Home", "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z") }
    val History by lazy { icon("History", "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z") }
    val Download by lazy { icon("Download", "M5,20h14v-2H5V20zM19,9h-4V3H9v6H5l7,7L19,9z") }
    val LibraryBooks by lazy { icon("LibraryBooks", "M4,6L2,6v14c0,1.1 0.9,2 2,2h14v-2L4,20L4,6zM20,2L8,2c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2L22,4c0,-1.1 -0.9,-2 -2,-2zM19,11L9,11L9,9h10v2zM15,15L9,15v-2h6v2zM19,7L9,7L9,5h10v2z") }
    val AccountCircle by lazy { icon("AccountCircle", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,5c1.66,0 3,1.34 3,3s-1.34,3 -3,3 -3,-1.34 -3,-3 1.34,-3 3,-3zM12,19.2c-2.5,0 -4.71,-1.28 -6,-3.22 0.03,-1.99 4,-3.08 6,-3.08 1.99,0 5.97,1.09 6,3.08 -1.29,1.94 -3.5,3.22 -6,3.22z") }
    val Settings by lazy { icon("Settings", "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z") }
    val Search by lazy { icon("Search", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z") }
    val FilterList by lazy { icon("FilterList", "M10,18h4v-2h-4v2zM3,6v2h18L21,6L3,6zM6,13h12v-2L6,11v2z") }
    val Sort by lazy { icon("Sort", "M3,18h6v-2L3,16v2zM3,6v2h18L21,6L3,6zM3,13h12v-2L3,11v2z") }
    val ArrowBack by lazy { icon("ArrowBack", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z", autoMirror = true) }

    private fun icon(name: String, path: String, autoMirror: Boolean = false) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()
}
