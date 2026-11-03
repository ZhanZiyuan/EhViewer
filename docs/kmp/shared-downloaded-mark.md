# Shared downloaded mark on list cards

Search cards now show Android 1.15.10's downloaded mark: a 16dp download icon before the language and page count on the rating row, for any gallery with a download entry, as `GalleryInfoListItem` does through `DownloadManager.collectContainDownloadInfo`. The shared screen derives the set from `GalleryDownloads.tasks`, which on Android mirrors the original `DownloadManager` and on Desktop/iOS is the shared Room/Rust queue, so the mark updates as downloads are added or removed. The icon is labelled "Downloaded" for accessibility (Android leaves it unlabelled; the pixels are unchanged). The new string is added to all four catalogs.

Reading progress (`n/mP`) stays off, matching Android's default `show_reading_progress = false`; exposing that preference is separate work.

Targets: GallerySearchCard.kt, GallerySearchScreen.kt, the four catalogs and GalleryPreviewIntegrationTest. The real download test now checks that the card has no mark before downloading. After the real paused-then-resumed download completes, it searches again and requires the mark, before going offline.

Local results: Android spotless/unit/host/lint/assemble, shared Desktop79 and the iOS arm64/simulator compile and debug link passed; source policy passed. The tablet passed 50/50 in both runs. Phone runs, in order:
- Run 1 failed 1/50: the test opened the drawer from the Downloads page, which has a Back button but no drawer button on phones. The test now returns with Back.
- Run 2 failed 2/50: a reader page-jump stage (passed in every other run), and the shared/legacy reader test's precheck found its fixture row already present.
- Run 3 failed the same precheck again.
- The leftover row came from run 1, whose own cleanup was lost when the emulator was killed before guest storage was flushed. After clearing the test app's data on the test-only emulator, run 4 failed 1/50: the first-run production destination test could not see search after Back.
- Run 5 passed 50/50.

Emulators are now synced before shutdown. The missing drawer button on phone Downloads/History is a recorded parity gap with Android, where those are top-level pages with a menu button.

Animeko was not consulted for this change; no AGPL code, assets or scripts are used. M5/M7/M8 stay open; stable 1.15.10/180074 and Draft PR15 remain.
