# Architecture

EhViewer is a GPL-licensed Android client for browsing, reading, and downloading galleries from supported E-Hentai sites. The Android app owns platform behavior. Shared Kotlin modules hold application models, persistence contracts, resources, and Compose components. Native archive and related media operations run behind JNI.

## Technology stack

| Layer | Technologies |
| --- | --- |
| App and UI | Kotlin, Jetpack Compose, Material 3, Compose Destinations, and AndroidX adaptive layout |
| Asynchronous work | Kotlin coroutines and Flow |
| Networking and images | Ktor with Cronet or OkHttp, Android WebView for interactive sign-in, and Coil for image loading |
| Storage | Room for gallery-related records, DataStore preferences for settings, and Android document access for user-selected folders |
| Native operations | Rust Core and JNI, CMake with the NDK, and libarchive through the native dependency build |
| Build and quality checks | Gradle, Android Gradle Plugin, KSP, Spotless, AndroidX instrumentation, and GitHub Actions |

## Repository layout

| Path | Responsibility |
| --- | --- |
| [`app/`](../../app) | Android entry points, navigation, screens, the reader, networking integration, the download service, and app tests |
| [`core/common/`](../../core/common) | Shared Kotlin utilities and foundation code |
| [`core/data/`](../../core/data) | Data models, Room schema and migrations, and platform-backed preference and cookie components |
| [`core/i18n/`](../../core/i18n) | Localized strings and other language resources |
| [`core/ui/`](../../core/ui) | Shared Compose components and adaptive UI helpers |
| [`native/rust/`](../../native/rust) | Rust workspace, parsers and archive logic in Core, JNI adapters, and native tests |
| [`native/third-party/`](../../native/third-party) | CMake and pinned native dependency integration |
| [`build-logic/`](../../build-logic) | Gradle build conventions |
| [`benchmark/`](../../benchmark) | Android baseline profile and benchmark modules |
| [`tools/verification/`](../../tools/verification) | Independent JVM tools for build, APK, release-input, and emulator checks |
| [`.github/workflows/`](../../.github/workflows) | CI, snapshot builds, release publication, and baseline profile automation |

## Session handling

The WebView and HTTP clients share cookie storage through `EhCookieStore`. Form sign-in stages cookies until the forum confirms success. Interactive sign-in confirms the forum session in the WebView that received the cookies, then persists the signed-in state. Profile and site configuration refresh separately after sign-in.

The app tracks forum identity separately from gallery-site accessibility. Read-only probes can distinguish an access denial, an authentication requirement, a browser challenge, and a transport error. A marked read-only GET can retry a supported connection failure once. Profile refresh, site preferences, and explicit sign-out have separate responsibilities.

See [development and testing](development.md), the [native workspace guide](../../native/rust/README.md), and the [verification tools](../../tools/verification/README.md).
