# Shared gallery detail header

This M5 slice brings the original Android detail header into the common Compose UI: the actual cover, language/size/page summary, information sheet, category and uploader routes, and existing read/download actions. It does not complete M5 or pixel-level parity for the entire detail screen.

## Reference and design decisions

The Android reference is tag `v1.15.10`, commit `423b8af13632896b55780783b54b5f720ec4c9dd`. The official arm64 APK was authenticated against its published SHA-256 (`befab5b136627a49b8c525277a66d6bfcbcbb09ff4947d4d86e9395c52419a42`) and declares version `1.15.10` / `180074`. The reference source files below are unchanged from that tag.

| Reference | Common target | Decision and difference |
| --- | --- | --- |
| EhViewer `app/src/main/kotlin/com/hippo/ehviewer/ui/main/GalleryDetail.kt`, `ui/screen/GalleryDetailContent.kt`, `app/src/main/res/values/dimens.xml` | `app/shared/.../GalleryDetailHeader.kt`, `GalleryDetailScreen.kt` | Adapt the existing project's 128 × 192dp cover, 16dp keyline, information card and equal compact action row. At 840dp of available content, place a 480dp header beside stacked read/download actions. Bound long names and allow large-font content to grow. |
| EhViewer `GalleryInfoBottomSheet` and existing Android translations | `GalleryDetailInformation.kt`, four common string catalogs | Keep the 21 metadata fields and their order. Use the real canonical cover URL and platform clipboard. Exclude API credentials. A parent value is copied rather than opened as another gallery; that navigation remains pending. |
| EhViewer uploader/category navigation | `GallerySearchScreen.kt`, `core/network/.../gallery/GalleryRepository.kt` | Use the real `/uploader/{name}` route with one encoded segment, shared cookies, cursors and filters. Reject invalid input before transport. Category chips start a real category-filtered request. |
| Read-only Animeko `app/shared/ui-foundation/src/commonMain/kotlin/platform/PlatformWindow.kt` | Existing common layout and platform hosts | Retain platform/window boundaries, but derive this component's layout from its available width. Do not introduce Animeko window implementations or media-specific models. |

The implementation and translated labels derive from EhViewer's own source and resources. The Animeko snapshot was only read for architectural context; no AGPL implementation, assets, scripts or dependency set were copied. Its lack of verifiable Git metadata remains recorded in the M0 reference audit.

The cover reuses the existing bounded Coil/Ktor cache, including the search result's cover while details load. Read/resume still uses real page tokens and decoded-only saved progress. Existing downloads open their management screen instead of being enqueued again; Android keeps its original download adapter. This deliberately retains the current common download interaction until the remaining original Android controls are adapted.

## Validation

All local receipts are retained under ignored `build/kmp-work-logs/uiux-detail-header-*`; their source is the predecessor `1ef9f9d2d8789d5fdfedbedcb4041380043d8517` plus the recorded working-tree changes, not a clean future commit.

- Android mandatory tests/lint/build, original native build tasks and both instrumentation APKs: 478 tasks, SUCCESS. The separate compiled-resource/APK artifact check first failed in CI; its build success was not package acceptance. See [the strict plural-resource repair](compiled-plural-resources.md).
- Desktop production distribution and tests: shared83, network146 and data87; zero failures/errors/skips. New rendering checks exercise 360/411/1280px layouts at 1× and 1.8× font size. They assert reachable, nonoverlapping, equally sized compact actions and stacked wide actions without assuming a host font's exact glyph metrics.
- Common network tests exercise actual Ktor mock transport for Unicode and reserved characters in uploader names, both E/Ex hosts, pagination and pre-transport rejection.
- iOS simulator package and arm64 device executable: SUCCESS. Actual owned simulator execution: network146, shared73 and data81, real Keychain3, cold starts, foreground/background and Rust ownership checks passed. Signed physical-device execution remains NOT_VERIFIED.
- Normal macOS interaction uses a newly owned empty profile and the frozen application JAR inventory. Real public data loaded the header, information sheet, uploader results and category results. Final-source screenshots and interaction records are retained alongside the profile; no previously denied Keychain items were accessed.

The first phone run executed all50 cases and failed only the old first-image request count: the new detail cover and downloaded page share `/0.png`. The test now requires exactly one gallery-referred cover and one page-referred download, preserving the no-repeat constraint and every test/deadline. The test-only 180-task rebuild passed. The unchanged production APK (`8c960e88348550d7de9ad77dd169c58d873fd0fb962479ca5239027e4db38388`) and updated test APK (`a69671b8e812ad56f7920b326045e8fc84b61468232f13a76437c207777b07e9`) passed serial phone50/tablet50, zero skips. The original failed receipt remains retained.

This commit's hosted platform checks must also pass before this slice is accepted. Screenshots from another platform, a fixture or an earlier binary do not satisfy that evidence. Final-source normal macOS screenshots include the real decoded page and compact/wide details with page2 resume; raw JPEG bytes, actual dimensions and SHA-256 inventories are retained without editing.

## Remaining UI acceptance

The detail toolbar, preview grid, original rating/tag/comment controls and reader chrome still need their own visual adaptation and interaction checks. The current generic toolbar and page list are not claimed as pixel-equivalent. iPhone/iPad, Android phone/tablet and all three desktop OSes still need the requested final two-screen evidence per form factor/OS. M5, M7 and M8 remain open; version `1.15.10` / `180074` and the Draft PR are retained.
