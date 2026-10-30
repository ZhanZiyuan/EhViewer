# Shared authentication and account controls

This M3 product slice adds the same CMP account screen on Desktop/iOS, backed by real shared Ktor forum login and the existing OS-backed session vault. It is not full F01 or M3 acceptance. Android builds/tests the common implementation; its existing sign-in flow and browser store remain until their adapter is independently verified.

The form uses EhViewer's existing `EhEngine.signIn` endpoint, field names and login response contract. Credentials remain in composition/request memory, are not saved as instance state, and never enter the persisted snapshot or public errors. Redirects are bounded, validated before contact, and use GET without replaying the password. HTML is limited to 8 MiB; authentication, challenge, rate-limit, server, transport and storage errors remain distinct and redacted.

The actual login HTTP transaction uses an isolated bounded cookie jar with the same HTTPS, origin/domain, host-only, path and expiry checks as persistent cookies. This is transient protocol state, not a substitute for persistence. Only a successful response and complete identity cookies can commit the new snapshot, once, through the mandatory platform vault. Rejected/incomplete logins preserve the prior session. Logout invalidates pending login commits and rejects late identity cookies; explicit successful sign-in can establish a new session. Atomic-write/clear cancellation invalidates memory and reloads the actual durable state.

Opening Account cancels pending search work. An identity action invalidates old results and query state. The form masks the password, disables invalid/duplicate submissions, supports the keyboard action and Back/cancellation, and shows secure-store recovery without silently substituting an empty session. Real forum redirects to `index.php?act=Login` now request authentication before fetching that page.

## Reference and license boundary

| Reference | Target | Decision / difference / license |
|---|---|---|
| Read-only Animeko `app/shared/app-data/src/commonMain/kotlin/domain/session/SessionStateProvider.kt` and `app/shared/application/src/commonMain/kotlin/platform/CommonKoinModule.kt` | `core/network/.../GallerySession.kt`, `session/GalleryAuthenticationRepository.kt`, `app/shared/.../GalleryAccountScreen.kt` | Adopt separation between session ownership and platform storage. Use EhViewer cookies, explicit constructors and cancellable requests; reject OAuth types, refresh protocol and DI dependency graph. Architecture only; no AGPL implementation copied or mechanically rewritten. |
| EhViewer `app/.../client/EhEngine.kt`, `EhUrl.kt` and `client/parser/SignInParser.kt` | Real common form request and response validation | Retain the existing service protocol with scoped staged cookies and redacted failures. Original sources/notices remain in place, including the parser's Apache-2.0 notice. No Animeko login protocol or code is used. |

No dependency version or production signing/release setting changes. Local Animeko remains an unmodified snapshot without a verifiable Git SHA, as recorded in M0.

## Verification and remaining work

The required shared network suites now include search (10), durable cookies/cancellation (11), content/login redirects (10), and authentication transactions (8). All 39 must execute on Android Host, JVM and iOS Native. The verifier requires every mandatory suite while allowing additional executed tests; failed/skipped/stale receipts still fail. Existing reader 20, native Keychain, lifecycle/FFI and original Android regressions remain required. Hosted SHA/results are recorded automatically on the same Draft PR; local evidence remains under ignored `build/kmp-work-logs/`.

Actual local results before the feature commit (dirty source; separate hosted acceptance is still required):

- 39 network tests each on Android Host/JVM/real iOS Native, plus the existing 20 reader tests; no failures/errors/skips in the accepted execution.
- 227 verifier tests, affected shared lint and original Android CI task set passed. The latter includes tests, formatting, Release lint, four-ABI Debug/Release builds and benchmark: 417 Gradle tasks, not a test count.
- Actual Kotlin-only device executable and Simulator app built. The owned Simulator passed real Keychain, two app launches, foreground/background, termination/relaunch and original Rust ownership/product regressions; it was deleted.
- Actual macOS account/guest/empty-submit-disabled UI rendered. Desktop keyboard injection remains unverified through the available tool.
- Actual iPad account UI accepted screen-keyboard input, moved focus with Next, masked a synthetic password as `••`, enabled a complete form and cleared fields after Back/reopen. No live login was submitted and no credential snapshot was created. A real username-autocorrection defect was fixed: `ui` now remains `ui` after Next. The owned iPad was deleted.

The first local startup attempt passed Native tests but failed lifecycle verification because `installDist` replaced a verifier JAR while that JVM was running. Its failure log is retained. Repeating the unchanged full check from an independently frozen, hashed tool distribution passed; product code and test requirements were not weakened.

No real-account successful sign-in is inferred from protocol fixtures. Browser verification/handoff, profile/restricted-site initialization, Android browser-session integration and the remaining data/configuration migrations are subsequent work; full F01/M3/P0 is not complete.

Stable version stays `1.15.10 / Android 180074`. PR #15 remains Draft; no tag, Ready transition, merge or production release is authorized.

The preceding reader `4e791db93` completed [all twelve required jobs, gate and dry-run](https://github.com/ZhanZiyuan/EhViewer/actions/runs/41037978710) successfully before this slice is pushed. This authentication commit still requires its own applicable CI; local results are not hosted acceptance.
