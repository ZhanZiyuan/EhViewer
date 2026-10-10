# Android verification tools

This independent Kotlin/JVM build contains build and regression tools, not another
application platform. Run it with the repository Gradle Wrapper and JDK 25; its
bytecode target is Java 17. It uses Gradle's bundled Kotlin DSL and Groovy JSON
library. No Python installation or additional shell helper is required.

```sh
./gradlew -p tools/verification check installDist
tools/verification/build/install/verification/bin/verification architecture
tools/verification/build/install/verification/bin/verification native-sources
tools/verification/build/install/verification/bin/verification bytecode
tools/verification/build/install/verification/bin/verification apks app/build/outputs/apk/release --sdk "$ANDROID_HOME"
tools/verification/build/install/verification/bin/verification diagnostics --sdk "$ANDROID_HOME"
tools/verification/build/install/verification/bin/verification release-input-tests --sdk "$ANDROID_HOME"
```

Build Debug, androidTest, Release and benchmark outputs before the artifact
checks. `apks` also accepts `--debug --application-id moe.tarsin.ehviewer.debug`
and an optional `--version-code`. It verifies actual manifests, signatures,
certificate continuity, ABI/JNI exports, ELF LOAD alignment and ZIP alignment.
The release integration tests reject mismatched versions, missing/extra APKs and
a genuinely tampered signature without creating a public payload.

```sh
tools/verification/build/install/verification/bin/verification prepare-release --tag v1.15.2 \
  --sdk "$ANDROID_HOME" --output release-dry-run/apks --private-dir "$PWD/.private-release" --retention-days 180
tools/verification/build/install/verification/bin/verification device --serial emulator-5560 \
  --sdk "$ANDROID_HOME" --output reports/api26
```

`prepare-release` accepts only the actual AGP metadata's three ABI splits plus
universal APK. It copies mapping and matching Rust Core/JNI symbols into a
version/commit directory with permissions 0700 and files 0600, records SHA-256
and the retention deadline, and refuses unsafe overwrites. Use a canonical local
path without symlink ancestors. Retention requires the owner's backup and
deletion policy; this tool does not provide durable remote storage or a scheduler.
Formal publication remains disabled. GitHub provides source archives itself.

`device` tests an already booted dedicated device. `emulator` installs the SDK
image from `TEST_IMAGE`, creates its own AVD on port 5554, verifies the API from
`TEST_LABEL` and 16 KB pages when requested, runs all 19 instrumentation tests,
captures logs, and shuts down its AVD. For manual emulator runs, supply the Debug
and androidTest APKs in `device-inputs/`. The emulator matrix is not part of CI.

Migration-only C comparison drivers, raw logs, reports and duplicate outputs
are kept outside the source checkout. Maintained Rust tests and Android tests
share `native/test-fixtures/`; parser tests use minimal inline Rust inputs.
