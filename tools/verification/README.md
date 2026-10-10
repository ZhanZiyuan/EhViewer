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
tools/verification/build/install/verification/bin/verification prepare-release --tag v1.15.5 \
  --sdk "$ANDROID_HOME" --output release-dry-run/apks --private-dir "$PWD/.private-release" --retention-days 180
tools/verification/build/install/verification/bin/verification device --serial emulator-5560 \
  --sdk "$ANDROID_HOME" --output reports/api26
```

`prepare-release` accepts the actual AGP metadata's three ABI splits plus
universal APK, or exactly one architecture selected with `--abi` for a matrix part. It copies mapping and matching Rust Core/JNI symbols into a
version/commit directory with permissions 0700 and files 0600, records SHA-256
and the retention deadline, and refuses unsafe overwrites. Use a canonical local
path without symlink ancestors. Retention requires the owner's backup and
deletion policy; this tool does not provide durable remote storage or a scheduler.
The `Release` workflow automatically follows successful main push CI and builds
that exact commit. Its `build` matrix contains `arm64-v8a`, `armeabi-v7a`,
`x86_64` and `universal`. `-PreleaseAbi=ABI` builds one APK; universal includes
all three native ABIs. Each part verifies and encrypts its own mapping and native
symbols, so independently linked binaries retain their matching diagnostics.
`combine-release` rejects missing/extra parts, mixed commits/versions, dirty sources
and checksum/signature mismatches before staging exactly four APKs. All four encrypted
diagnostic bundles are retained for 90 days before publication.
Manual runs default to a dry run; checking `publish` requires
main and successful CI for the same commit. A version already published is
skipped, tags pointing elsewhere are rejected, and only the separate publication
job receives write permission. GitHub provides source archives itself.

Production signing reads `EHVIEWER_KEYSTORE`, `EHVIEWER_STORE_PASSWORD`,
`EHVIEWER_KEY_ALIAS` and `EHVIEWER_KEY_PASSWORD`. For local builds, keep the
original keystore at `.private-signing/androidkey.jks` and the three properties
`storePassword`, `keyAlias`, `keyPassword` in `.private-signing/signing.properties`
(directory 0700, files 0600). These files must be backed up separately. Never
replace the production key. Trusted push CI builds restore the original signing key; pull requests and
secret-free manual CI builds use the debug signing key;
`apks --test-signing` accepts this only for SNAPSHOT versions. `-Prelease` fails
without the original external signing material, and final APK verification
requires the historical certificate digest.

Repository secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_STORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Repository variable:
`DIAGNOSTICS_PUBLIC_KEY` (RSA public PEM; never the private key).

```sh
tools/verification/build/install/verification/bin/verification diagnostics-key --output "$PWD/.private-release/keys"
tools/verification/build/install/verification/bin/verification encrypt-diagnostics \
  --private-dir "$PWD/.private-release/VERSION-COMMIT" \
  --public-key "$PWD/.private-release/keys/public.pem" --output diagnostics.enc
tools/verification/build/install/verification/bin/verification decrypt-diagnostics \
  --input diagnostics.enc --private-key "$PWD/.private-release/keys/private.pem" \
  --output "$PWD/.private-release/restored/diagnostics.zip"
```

The diagnostics envelope uses RSA-3072 OAEP (SHA-256 and MGF1-SHA256) and
AES-256-GCM with a random key/nonce and an authenticated header. It contains
mapping, matching native symbols and their version/commit/checksum manifest.
Only ciphertext is retained in Actions for 90 days; plaintext is removed from
the runner. Public repository artifacts are not private storage. Back up the
owner-only private key offline and download encrypted artifacts before expiry.
The decrypt command authenticates the complete envelope before retaining a
0600 plaintext archive in an owner-only directory.

`device` runs all 19 instrumentation tests on an already booted dedicated
local device and captures its API, page size, APK digests and logs. The removed
CI emulator matrix and its AVD provisioning code are no longer maintained here.

Regression archives, expected data and licenses are restored into the ignored
`.local-test-fixtures/` directory from immutable Git baseline
`8fdfb6c5550fab506759a5587b1c8ee4050f507e`. Rust tests restore their own inputs;
Gradle prepares Android test assets automatically. A full Git history is required
(`git fetch --unshallow` for shallow clones). No fixture data is tracked in the
current tree or included in production APKs. Parser inputs stay inline in Rust.
