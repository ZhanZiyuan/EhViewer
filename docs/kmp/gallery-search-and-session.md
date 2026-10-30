# Shared gallery search and secure sessions

This M3 slice uses `core/network` for the real Ktor request, cookie/session contract and gallery repository, and `app/shared` for the same Compose Multiplatform search screen. Desktop and iOS launch this business screen by default. The original explicit startup/archive regressions remain available to CI. M3 is not complete.

The search flow supports anonymous browsing, title/tag search, cursor pagination, loading, retry and empty results. Its temporary browser handoff is superseded by the [shared details and online reader](gallery-details-and-reader.md). Android now opens the same CMP search/detail/reader through its production drawer, borrowing the original client, browser cookies and Room/DataStore instead of replacing them; see [account and Android integration](account-initialization.md).

## Shared search filters

`GallerySearchFilters` and the common CMP dialog implement the existing Android service protocol from `ListUrlBuilder.kt`, `EhUtils.kt` and `AdvanceTable.kt`: ten category bits, optional minimum rating (2–5), positive page bounds and the five original advanced flags. Categories default to the site's saved preferences; explicit selections become the original inverted `f_cats` mask. Numeric bounds are validated before a request, including deserialization and copies. The form edits a private draft; Cancel discards it, Reset restores site defaults, and Apply starts a new search. Paging captures the successfully submitted query/filter pair. The same saveable form and accessible checkbox state are used on Android, Desktop and iOS.

This is independently implemented from EhViewer's own protocol, with no new dependency or Animeko implementation reuse. It continues the already audited common UI/platform ownership boundaries. An anonymous, TLS-verified HTTPS request on 2026-10-30 returned HTTP 200 and a real form containing all ten requested filter field names. That observation verifies form/protocol availability, not authenticated account behavior or a complete application interaction.

Local preparation passed **66 network / 20 reader tests** on Android Host and Desktop, **232 verifier tests**, affected Android tests/Release lint/four APKs/benchmark/instrumentation compilation (**406 Gradle tasks / 3m21s**) and a real Kotlin-only iOS Simulator app build. APK identity, test signatures, actual ABIs/16KB alignment, diagnostics and Java 17 bytecode passed. A fresh owned iPhone Simulator also passed **66 Native network / 20 reader / 3 Keychain tests**, two cold starts with all existing account/gallery/image/WebKit/Rust ownership assertions, and lifecycle/background/resume checks; independent readback confirmed deletion.

The actual Android shared-entry test now changes a category draft, cancels it and verifies that applied filters remain unchanged, alongside real display rotation and account navigation. Both phone and tablet passed all **32 tests on identical APKs**: application `fb0478e25a22cc77937a19da62bec4e2b7704a5f7ac3c800c4cf2a11161e8b09`, test APK `852283f3f87e307e0ab795814879a64f94ca4d3e7ef57d861e3d92959b1c681e`. The first phone attempt was obstructed by a captured emulator **System UI isn't responding** dialog; no application runtime crash was recorded. Restarting the owned emulator after compilers finished passed the identical APKs and assertions; the failure remains preserved. Manual real-site form interaction remains unverified while the host is locked. This feature is prepared separately from the preceding verified account/index snapshot and is not yet committed or accepted by hosted CI. M3 remains incomplete.

## Platform boundaries

| Shared contract | Desktop | iOS | Android |
|---|---|---|---|
| Ktor engine | OkHttp | Darwin, automatic system cookies disabled | Original application Cronet/OkHttp client and browser integration |
| Gallery HTML decoding | Original Rust parser via JNI; Windows uses the existing secure C ABI loader | Same parser through generated C ABI declarations | JNI adapter available; existing parser path retained |
| Persistent session snapshot | Rust AES-256-GCM; OS Keychain, Credential Manager or Secret Service holds only a random 32-byte key | Same authenticated snapshot; real iOS Keychain | Existing browser cookie store; no desktop vault substituted |
| Search UI | Shared CMP screen | Same CMP screen inside the Kotlin UIKit entry | Same CMP screen through the original navigation and data adapters |

Cookies retain HTTPS, host-only/domain, path-boundary and expiry rules. A corrupt or inaccessible store fails explicitly and does not silently become a successful empty session. Cancellation propagates. Public exceptions omit cookies, URLs, response bodies and backend causes. Logout clears the active encrypted snapshot. [Authentication](gallery-authentication.md), [iOS browser handoff](browser-sign-in.md) and [desktop native browser handoff](desktop-browser-sign-in.md) now extend this initial slice; full live-account and platform P0 acceptance remains incomplete.

Requests use a [streaming Ktor statement](https://api.ktor.io/ktor-client-core/io.ktor.client.statement/-http-statement/execute.html), stop above 8 MiB, and cancel the channel. A producer-backed regression detects accidental eager buffering. Rust parsing runs off the UI dispatcher. No HTTP logging plugin or production in-memory vault is installed.

## iOS Keychain acceptance

Actual Simulator execution first failed with `errSecMissingEntitlement` (-34018). Applying iOS entitlements to a macOS ad-hoc code signature then correctly failed the host launch policy. The working build embeds the Simulator identity in `__TEXT,__entitlements` and keeps the signature plain ad hoc, as used by [Chromium's native Simulator build](https://chromium.googlesource.com/chromium/src/build/config/+/refs/heads/main/ios/rules.gni).

`app/ios/Simulator.entitlements` is restricted to the Simulator executable and Rust test runner. Its `SIMULATOR` prefix is not a development team. It is never applied to the device executable or treated as a provisioning profile. The real Rust tests exercise Keychain key creation/restoration/deletion, encrypted-file round-trip/logout, wrong-key/tamper rejection and failure preservation. Test credentials and directories are owned and cleaned up.

Device execution still requires valid development signing, which is unavailable. The eventual `arm64-unsigned.ipa` remains unsigned and is not directly installable; this slice does not change that release contract.

The local startup runner also encountered a newer device-specific runtime with two displays, making its single-display capture ambiguous. Default acceptance now selects a general iOS runtime; explicit runtime selection remains supported. This does not expand the requested product scope to foldable devices.

## Reference and licensing

| Read-only Animeko reference | EhViewer destination | Adopt / adapt / reject |
|---|---|---|
| `app/shared/application/src/commonMain/kotlin/platform/CommonKoinModule.kt` | `GallerySession.kt`, platform application factories | Adopt the service/lifetime boundary; inject real engine, decoder and vault directly. Do not introduce its Koin dependency tree. |
| `app/shared/app-data/src/commonMain/kotlin/domain/session/SessionStateProvider.kt` | `PersistentCookieStorage.kt`, session error handling | Adapt the separation between session state and platform storage. Retain EhViewer's cookies/site protocol; reject Bangumi/Ani OAuth types and token refresh behavior. |
| Previously audited shared UI/platform entry organization | `app/shared`, Kotlin UIKit entry and desktop root | Share the CMP business screen; retain EhViewer gallery/Rust features. No video, torrent or danmaku module is adopted. |

No Animeko code, resources, scripts or dependency set is copied or mechanically rewritten. The reference remains the M0 read-only directory snapshot with unverifiable Git SHA. The Rust parser and CBOR models reused here belong to EhViewer.

Ktor/Darwin/MockEngine and coroutine-test aliases use this repository's existing pinned versions. The independently selected Rust session dependencies pin keyring 4.2.0, ring 0.17.14, zeroize 1.9.0 and security-framework 3.7.0; the latter three versions already existed in the lock. Desktop keyring v1 does not support iOS, so iOS calls the existing Security framework dependency directly. Direct notices and origin/license selections are retained under `app/desktop/licenses/session` and included in the app packages. Full transitive SBOM/release-license acceptance remains M6 work.

The lock diff adds 64 packages, including the owned session crate, and removes or upgrades no existing package/version. All 63 new registry archives were checked against their locked SHA-256; their MIT notices and exact origin/digest inventory are retained in `licenses/session/locked-components.toml`. Only license text is copied from these archives. The full application/JVM/Native SBOM remains a separate release acceptance item.

## Verification scope

Android/JVM each execute all 19 shared network/session tests; iOS executes the same common tests as Native code. The shared verifier binds seven AARs and four JVM JARs to actual Java 17 compiler output and real test XML. Original Android tests/lint/four APKs, native ownership/archive checks, signature isolation, diagnostics and the stable `ci/gate` remain mandatory.

Manual business checks exercised real anonymous data in the Desktop window and iOS 27 Simulator: first page, a distinct next page and enabled previous navigation. iOS also accepted a synthetic search string and displayed the real empty-result state with disabled page controls. Desktop text entry, iPad/tablet layout, authenticated login, other desktop business windows and complete P0 are not inferred from these checks.

Every committed result is recorded by the source-bound Actions Summary on PR #15. No additional bookkeeping commit or duplicate feature Push matrix is required. This Native/build change requests the full PR plan and dry run. The version stays `1.15.10 / 180074`, the PR stays Draft, and no tag, formal Release or merge is authorized.
