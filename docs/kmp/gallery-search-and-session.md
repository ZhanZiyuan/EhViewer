# Shared gallery search and secure sessions

This M3 slice uses `core/network` for the real Ktor request, cookie/session contract and gallery repository, and `app/shared` for the same Compose Multiplatform search screen. Desktop and iOS launch this business screen by default. The original explicit startup/archive regressions remain available to CI. M3 is not complete.

The visible flow supports anonymous browsing, title/tag search, cursor pagination, loading, retry and empty results. Gallery rows temporarily open the real gallery URL in the platform browser. Native detail, preview/image loading and the reader are the next product slice. Android compiles the same network/UI libraries; integration with its existing navigation and WebView login is still pending. The existing Android application, browser cookies and Rust reader remain intact.

## Platform boundaries

| Shared contract | Desktop | iOS | Android |
|---|---|---|---|
| Ktor engine | OkHttp | Darwin, automatic system cookies disabled | OkHttp; existing browser integration retained |
| Gallery HTML decoding | Original Rust parser via JNI; Windows uses the existing secure C ABI loader | Same parser through generated C ABI declarations | JNI adapter available; existing parser path retained |
| Persistent session snapshot | Rust AES-256-GCM; OS Keychain, Credential Manager or Secret Service holds only a random 32-byte key | Same authenticated snapshot; real iOS Keychain | Existing browser cookie store; no desktop vault substituted |
| Search UI | Shared CMP screen | Same CMP screen inside the Kotlin UIKit entry | Shared library built; navigation integration pending |

Cookies retain HTTPS, host-only/domain, path-boundary and expiry rules. A corrupt or inaccessible store fails explicitly and does not silently become a successful empty session. Cancellation propagates. Public exceptions omit cookies, URLs, response bodies and backend causes. Logout clears the active encrypted snapshot. Authentication UI and browser session handoff remain subsequent M3 work.

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
