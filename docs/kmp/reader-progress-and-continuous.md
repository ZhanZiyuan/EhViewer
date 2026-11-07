# Shared reading progress and continuous reading

This M3–M5 slice adds resumable reading, persisted direction and continuous reading to the real search → detail → image flow. Desktop and iOS use the same Compose Multiplatform UI and KMP state repository. It does not complete M3 or the full reader P0. Android compiles and tests the shared implementation; adapting its existing Room progress and preferences remains a separate integration step.

## Behavior

- Details offer **Continue reading** and **Read from start** after a successfully decoded page has been saved. Returning refreshes this position. Saved tokens resume directly; index-only progress resolves the actual gallery preview window rather than inventing a token.
- Paged reading persists left-to-right/right-to-left direction and mirrors arrow keys and previous/next controls. PageUp/PageDown retain logical order. Continuous reading uses a common lazy vertical list and switches back to the current decoded page in paged mode.
- Continuous reading owns at most the current and next page, with two concurrent requests and a 64 MiB decoded-pixel budget. Scrolling cancels work outside this window; request identities reject late cancelled results. Existing image/network bounds remain enforced. Errors require explicit retry, using the real H@H reload key when available.
- Page-shaped placeholders, a bounded cache of 1,024 known aspect ratios and viewport-based inertial speed keep fast touch flings from skipping dozens of unloaded pages. Extreme aspect ratios use a bounded layout with fitted pixels. Switching to paged reading waits for the current page to decode instead of jumping to the old starting URL.
- Only the visible decoded page updates progress. Preferences update the UI after persistence succeeds. Invalid, future-schema and oversized state is preserved with a recoverable error. Cancellation after a possible native atomic commit invalidates memory and reloads disk.

`ReaderStateRepository` uses a distinct `reader-state` directory with the real OS-backed `SessionVault`: macOS Keychain/Linux Secret Service through JNI, Windows Credential Manager through the existing C ABI adapter, and iOS Keychain through Kotlin/Native. Cookies and progress have separate encrypted payloads. The versioned snapshot bounds history to 1,024 galleries and 256 KiB. It does not replace the remaining database/settings/download migrations.

Actual iPad reinstall testing exposed a pre-existing vault identity defect: using the current absolute directory to identify the Keychain key fails when iOS relocates the preserved data container. A private, non-secret `.session-account-v1` file now atomically retains the original account identifier under the existing directory lock; master keys remain only in the OS credential store. Existing snapshots at their original location enroll their existing key without re-encryption. An already-relocated legacy snapshot without that identifier remains an explicit error: neither its ciphertext nor its key is silently replaced. The original failed test snapshot is retained locally. This follows the container lifecycle described in [Apple TN2285](https://developer.apple.com/library/archive/technotes/tn2285/index.html).

The original three Rust session tests retain their crypto/logout checks and now also exercise actual OS-store recovery after directory relocation, legacy account enrollment, missing identity after relocation, malformed metadata and Unix symlink rejection. C ABI signatures and encryption format remain unchanged.

## Reference and license decisions

| Read-only Animeko path | EhViewer target | Adopt / adapt / reject | License handling |
|---|---|---|---|
| `app/shared/app-data/src/commonMain/kotlin/data/persistent/SettingsStore.kt` | `core/domain/.../reader/ReadingState.kt`, `app/shared/.../reader/ReaderStateRepository.kt` | Adopt common state with injected platform storage. Adapt to EhViewer's authenticated vault and explicit corruption errors. Reject automatic corruption replacement, unrelated stores and the dependency graph. | Architecture only; no AGPL code, resources, scripts or dependencies copied. |
| `app/shared/app-data/src/commonMain/kotlin/data/repository/media/EpisodePreferencesRepository.kt` | Common reader preferences, gallery progress and UI recovery | Adopt persisted preferences behind an interface. Use gallery/page identities and explicit constructor injection; reject episode/media-source models and service lookup. | Independently written Kotlin; no mechanical rewrite or exact reuse. |
| Navigation/detail loader paths in [the preceding slice](gallery-details-and-reader.md) | Detail → reader → retained detail | Retain real identity and cancellation; use a bounded page window rather than a video player/cache abstraction. | Previous architecture-only boundary retained. |

Animeko remains a read-only snapshot without a verifiable Git SHA, as recorded in [M0](animeko-reference-audit.md). No dependency version is upgraded.

## Acceptance

The common tests cover image allocation (2), persistence/corruption/cancellation (9), page-window bounds/cancellation/retry (6), and preview-window resume resolution (3). Android Host and JVM execute all 20. The iOS startup verifier runs the same Native tests on its owned booted Simulator, checks fresh XML against mandatory suites, and retains the executed binary SHA. Release dry-run independently validates the XML and binary. Original network (28), Keychain and UIKit/Compose/Rust lifecycle regressions remain mandatory.

Local validation before this feature commit (dirty source evidence; hosted acceptance remains separate):

| Check | Actual result |
|---|---|
| Shared reader contracts | 20 each on Android Host, JVM and real iOS Native; zero failures/errors/skips. Final Native execution reused the owned iPad and took 11 seconds without rerunning unchanged compilation. |
| Original Android CI task set | Tests, formatting, Release lint, Debug/Release four-ABI builds and benchmark passed: 417 Gradle tasks, not a test count. |
| Actual Android/shared outputs | Seven AAR/four JVM JAR inventory and matching source-derived receipts; four APK signatures, ABI/JNI/16 KiB alignment, diagnostics and bytecode checks passed. |
| Rust | Core 24, archive 15, C ABI 11, JNI 5 and session 3 passed; original three online ignores unchanged. Strict host Clippy and all three Android target Clippy checks passed. Session tests now include real OS-store relocation and legacy enrollment. |
| Verifier | 227 tests passed, including rejecting stale/missing/skipped product receipts. |
| Actual iOS startup | Real Keychain tests including relocation; 28 network/20 reader tests; original two launches, background/resume, explicit termination and 2,000 owned FFI results per launch passed. Simulator app and device executable built. |
| Actual packaged macOS | Real images, RTL controls, continue page 2, continuous page 5 → paged page 5, process exit/relaunch → continue page 5/RTL passed. Final package rebuilt after native/layout fixes. |
| Actual iPad update | Different installed executable and data-container path; account identifier and ciphertext hashes unchanged. Same gallery resumed and decoded page 102, with continuous mode and RTL retained. |
| Actual iPad input/touch/rotation | Screen keyboard searched `nishikibasami` and returned matching results. Repeated identical drags advanced page 2 → page 3 after inertial limiting; landscape retained page 3. |

The extra broad root `lint` invocation failed with 1,140 `RestrictedApi` errors exclusively in unchanged Room KSP-generated `core/data` files. Its report is retained separately; no lint rule, baseline or production implementation was changed to suppress it. The existing required `:app:lintRelease` and affected shared lint passed. This extra result is not claimed as a pass.

The preceding receipt repair `12820976e` completed [all twelve required jobs, gate and dry-run](https://github.com/ZhanZiyuan/EhViewer/actions/runs/41028854296) successfully before this feature may be pushed. Its earlier failed reader aggregation remains recorded on PR #15. This feature's own exact SHA/results are recorded automatically in Actions Summary and the same Draft PR; they must not be inferred from local results. Logs remain under ignored `build/kmp-work-logs/`.

Remaining work includes Android product integration, authentication handoff, database/configuration migrations, detail thumbnails/actions, animated images, complete input/accessibility/performance acceptance, downloads, archives and eleven final assets. No signed device acceptance is claimed. Stable version remains `1.15.10 / 180074`; final `1.16.0` preparation remains an independent M8 commit. PR #15 stays Draft.
