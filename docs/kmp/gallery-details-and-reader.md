# Shared gallery details and online page reader

This product slice extends the existing shared search/session flow with real gallery details, image-page documents and an online paged reader. Desktop and iOS use the same Compose Multiplatform screens and KMP repository. It is part of M3–M5 work, **not completion of M3 or the full reader P0**. Android builds the same libraries; its existing navigation, browser login and complete reader remain in place until their integration is independently verified.

## Behavior and implementation

- Search rows open shared details. Returning preserves search results and list position; returning from the reader preserves loaded details.
- Details use EhViewer's existing Rust metadata, tags and preview parser and original CBOR models. The repository checks gallery identity and preview positions. Preview-page requests use the actual `p` parameter.
- Reader pages use a new bounded Rust adapter for the actual image, next/previous page links and reload/original-image keys. JNI and generated C ABI adapters decode the same result. Existing JNI, C ABI widths/statuses and owned-buffer operations remain intact.
- The shared reader loads actual bytes, shows recoverable loading/error/retry states (manual image retry uses the parsed `nl` key to request another H@H server), moves forward/backward through the gallery, supports arrow/PageUp/PageDown keys, double-tap zoom, pinch/drag and reset zoom. Platform bitmap decoding is Kotlin: Android BitmapFactory; the same Skia source compiles on Desktop and iOS.
- Requests share the real cookie/session client. Redirects are bounded and validated **before** contacting the destination. Login cookies are not sent to image CDNs. HTML is limited to 8 MiB, encoded images to 32 MiB, and decoded images to 16 megapixels with checked dimensions before pixel allocation. Invalid responses and cancellation preserve the existing redacted error contract.

Search/metadata HTML, image-page HTML, CDN traffic and decoder fixtures are distinct. Normal browsing never uses fixtures or requires a regression flag. Explicit offline packaged-app regression modes use an owned two-page HTML fixture and a generated two-pixel PNG to verify real Rust → CBOR → Kotlin types and actual pixel colors, without requiring a third-party site in CI. All original startup/archive/ownership regressions remain mandatory.

## Reference decisions

The local Animeko directory remains read-only. Its Git identity is still unverifiable because the provided tree is a snapshot, as recorded in M0.

| Read-only reference path | EhViewer target | Decision and differences | License handling |
|---|---|---|---|
| `app/shared/app-platform/src/commonMain/kotlin/navigation/AniBackStack.kt` | `app/shared/.../GallerySearchScreen.kt`, `GalleryDetailScreen.kt` | Adopt the concept of retaining state across routes. Use a small gallery/detail/reader composition for this slice; no Animeko route types, serialized stack or navigation implementation is reused. | Architecture reference only; no AGPL code copied or rewritten. |
| `app/shared/ui-subject/src/commonMain/kotlin/ui/subject/details/state/SubjectDetailsStateLoader.kt` | Shared detail/reader loading effects and `core/network/.../GalleryContentRepository.kt` | Adopt cancellation and explicit retry as product behavior. Keep EhViewer gallery identities, Rust parsers and bounded image requests; reject subject/episode/video models and their dependency graph. | Architecture reference only; implementation independently written in Kotlin/Rust. |
| EhViewer's own `native/rust/core/src/parser/detail.rs` and Android `GalleryPageParser.kt` | `native/rust/core/src/parser/gallery.rs`, portable document adapters | Reuse the existing gallery model/parser; independently port page-source extraction into Rust, including HTML entity handling and navigation. | Existing EhViewer licenses and original notices retained. |

The existing CMP/Skiko versions are retained; no dependency version upgrade or Animeko dependency set is introduced. Bitmap ownership was checked against the pinned Compose implementation. Relevant primary API references: [Skiko Codec](https://github.com/JetBrains/skiko/blob/master/skiko/src/commonMain/kotlin/org/jetbrains/skia/Codec.kt), [CMP images](https://kotlinlang.org/docs/multiplatform/compose-desktop-images.html).

## Verification and limits

Local evidence is retained under ignored `build/kmp-work-logs/`; it explicitly records the dirty working tree until the feature commit exists. Per-commit hosted results are recorded automatically by Actions Summary on the same Draft PR #15.

| Actual local check | Result |
|---|---|
| Shared network/session/content contracts | 28 tests each on Android Host, JVM and real iOS Native; no failures/skips |
| Shared decoded-dimension safeguards | 2 tests each on Android Host and JVM |
| Rust Core / archive / C ABI / JNI | 24 / 15 / 11 / 5 passed; only the 3 pre-existing online Core ignores remain |
| Production Desktop JNI, model and Skia pixel regression | 3 checks passed with the newly built ARM64 library |
| Kotlin-only iOS device executable and Simulator app | Actual builds passed; device execution remains unverified |
| Owned iOS Simulator | Keychain tests, 28 network tests, two launches with 3 real product checks each, background/foreground and explicit termination/relaunch passed; owned device deleted |
| Actual macOS product | Real public list → 89-page gallery metadata → image 1 → image 2 → reverse navigation → detail → retained search list passed through the packaged app |
| Android regression | Original tests, lint, Debug/Release four-ABI APKs and benchmark plus affected shared tests/lint passed (446 Gradle tasks; this is not a test count) |
| Actual iPad mini Simulator product | Same real list, gallery details, image 1/image 2 and return to retained detail passed; explicit landscape rendering and rotation retaining the selected gallery also passed |

Hosted acceptance and the remaining input/advanced reader checks are still being completed for this slice; their results must not be inferred from these rows.

Remaining work includes Android shared navigation/session integration, authenticated browser handoff, shared persistence/settings and migrations; detail thumbnails/favorites/actions and preview browsing; continuous/RTL reading, saved progress, prefetch/cache, animated images and complete input/accessibility/performance acceptance; downloads, archive integration and the remaining release targets. Desktop text entry was not verified through the available automation. No signed device, full five-platform P0 or eleven-asset acceptance is claimed.

The application remains `1.15.10 / Android 180074`. Production `releases.yml`, signing and publication protections are unchanged. The final `1.16.0` freeze remains an independent M8 commit after the required acceptance.
