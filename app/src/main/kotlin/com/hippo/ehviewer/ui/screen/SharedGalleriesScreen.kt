package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.ehviewer.core.data.DataStoreNetworkPreferences
import com.ehviewer.shared.GalleryFavoriteControls
import com.ehviewer.shared.GalleryLibraryScreen
import com.ehviewer.shared.GalleryQuickSearchControls
import com.ehviewer.shared.GallerySettingsPage
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.AboutScreenDestination
import com.hippo.ehviewer.ui.destinations.DownloadScreenDestination
import com.hippo.ehviewer.ui.destinations.EhScreenDestination
import com.hippo.ehviewer.ui.destinations.PrivacyScreenDestination
import com.hippo.ehviewer.ui.main.AvatarIcon
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.ui.openBrowserOrThrow
import com.hippo.ehviewer.ui.theme.EhTheme
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

/** The production shared search → details → reader, using Android's original account and data. */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.SharedGalleriesScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val site by Settings.gallerySite.collectAsState()
    val signedIn by Settings.hasSignedIn.collectAsState()
    val appearance = remember { AndroidGalleryAppearanceStore() }
    val networkPreferences = remember { DataStoreNetworkPreferences() }
    val language = remember { androidGalleryLanguageSettings() }
    val listPreferences = remember { AndroidGalleryListPreferences() }
    val favoriteControls = remember { GalleryFavoriteControls(AndroidGalleryFavoritePreferences(), EhDB.sharedLocalFavorites) }
    val quickSearch = remember(listPreferences) { GalleryQuickSearchControls(EhDB.sharedQuickSearch, listPreferences) }
    val scope = rememberCoroutineScope()
    val downloads = remember { AndroidGalleryDownloads(scope) }
    key(site, signedIn) {
        val library = remember { AndroidGalleryLibrary(site == EhUrl.SITE_EX) }
        CompositionLocalProvider(LocalUriHandler provides rememberGalleryBrowserHandler()) {
            GalleryLibraryScreen(
                library, library.readerState, appearance, EhDB.sharedHistory, downloads, language,
                networkPreferences = networkPreferences,
                listPreferences = listPreferences,
                presentationPreferences = listPreferences,
                launchPreferences = listPreferences,
                favoriteControls = favoriteControls,
                quickSearch = quickSearch,
                platformAvatar = { AvatarIcon() },
                onPlatformDownloadOpen = { gid -> with(navigator) { navToReader(checkNotNull(DownloadManager.getDownloadInfo(gid)).galleryInfo) } },
                onBack = { navigator.popBackStack() },
                onAccount = { navigator.navigate(EhScreenDestination) },
                platformTheme = { dark, content -> EhTheme(dark, content) },
                browseOnEntry = true,
                onPlatformSettings = { page ->
                    val destination = when (page) {
                        GallerySettingsPage.EH, GallerySettingsPage.ADVANCED -> null
                        GallerySettingsPage.DOWNLOAD -> DownloadScreenDestination
                        GallerySettingsPage.PRIVACY -> PrivacyScreenDestination
                        GallerySettingsPage.ABOUT -> AboutScreenDestination
                    }
                    destination?.let { navigator.navigate(it) }
                    destination != null
                },
            )
        }
    }
}

@Composable
internal fun rememberGalleryBrowserHandler(): UriHandler {
    val context = LocalContext.current
    return remember(context) {
        object : UriHandler {
            override fun openUri(uri: String) = with(context) { openBrowserOrThrow(uri) }
        }
    }
}
