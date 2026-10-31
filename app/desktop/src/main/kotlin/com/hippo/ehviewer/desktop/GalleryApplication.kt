package com.hippo.ehviewer.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.ehviewer.core.data.RoomGalleryHistoryStore
import com.ehviewer.core.database.DataDirectory
import com.ehviewer.core.database.EhDatabase
import com.ehviewer.core.database.Schema17to18
import com.ehviewer.core.database.ownedRoomDb
import com.ehviewer.core.network.GallerySession
import com.ehviewer.core.network.gallery.RustGalleryDecoder
import com.ehviewer.core.network.session.NativeSessionVault
import com.ehviewer.shared.GallerySearchScreen
import com.ehviewer.shared.reader.ReaderStateRepository
import com.ehviewer.shared.settings.GalleryAppearanceRepository
import java.nio.file.Path
import okio.Path.Companion.toPath

private fun applicationData(): Path {
    val os = System.getProperty("os.name")
    val home = Path.of(System.getProperty("user.home"))
    val base = when {
        os.startsWith("Mac") -> home.resolve("Library/Application Support")
        os.startsWith("Windows") -> Path.of(System.getenv("LOCALAPPDATA") ?: home.resolve("AppData/Local").toString())
        else -> System.getenv("XDG_DATA_HOME")?.takeIf { Path.of(it).isAbsolute }?.let(Path::of) ?: home.resolve(".local/share")
    }
    return base.resolve("EhViewer").toAbsolutePath().normalize()
}

/** A normal product window; the existing explicit --m1-smoke path keeps its original regressions. */
fun galleryApplication() {
    val resources = Path.of(checkNotNull(System.getProperty("compose.application.resources.dir")))
    val windows = System.getProperty("os.name").startsWith("Windows")
    val version = System.getProperty("jpackage.app-version") ?: "development"
    val session = if (windows) {
        windowsGallerySession(resources.resolve("ehviewer_capi.dll"), applicationData(), "EhViewer/$version")
    } else {
        val extension = if (System.getProperty("os.name").startsWith("Mac")) "dylib" else "so"
        System.load(resources.resolve("libehviewer_rust.$extension").toAbsolutePath().toString())
        GallerySession(NativeSessionVault(applicationData().toString()), RustGalleryDecoder, "EhViewer/$version")
    }
    val readerDirectory = applicationData().resolve("reader-state")
    val readerState = if (windows) windowsReaderState(resources.resolve("ehviewer_capi.dll"), readerDirectory) else ReaderStateRepository(NativeSessionVault(readerDirectory.toString()))
    val appearanceDirectory = applicationData().resolve("appearance")
    val appearance = if (windows) windowsAppearanceStore(resources.resolve("ehviewer_capi.dll"), appearanceDirectory) else GalleryAppearanceRepository(NativeSessionVault(appearanceDirectory.toString()))
    val browserLibrary = resources.resolve(if (windows) "ehviewer_capi.dll" else "libehviewer_rust.${if (System.getProperty("os.name").startsWith("Mac")) "dylib" else "so"}")
    val browsers = DesktopBrowserService(browserLibrary, resources.resolve(if (windows) "ehviewer_browser.exe" else "ehviewer_browser"))
    DataDirectory.configure(applicationData().resolve("database").toString().toPath())
    val databaseOwner = ownedRoomDb<EhDatabase>("eh.db") { addMigrations(Schema17to18()) }
    val database = databaseOwner.database
    val history = RoomGalleryHistoryStore(database)
    try {
        application {
            Window(onCloseRequest = ::exitApplication, title = "EhViewer") {
                GallerySearchScreen(session, readerState, appearance, history, browserFactory = browsers::create)
            }
        }
    } finally {
        browsers.close()
        session.close()
        databaseOwner.close()
    }
}
