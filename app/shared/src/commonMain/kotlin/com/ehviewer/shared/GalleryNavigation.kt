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
