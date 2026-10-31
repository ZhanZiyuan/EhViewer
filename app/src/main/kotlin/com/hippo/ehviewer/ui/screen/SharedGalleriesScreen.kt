package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.ehviewer.shared.GalleryLibraryScreen
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.EhScreenDestination
import com.hippo.ehviewer.ui.navToReader
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
    val scope = rememberCoroutineScope()
    val downloads = remember { AndroidGalleryDownloads(scope) }
    key(site, signedIn) {
        val library = remember { AndroidGalleryLibrary(site == EhUrl.SITE_EX) }
        GalleryLibraryScreen(library, library.readerState, appearance, EhDB.sharedHistory, downloads, onPlatformDownloadOpen = { gid ->
            with(navigator) { navToReader(checkNotNull(DownloadManager.getDownloadInfo(gid)).galleryInfo) }
        }, onBack = { navigator.popBackStack() }, onAccount = { navigator.navigate(EhScreenDestination) })
    }
}
