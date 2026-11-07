# Desktop accessibility navigation repair

Normal macOS product testing of b56d694e481cbab5abc35d0a96c49cf5f47b42f1 twice exited when opening an anonymous search result. The captured exception is `NullPointerException` in `ArrayDeque.addFirst`, called by `ComposeSceneAccessibility.defaultAccessibilityFocusTarget` while removing the focused result. This is an actual interaction failure; the passing compile, headless layout tests and packaged M1 window probe did not establish product navigation acceptance.

The selected correction is the first subsequent stable Compose release, 1.12.0. Its [release notes](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.0) explicitly include the [null accessible-child fix](https://github.com/JetBrains/compose-multiplatform-core/commit/81c2b3c283afae15b642f39dc8f8a1859041a755). The official 1.12.0 source archive was inspected; SHA-256 is `d7ff90e4158e2c56eb6abb730c4ef2e314a6ac8d50f70c586e752d5635d2c7f8`. Accessibility stays enabled. No exception suppression, patched vendor classes or substituted focus semantics are used.

Compose 1.12.0 requires its matching Skiko 0.150.1 and Material 3 1.12.0-alpha03 components. An intermediate build retaining Material 3 1.11.0-alpha07 passed compilation and normal navigation but failed opening the page-number field with `AbstractMethodError: CustomStyle.applyStyle`. Its binary, screenshot and complete exception log remain retained. The new `GalleryInputDialogRenderTest` renders the production page-jump and search-filter dialogs. A temporary test-only classpath injection of the old Material JAR reproduces that exact failure in both cases; the ordinary aligned Desktop suite then passes all 81 tests with zero skips. The temporary injection is not part of the build configuration or committed source.

Six original Skiko vendor archives were checked against their official Maven Central `.jar.sha256` files, ZIP CRCs and actual Mach-O/ELF/PE headers. Existing strict pins are replaced with these measured matching-version bytes; architecture, package, source-SHA and signature requirements are retained.

| Vendor runtime | Original JAR SHA-256 | Matching native SHA-256 |
| --- | --- | --- |
| macos-arm64 | bd186dd6bf8736eb69be190107e1b3ab7bd2452ae917faaa516ef513217a5f33 | 4b22275236c861e1288750f3022a5851440f1ba7dec227e6d428b5ee0899175a |
| macos-x64 | 64989da062646356d56a45fc5a2354624e9617260bea96b2d8f04b4080d14595 | a0feb28bb78e138050eaa0d91b083e041a9e8a62476fbe3c3bf49fa9f2aabc53 |
| linux-arm64 | 2de2e59a628ff459c42fb51e636f6d10bcff1474ece1a3f97bddd2830f45a893 | ee66e016db90b5628bc4277c074ece4f88f3ccf0f2c736ab6053ba12f7bb36fe |
| linux-x64 | 920c1cb033abfb79202f9f455fb86491bec5e45c156f186c0125b10fce97e093 | 10674295a4eb78232b97fb94c20a66687b8af8e4b512b1224dcf75f8e16cc030 |
| windows-arm64 | 570229ae57e659393444d70d5266912af84c99cc50df9092716dc5f0fb8261aa | 8bddb4a42facbf3117447f9acda90b2e7f02aa38f259c1c92e91c2f685650549 |
| windows-x64 | 22a5e52c9fb4278f998785c5029d96f007c8eb8944f6eb0d3f4b06fb3775edcf | 5061ffbbfbad9fc8c6c8e0c1741b674c863293d3d071b061db9e6e0fc3ef1d38 |

Both macOS vendor JARs contain both CPUs; the existing selector still authenticates the original JAR and retains only the requested CPU. Their ad-hoc signatures differ, so the table binds each native to its own vendor archive. Local format inspection is not Windows/Linux execution acceptance; the repair needs its own Full CI including all native consumers and release dry-run.

The Apache-2.0 LICENSE and NOTICE were fetched from Skiko tag v0.150.1, commit `3956e988e6e93eaf1ee985d725049443e7845807`. They match the retained earlier texts byte for byte; the versioned origin and installed-notice checks now describe the linked version. The old failed validation bundles and profiles remain in ignored `build/kmp-work-logs/uiux-history-b56-mac-20261104/`. Each rebuilt validation bundle uses a new empty home; no older encrypted profile or denied Keychain item is copied or read. Animeko was not consulted and no AGPL implementation is reused.

Final local results: Android478 tasks (tests, lint, format, original APK and benchmark builds); shared Desktop81/network144/data87; verifier277, all without failures or skips. The final identical APK pair passed phone50/tablet50 with zero skips. Actual iOS execution passed network144/shared73/data81, Keychain, both cold starts, foreground/background and Rust2000/live0 per launch. The earlier compile-only intermediate Native/Android outputs are not substitutes for these final aligned runs.

Normal macOS interaction now passes search → real detail → decoded page1, invalid page0 validation, an actual jump to decoded page2, reopening/cancelling the field with value2, and returning to the saved second-page action. History's cancel keeps the card; confirmed clear empties it, and the same retained binary exits normally and restarts with empty history. Reopening that gallery after restart still offers page2. Two raw 1600×1200 CUA JPEGs and accessibility trees (populated/empty History) have SHA-256 inventories beside the retained binaries. Their bytes were not edited or converted; their initial `.png` suffix was corrected to the actual JPEG format. These are macOS ARM64 UI evidence, not other-platform screenshots or pixel-parity acceptance.

The repair still requires its own Full CI, native consumers and release dry-run. M5/M7/M8 and pixel-level parity remain open. Stable 1.15.10/180074, the eleven-asset contract and Draft PR15 remain unchanged.
