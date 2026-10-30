# Native browser sign-in and shared session handoff

This M3 slice adds an actual iOS WKWebView login surface, written in Kotlin/Native and embedded in the same CMP Account flow. The shared Back, confirmation, progress and error controls are Kotlin/CMP. Desktop and Android browser adapters remain separate unfinished work; they do not display an unimplemented entry point.

The browser uses a new nonpersistent WebKit data store, the same configured User-Agent as the Ktor session, normal system TLS and the official forum login page. Main-frame navigation is limited to the three official HTTPS service hosts on port 443; external schemes, foreign destinations and popup windows are rejected. Cloudflare's HTTPS challenge subframe can load. No custom JavaScript, Swift, certificate override, browser password persistence or automated CAPTCHA solver is introduced.

Only the user's **Use this session** action reads the real `WKHTTPCookieStore`, including HttpOnly metadata. The shared handoff preserves value, domain/host-only scope, path, Secure, HttpOnly and expiration, bounds records/bytes, excludes unrelated domains and requires a complete unexpired identity for the root gallery request. Missing, expired, malformed or wrong-path cookies leave the previous durable snapshot intact. A generation captured before opening the browser prevents a late result from undoing logout or replacing a newer login. The actual OS vault receives one atomic successful commit; closing the browser discards its isolated state.

This establishes local credential presence, not proof that a server has not revoked it. Subsequent real gallery requests retain the shared authentication/challenge error handling. No successful real-account login or CAPTCHA completion is claimed without that execution.

## Reference and licensing

| Read-only reference / primary API | Target | Decision, difference and license treatment |
|---|---|---|
| Animeko `app/shared/app-data/src/commonMain/kotlin/domain/session/SessionStateProvider.kt` | `core/network/.../session/BrowserSignIn.kt` | Adopt explicit session ownership and invalidation boundaries. Use EhViewer cookie identity and an atomic generation-checked transaction, without Animeko token/OAuth state types. Architecture only; no AGPL code copied or mechanically rewritten. |
| Animeko `app/shared/application/src/commonMain/kotlin/platform/CommonKoinModule.kt`, web-session wiring around lines 223–243 | `GallerySession.kt`, common CMP browser screen and iOS adapter | Adopt a browser/network identity boundary. Retain EhViewer constructors/User-Agent and native cookie APIs; reject the DI graph, page evaluator, recognizers and automated solvers. No scripts, dependencies or implementation copied. |
| [CMP UIKit interoperability](https://kotlinlang.org/docs/multiplatform/compose-uikit-integration.html), pinned CMP platform metadata | `app/shared/src/iosMain/.../AppleGalleryLoginBrowser.kt` | Independently implement the native view in Kotlin using the current `viewinterop.UIKitView` API and native accessibility. Existing pinned CMP versions remain. |
| Apple [cookie store](https://developer.apple.com/documentation/webkit/wkhttpcookiestore/getallcookies(_:)) and [nonpersistent store](https://developer.apple.com/documentation/webkit/wkwebsitedatastore/nonpersistent()) | Real WebKit cookie extraction and isolated lifetime | Use OS framework APIs, including HttpOnly extraction; no vendored browser implementation or new third-party dependency. Actual compiler/platform metadata and runtime execution determine acceptance. |

Animeko remains an unmodified local snapshot without a verifiable Git SHA, as recorded in M0. This change does not alter production signing, release workflow, Rust ABI or stable version `1.15.10 / Android 180074`.

## Verification

The shared network gate requires all five suites and at least **46** executed tests: existing search 10, persistent cookies 11, content 10, form authentication 8 and browser handoff 7. Browser tests cover exact metadata/value transfer, one commit, host-only/path/expiry isolation, malformed/oversized input, incomplete retry, cancellation/store failure, logout/new-login races and navigation restrictions. Actual Android Host/JVM execution passed with no failure/error/skip. Device and Simulator Kotlin compilation, affected lint and the existing 20 reader tests passed locally.

The explicit iOS startup regression additionally creates actual isolated WebKit stores, parses/sets/reads synthetic HttpOnly cookies through native APIs, commits through the real Keychain-backed vault, reopens it and verifies independent-store isolation. Both cold launches must report four checks; the independent staging consumer rechecks each actual PID's stderr as well as the source-bound receipt. Synthetic fixture credentials are confined to the explicit regression's dedicated vault, cleared afterwards; normal startup does not seed any identity.

Actual local Native execution passed: network **46**, reader **20**, original real Keychain **3**, both cold-launch browser transfers **4 each**, two original **2,000-result** FFI checks with zero live handles, real gallery/image checks, background/foreground and termination/relaunch. The owned startup Simulator was deleted. Original Android CI tasks passed, including Release lint, four-ABI builds and benchmark: **361 Gradle tasks / 8m 41s**, not a test count. Verifier **227** passed; actual seven AAR/four JAR, four APK/native diagnostics and packaged macOS outputs passed byte/architecture checks and remain under ignored `build/kmp-work-logs/`.

Actual iPhone and iPad loaded the official HTTPS forum inside WebKit and displayed its Cloudflare verification page. Shared controls fit the narrow phone and both tablet orientations. Confirming without identity showed the incomplete-login error; Back returned to the guest Account screen, and neither manual app container contained a production credential snapshot. The manual Simulators were deleted and their absence checked. Actual macOS still rendered the existing common form, with no unimplemented browser entry. No live account credentials were submitted and the website verification was not completed.

This commit still requires its own hosted CI. Real-account success, CAPTCHA completion, signed physical devices and complete F01/M3 remain unverified. A loaded website challenge is not successful account authentication.

The preceding authentication `2ae640984` passed [all twelve hosted jobs, gate and dry-run](https://github.com/ZhanZiyuan/EhViewer/actions/runs/41046191867). All twelve PR checks were independently read back as SUCCESS on that exact Draft PR head before this browser slice was pushed. This is prior-commit acceptance, not a substitute for the new SHA's checks.

The accompanying check-plan change removes only iOS's unrelated desktop scheduling dependencies. This CI-policy/public-API commit still selects Full, with every platform, strict gate and package dry-run required. The workflow does not alter the production release/signing contract. See [CI planning](ci-plan.md).
