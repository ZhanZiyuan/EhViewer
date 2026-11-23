# Download ordering

v1.15.15 adds a persistent custom order to the Downloads screen. The app stores the selected mode in the existing integer preference, with a separate custom field and the original automatic sort directions and grouping flag.

## User interface

The list identifies galleries by GID and exposes its drag handle only in eligible custom-order views. The grid, active searches, selection, grouped views, and status or specific artist filters hide that control. The layout adjustments are scoped to rows with a gallery reorder handle. Label reordering has its own state and persistence.

## Storage and migration

- The Room 23 → 24 migration adds `DOWNLOADS.CUSTOM_POSITION`, a signed 64-bit position. It seeds positions using `TIME DESC, GID DESC`. The migration reads that order before writing, so it can run with the SQLite version shipped on API 26.
- A move saves a position between the neighboring entries. If there is no usable gap, the existing sequence is given new spaced positions within a transaction.
- `DownloadManager` serializes mutations with its command queue and sort mutex, checks the source and destination by GID, and writes the new positions before publishing the updated in-memory ordering. This keeps concurrent downloads, imports, deletions, and label changes from persisting a stale drag.
- Existing downloads keep their attributes when moved. New or restored entries receive a position at the front. Existing GIDs are skipped during import.

Implementation is in [`CustomDownloadOrder.kt`](../../app/src/main/kotlin/com/hippo/ehviewer/download/CustomDownloadOrder.kt), [`DownloadManager.kt`](../../app/src/main/kotlin/com/hippo/ehviewer/download/DownloadManager.kt), and [the Room migrations](../../core/data/src/commonMain/kotlin/com/ehviewer/core/database/DatabaseMigrations.kt).

## What to verify

- Old sort flags round-trip, and switching between automatic and custom modes restores the saved manual order.
- Migration preserves existing download rows, including rows with identical timestamps. Reopening the database preserves positions.
- Moving within a label preserves label membership and the relative order of other galleries. Grid display, task actions, selection, and label dragging continue to work.
- Custom and automatic rows align their page counts on compact and wide screens. The v1.15.15 emulator matrix includes 320 dp phone windows, a 448 dp Pixel 10 Pro XL, and Pixel Tablet windows at 800 dp and 1280 dp in portrait and landscape. The device runs cover API 26, 29, 36, and 37, including 16 KiB pages.

See [the user guide](../user-guide/downloads.md) and [the test commands](development.md#run-device-checks).
