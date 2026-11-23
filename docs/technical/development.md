# Development and testing

## Set up the toolchain

- Use JDK 25 for Gradle. The Android modules target Java 17 bytecode.
- Install Android SDK Platform 37, Build Tools 37.0.0, the configured NDK, and CMake. See [the Gradle settings](../../settings.gradle.kts) and [CI workflow](../../.github/workflows/ci.yml).
- Install Rust with rustup and use the pinned toolchain and Android targets in [`rust-toolchain.toml`](../../rust-toolchain.toml).
- For native host tests, install the platform development packages for libarchive and libwebp. The CI workflow lists its Linux packages.
- Clone with Git history available. The tests restore archive fixtures from a pinned commit into a local ignored directory.

## Build the app

Run these commands from the repository root.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

The debug APKs are under `app/build/outputs/apk/debug/`. The test APK is under `app/build/outputs/apk/androidTest/debug/`. The debug application uses the `.debug` application ID suffix. Production-signed stable builds require the project's release signing key and follow the release workflow.

## Run host checks

```sh
./gradlew -p tools/verification check installDist
./gradlew :build-logic:convention:check spotlessCheck :app:testDebugUnitTest \
  :core:common:testAndroidHostTest :core:data:testAndroidHostTest \
  :core:i18n:testAndroidHostTest :core:ui:testAndroidHostTest \
  :app:lintRelease :app:assembleRelease :benchmark:assembleNonMinifiedRelease
```

Run Rust checks from `native/rust/`.

```sh
cargo fmt --all -- --check
cargo test --locked -p ehviewer_core
cargo test --locked --features jvm --lib
for target in aarch64-linux-android thumbv7neon-linux-androideabi x86_64-linux-android; do
  cargo clippy --locked --workspace --target "$target" --all-features -- -D warnings || exit 1
done
```

The two opt-in gallery parser cases contact the public gallery service. Use `cargo test --locked -p ehviewer_core -- --include-ignored` when you also want to run those checks.

## Run device checks

Start an emulator or attach a test device, then use the serial reported by `adb devices`. The verifier installs the debug and instrumentation APKs and records the API level, device page size, APK hashes, and test results.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
tools/verification/build/install/verification/bin/verification device \
  --serial DEVICE_SERIAL --sdk "$ANDROID_HOME" --output /tmp/ehviewer-device
```

Use [the verification guide](../../tools/verification/README.md) for the APK, ABI, signature, 16 KiB alignment, and release-input checks. For UI changes, include both compact and tablet windows. Keep generated APKs, fixtures, reports, and signing material out of commits.
