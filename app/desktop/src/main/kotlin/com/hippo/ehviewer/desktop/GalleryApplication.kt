package com.hippo.ehviewer.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.ehviewer.core.data.DataStoreGalleryListPreferences
import com.ehviewer.core.data.DataStoreLanguageStore
import com.ehviewer.core.data.DataStoreNetworkPreferences
import com.ehviewer.core.data.DataStoreStoragePreferences
import com.ehviewer.core.data.FileArchiveLibrary
import com.ehviewer.core.data.RoomGalleryDownloadStorage
import com.ehviewer.core.data.RoomGalleryHistoryStore
import com.ehviewer.core.database.DataDirectory
import com.ehviewer.core.database.EhDatabase
import com.ehviewer.core.database.Schema17to18
import com.ehviewer.core.database.download.DownloadDatabase
import com.ehviewer.core.database.ownedRoomDb
import com.ehviewer.core.domain.reader.ArchiveDocumentFactory
import com.ehviewer.core.network.GallerySession
import com.ehviewer.core.network.archive.NativeArchiveBridge
import com.ehviewer.core.network.gallery.RustGalleryDecoder
import com.ehviewer.core.network.session.NativeSessionVault
import com.ehviewer.shared.ArchiveLibraryScreen
import com.ehviewer.shared.GallerySearchScreen
import com.ehviewer.shared.reader.ReaderStateRepository
import com.ehviewer.shared.settings.GalleryAppearanceRepository
import com.ehviewer.shared.settings.GalleryLanguageSettings
import com.ehviewer.shared.settings.galleryResourceLanguageTag
import java.nio.file.Path
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
    DataDirectory.configure(applicationData().resolve("database").toString().toPath())
    val resources = Path.of(checkNotNull(System.getProperty("compose.application.resources.dir")))
    val windows = System.getProperty("os.name").startsWith("Windows")
    val version = System.getProperty("jpackage.app-version") ?: "development"
    val session = if (windows) {
        windowsGallerySession(resources.resolve("ehviewer_capi.dll"), applicationData(), "EhViewer/$version")
    } else {
        val extension = if (System.getProperty("os.name").startsWith("Mac")) "dylib" else "so"
        System.load(resources.resolve("libehviewer_rust.$extension").toAbsolutePath().toString())
        GallerySession(NativeSessionVault(applicationData().toString()), RustGalleryDecoder, "EhViewer/$version", networkPreferences = DataStoreNetworkPreferences())
    }
    val readerDirectory = applicationData().resolve("reader-state")
    val readerState = if (windows) windowsReaderState(resources.resolve("ehviewer_capi.dll"), readerDirectory) else ReaderStateRepository(NativeSessionVault(readerDirectory.toString()))
    val appearanceDirectory = applicationData().resolve("appearance")
    val appearance = if (windows) windowsAppearanceStore(resources.resolve("ehviewer_capi.dll"), appearanceDirectory) else GalleryAppearanceRepository(NativeSessionVault(appearanceDirectory.toString()))
    val browserLibrary = resources.resolve(if (windows) "ehviewer_capi.dll" else "libehviewer_rust.${if (System.getProperty("os.name").startsWith("Mac")) "dylib" else "so"}")
    val browsers = DesktopBrowserService(browserLibrary, resources.resolve(if (windows) "ehviewer_browser.exe" else "ehviewer_browser"))
    val systemLanguage = Locale.getDefault().toLanguageTag()
    val language = GalleryLanguageSettings(DataStoreLanguageStore()) { tag ->
        Locale.setDefault(Locale.forLanguageTag(galleryResourceLanguageTag(tag ?: systemLanguage)))
    }
    val listPreferences = DataStoreGalleryListPreferences()
    val databaseOwner = ownedRoomDb<EhDatabase>("eh.db") { addMigrations(Schema17to18()) }
    val database = databaseOwner.database
    val history = RoomGalleryHistoryStore(database)
    val downloadDatabaseOwner = ownedRoomDb<DownloadDatabase>("downloads.db") {}
    val downloadDatabase = downloadDatabaseOwner.database
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val downloadStorage = RoomGalleryDownloadStorage(downloadDatabase, applicationData().resolve("downloads").toString().toPath(), preferences = DataStoreStoragePreferences())
    val downloads = session.downloads(downloadStorage, scope)
    val windowsArchives = if (windows) WindowsArchiveFactory(resources.resolve("ehviewer_capi.dll")) else null
    val archives = FileArchiveLibrary(applicationData().resolve("local-library").toString().toPath(), windowsArchives ?: ArchiveDocumentFactory(NativeArchiveBridge::open))
    try {
        application {
            val icon = remember { BitmapPainter(desktopApplicationIcon()) }
            Window(onCloseRequest = ::exitApplication, title = "EhViewer", icon = icon) {
                GallerySearchScreen(session, readerState, appearance, history, downloads, language, archiveContent = { back ->
                    ArchiveLibraryScreen(archives, session, pickAndImport = {
                        pickComicArchive()?.let { archives.importFile(it.toString(), it.fileName.toString().replace('\n', ' ').replace('\r', ' ').take(1024)) }
                    }, pickAndImportDirectory = {
                        pickImageDirectory()?.let { archives.importDirectory(it.toString(), (it.fileName?.toString() ?: "Imported images").replace('\n', ' ').replace('\r', ' ').take(1024)) }
                    }, pickAndExport = { book -> exportComicArchive(archives, book) }, readingState = { book ->
                        val directory = applicationData().resolve("archive-reading-state").resolve(book.id)
                        if (windows) windowsReaderState(resources.resolve("ehviewer_capi.dll"), directory) else ReaderStateRepository(NativeSessionVault(directory.toString()))
                    }, onBack = back)
                }, browserFactory = browsers::create, storageSettings = downloadStorage, listPreferences = listPreferences)
            }
        }
    } finally {
        runBlocking { downloads.closeAndJoin() }
        runBlocking { archives.close() }
        windowsArchives?.close()
        scope.cancel()
        downloadDatabaseOwner.close()
        browsers.close()
        session.close()
        databaseOwner.close()
    }
}
