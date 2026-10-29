# Platform verification tools

M3 common JVM: `common-jvm-probe --jar PATH` loads actual packaged filesystem APIs and performs eight real disk/Unicode/error/closure/cancellation scenarios in an owned temporary directory. This local probe does not establish clean-source provenance. `common-jvm --input SHARED_ARTIFACT_DIRECTORY --output FRESH_RECEIPT_FILE` additionally requires clean same-source shared schema4, complete compiler/JAR bytes and actual eight-test XML before execution. Five existing native JVM consumers retain those receipts; stage schema5 requires them and reexecutes the actual common JAR. Android document-provider source stays unchanged and Native common is not enabled. See [ADR0027](../../docs/kmp/adr/0027-common-jvm-filesystem.md) and [actual common JVM acceptance](../../docs/kmp/common-jvm-filesystem.md).

M3 gallery models: shared schema3 and staged schema4 compare the complete actual JVM class inventory, require all15 model types and reparse both real eight-test Android/JVM XML sets. Native schema2 requires all nine model metadata fragments, public model/serializer APIs, the exact existing serialization dependency set and all eight actual Native tests. The five model tests preserve pre-move Android CBOR bytes. See [ADR0026](../../docs/kmp/adr/0026-portable-gallery-models.md); direct product integration and full P0 remain incomplete.

M3 Native domain: `verification ios-domain --output NEW_DIRECTORY` runs on macOS ARM64 after the pinned Native toolchain is available. It owns a new Simulator, forces both production KLIB compiles and all common Native tests, stages the just-linked identical executable outside protected source directories, checks fresh compiler/XML/API/Mach-O bytes, and cleans up. `verification ios-domain-artifact --input RETAINED_DIRECTORY` independently checks those actual uploaded bytes and test receipts on any host; it reports Native execution here as NOT_EXECUTED. CI requires this separate artifact in the current-source package gate. See [ADR0025](../../docs/kmp/adr/0025-ios-domain-native.md). Device execution, direct product integration, minimum systems and full P0 remain unverified.

M3 first slice: `verification shared-core --output NEW_DIRECTORY` checks five actual Android AARs and the portable domain JVM JAR against their compiler outputs and real test XML. It loads that exact JAR, exercises seven production title cases and verifies the four existing ArchiveCatalog methods. Stage aggregation repeats the actual JAR probe; iOS domain and direct desktop product integration remain NOT_INTEGRATED. See [domain migration](../../docs/kmp/domain-core-migration.md).

`verification staged-package-dry-run --repository ZhanZiyuan/EhViewer --run-id ID --android-run-id ID --sdk SDK --output NEW_DIRECTORY` fetches authenticated real CI artifacts and validates six current-stage candidate packages plus source-bound original native receipts. `staged-package-check --input DIRECTORY --sdk SDK --output NEW_DIRECTORY` uses previously authenticated context/archives offline. Both retain actual source/version/size/SHA, five unintegrated final targets and false P0/all-eleven/publish acceptance; neither emits AppImage/IPA stand-ins or executes other native OSes. See [staged package dry run](../../docs/kmp/staged-package-dry-run.md).

`verification artifact-archive --catalog FILE --archive FILE --artifact-id ID --run-id ID --head-sha SHA --output NEW_DIRECTORY` verifies an actual GitHub artifact archive against its complete official catalog before safe byte extraction. It binds run/head identity, official ZIP size/SHA, canonical paths, CRC and every extracted file's read-back SHA; existing user data is never overwritten. This component reports transport inspection only, with package validation/native execution NOT_EXECUTED and executable permissions not preserved. It does not complete staged package aggregation. See [artifact archive intake](../../docs/kmp/artifact-archive-intake.md).

`verification ios-log-evidence --input FILE --first-pid PID --second-pid PID` only validates a retained unified-log trace for both actual process lifecycles and Rust ownership results. It does not launch an app, create startup.json or substitute for the mandatory real `ios-startup` job. The owned time-window collection and unchanged deadlines are documented in ADR0016.

`verification source-policy` audits cumulative source changes and untracked files against the fixed M0 baseline, rejects forbidden implementation/link/reference boundaries and checks retained third-party origin bytes. It needs full target Git history, never a local Animeko checkout. Its explicit scope and limitations are documented in [source-policy.md](../../docs/kmp/source-policy.md); full license/copy/SBOM review is not claimed.

`verification migration-phase` validates the reviewed M3 job/dependency list, frozen metadata and module-specific targets. Only domain gains Android/JVM support; common/data/i18n/ui retain their Android boundary. Its source-bound JSON lists all eleven product targets, six staged candidates and five outstanding final packages; declarations cannot mark M3, product P0 or final delivery accepted. See [migration-phase.md](../../docs/kmp/migration-phase.md).

This independent Kotlin/JVM build contains build and regression tools, not another
application platform. Run it with the repository Gradle Wrapper and JDK 25; its
bytecode target is Java 17. It uses Gradle's bundled Kotlin DSL and Groovy JSON
library. No Python installation or additional shell helper is required.

The M1 `ios-startup --app PATH --output NEW_DIRECTORY` command checks a real
Kotlin-only Simulator app and its cold-start/background/foreground lifecycle in
an owned temporary device. See [KMP build and run](../../docs/kmp/build-and-run.md)
for prerequisites, scope and evidence. It never substitutes for signed-device tests.

```sh
./gradlew -p tools/verification check installDist
tools/verification/build/install/verification/bin/verification architecture
tools/verification/build/install/verification/bin/verification release-contract
tools/verification/build/install/verification/bin/verification migration-phase
tools/verification/build/install/verification/bin/verification shared-core --output reports/shared-core
tools/verification/build/install/verification/bin/verification windows-stage-zip --runtime-report reports/desktop-windows-arm64/windows-arm64-runtime.json --image reports/desktop-windows-arm64/image/EhViewer --output reports/windows-arm64-package
tools/verification/build/install/verification/bin/verification stage-zip-extract --package-report reports/windows-arm64-package/windows-arm64-package.json --output reports/windows-zip-byte-extraction
tools/verification/build/install/verification/bin/verification windows-zip-launch --package-report reports/windows-arm64-package/windows-arm64-package.json --runtime-report reports/desktop-windows-arm64/windows-arm64-runtime.json --output reports/windows-arm64-zip-launch
tools/verification/build/install/verification/bin/verification native-sources
tools/verification/build/install/verification/bin/verification bytecode
tools/verification/build/install/verification/bin/verification apks app/build/outputs/apk/release --sdk "$ANDROID_HOME"
tools/verification/build/install/verification/bin/verification diagnostics --sdk "$ANDROID_HOME"
tools/verification/build/install/verification/bin/verification release-input-tests --sdk "$ANDROID_HOME"
```

`release-contract` emits the source-bound eleven-target delivery plan without production keys or publication. It explicitly reports `CONTRACT_ONLY` and `artifacts_verified=false`; it is not a successful eleven-package dry-run. An optional `--tag` must match the authoritative source version. Exact runners, names, signing boundaries and unimplemented package checks are documented in [the delivery contract](../../docs/kmp/release-artifacts.md).

Build Debug, androidTest, Release and benchmark outputs before the artifact
checks. `apks` also accepts `--debug --application-id moe.tarsin.ehviewer.debug`
and an optional `--version-code`. It verifies actual manifests, signatures,
certificate continuity, ABI/JNI exports, ELF LOAD alignment and ZIP alignment.
The release integration tests reject mismatched versions, missing/extra APKs and
a genuinely tampered signature without creating a public payload.

`check` also executes the actual Release publication script against a stateful
Kotlin GitHub CLI mock. These offline tests require Bash and jq, cover
first publication, draft retries, lightweight/annotated tags, creation races and
API failures, and never contact GitHub or publish assets.

```sh
tools/verification/build/install/verification/bin/verification prepare-release --tag v1.15.10 \
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
Manual Release runs are main-only and default to a dry run; checking `publish` requires
main and successful CI for the same commit. Feature branches use the secret-free
CI snapshot path, not the production Release workflow. A version already published is
skipped, tags pointing elsewhere are rejected, and only the separate publication
job receives write permission. GitHub provides source archives itself.
Publication explicitly creates a missing tag at the verified source commit,
reuses a matching draft, rejects duplicate drafts and conflicting tags, and
checks the tag again after uploading the four APKs. Existing tags are never moved.

Production signing reads `EHVIEWER_KEYSTORE`, `EHVIEWER_STORE_PASSWORD`,
`EHVIEWER_KEY_ALIAS` and `EHVIEWER_KEY_PASSWORD`. For local builds, keep the
original keystore at `.private-signing/androidkey.jks` and the three properties
`storePassword`, `keyAlias`, `keyPassword` in `.private-signing/signing.properties`
(directory 0700, files 0600). These files must be backed up separately. Never
replace the production key. Main push and main manual CI builds restore the original signing key;
feature push, pull requests and other manual CI builds use the debug signing key;
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

`device` runs all discovered instrumentation tests on an already booted dedicated
local device and captures its API, page size, APK digests and logs. The removed
CI emulator matrix and its AVD provisioning code are no longer maintained here.

The host JNI performance workload is reproducible with JDK 25 and the same
client for original C and Rust libraries. Fixtures are generated locally:

```sh
tools/verification/build/install/verification/bin/verification native-benchmark-inputs --output /private/tmp/ehviewer-benchmark
JAVA_OPTS='--enable-native-access=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED' \
  tools/verification/build/install/verification/bin/verification native-benchmark \
  --library /absolute/path/to/libehviewer_rust.dylib --archive /private/tmp/ehviewer-benchmark/large-stored.zip \
  --warmup 50 --loops 300
```

Add `--samples` to retain all measured operation durations in original order and
report JVM GC count/time during the measured interval. Collection and serialization
occur outside operation timers. Use `--warmup 50 --loops 300 --retained 0` and
`--retained 64` for the existing comparison protocol, interleave independent JVM
trials, and stop build/emulator work before collecting performance results.
GC counters cannot attribute native tails or explain an earlier run that did not
record them; process-wide RSS/fault counts also include JVM startup.
`--mutate` adds a deterministic byte write to each extracted page as a separate
dirty-view stress workload. Keep it disabled for the original comparison and do
not compare its results to read-only trials. The original C implementation does
not provide the Rust implementation's independent writable-buffer guarantees.
`--cpu-samples` adds per-operation current-thread CPU durations and start offsets
for a separate diagnostic run (and implies `--samples`). It requires supported,
enabled JVM thread CPU timing. Combine it with JDK safepoint logging when
investigating wall-clock outliers. Do not mix instrumented CPU trials into the
original latency comparison.
For zero-warmup trials, use phase samples and JIT logs to distinguish native
extraction from Java page-touch compilation. Faster extraction can change which
iterations run before the touch loop is optimized, so an end-to-end first-use
P95 regression alone does not identify a native regression. Keep the original
unwarmed results; do not hide the tail by adding production warmup or delays.
The page-touch loop and timed extract/touch/release method are separate from
optional statistics, so adding diagnostic output does not enlarge their JIT
compilation bodies. Capture GC counters before allocating output sample lists.
Historical measurements still require caution: a fixed 50-operation warmup is
not proof that JIT compilation, CPU scheduling or the machine has stabilized.
For microsecond Stored comparisons, inspect JIT compilation separately and use a
calibrated steady-state cohort (50,000 warmup operations and 10,000 measured
operations were used for the v1.15.10 single-thread target). Keep shorter,
first-use and diagnostic cohorts separately labeled; do not silently replace or
combine their results.

Repeat independent JVM trials for both ZIP inputs, alternating library order.
`--retained N` measures extraction with other page buffers still outstanding.
The workload touches each 4-KiB page and releases it; it does not measure Android
rendering, real-device latency or long-session memory. Keep libraries, generated
inputs and raw results outside Git.

The same benchmark supports `--readonly` for the new internal reader API (use the
original writable method for historical C and unmodified Rust). `--threads 1..8`
creates independent platform workers with private volatile touch counters;
`--order forward|reverse|random|repeat|legacy` controls navigation with a fixed seed.
All workers finish warmup before measurement starts. Samples are boxed only after
every worker stops and GC counters are captured. Reports include P50/P95/P99,
throughput, open/held-release/close timings and optional original-order samples.
Repeat all three implementations with the same newly compiled client; older
measurements from an earlier harness are not interchangeable with a fresh run.

```sh
tools/verification/build/install/verification/bin/verification native-benchmark-inputs \
  --output /private/tmp/ehviewer-pages --page-bytes 4194304 --pages 32 --pattern mixed
JAVA_OPTS='--enable-native-access=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED' \
  tools/verification/build/install/verification/bin/verification native-benchmark \
  --library /absolute/path/to/libehviewer_rust.dylib \
  --archive /private/tmp/ehviewer-pages/large-stored.zip --readonly \
  --warmup 3000 --loops 2000 --retained 64 --threads 4 --order random --samples
```

Input generation also accepts 64-KiB/256-KiB/1/4/8/16-MiB pages. `constant` retains
the original synthetic compressible payload; `mixed` uses seeded random bytes to
represent already compressed image data. Neither is an Android rendering benchmark.
Do not request retained-page combinations exceeding the unchanged 128-buffer/512-MiB
JNI limits. A fresh JVM with `--warmup 0` measures first use of a new mapping and
unwarmed code, **not a guaranteed cold OS disk cache**.

`--phase-samples` records extraction/JNI, touch and release separately; combine it
with `--cpu-samples` and JVM safepoint logs for diagnostic trials only. `--pid-file`
writes the actual JVM PID for stack sampling. `--pause-ms 12000` pauses after the
measurement while the session/retained leases remain alive for `vmmap`/`footprint`
inspection. Instrumented/paused trials are marked and excluded from acceptance
latency cohorts. Capture process CPU/wall/RSS/page faults separately (for example,
`/usr/bin/time -l` on macOS); whole-JVM RSS includes clean file mappings, JIT and GC.
Report clean mapped bytes and private/physical footprint separately when explaining
RSS changes; they do not make an exceeded RSS target a passing result.

The host clients below use the real JNI ABI, retaining fixture data locally:

```sh
JAVA_OPTS='--enable-native-access=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED' \
  tools/verification/build/install/verification/bin/verification native-differential \
  --library /absolute/path/to/libehviewer_rust.dylib --fixtures .local-test-fixtures
JAVA_OPTS='--enable-native-access=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED' \
  tools/verification/build/install/verification/bin/verification native-regression \
  --library /absolute/path/to/libehviewer_rust.dylib --fixtures .local-test-fixtures
```

`native-differential` emits ordered extensions, sizes and SHA-256 for 13 formats and
checks repeated/backward access using the unchanged C ABI. Compare its JSON across
the historical C, frozen Rust and optimized Rust libraries; add `--readonly` on the
optimized library to verify the new reader path against the same oracle.
`native-regression` is
Rust-specific: concurrent first-use method caching, true read-only slices/duplicates,
native write rejection, writable isolation, close survival, stale/double release and
shared-address registration limits. It never runs the legacy C lifetime hazards.
Add `--large-archive /path/to/16MiB-pages/large-stored.zip` to verify the independent
512-MiB byte budget with 32 shared 16-MiB views, below the 128-object count limit.

Regression archives, expected data and licenses are restored into the ignored
`.local-test-fixtures/` directory from immutable Git baseline
`8fdfb6c5550fab506759a5587b1c8ee4050f507e`. Rust tests restore their own inputs;
Gradle prepares Android test assets automatically. A full Git history is required
(`git fetch --unshallow` for shallow clones). No fixture data is tracked in the
current tree or included in production APKs. Parser inputs stay inline in Rust.

## Push-based CI snapshots

CI snapshots are built on pushes, PR checks and manual CI runs. There is no daily
APK schedule. Only successful main push runs are offered by the application's
CI snapshot update channel; PRs, forks, other branches and manual checks are
excluded from discovery. The independent Baseline Profile schedule is unchanged.

CI calls assembleRelease without -Prelease: the APK is optimized as Release,
with versionName x.y.z-SNAPSHOT and SNAPSHOT=true. Four Actions APK artifacts
are named snapshot-COMMIT-ABI, each containing exactly one file:

- EhViewer-x.y.z-arm64-v8a.apk
- EhViewer-x.y.z-armeabi-v7a.apk
- EhViewer-x.y.z-universal.apk
- EhViewer-x.y.z-x86_64.apk

The snapshot-COMMIT-ARTIFACT_IDS artifact contains the completion manifest and
binds the exact diagnostic/ARM64/ARM32/universal/x86_64 artifact IDs. Partial
overwrites on retries invalidate the old marker; mixed attempts are not reused. Trusted production-signed
snapshots retain matching mapping/native symbols in an encrypted
snapshot-diagnostics-COMMIT artifact for 90 days. APKs and their marker are also
retained for 90 days. No plaintext diagnostic bundle is uploaded. Non-main
checks may use test signing and are not application update sources.

Before expensive work, main CI searches paginated successful main push runs for
the exact full source SHA and all four nonempty, unexpired APK artifacts, the
completion marker and encrypted diagnostics. A complete existing snapshot skips
validation, build and upload. A rerun also checks its completed prior attempts.
Missing, expired or incomplete outputs are rebuilt; API failures fail closed.
New source commits always require fresh CI.

The application uses public Actions metadata and commit comparison. It selects
a complete trusted snapshot, offers only an ahead commit, and opens the matching
ABI artifact (or universal) in the browser. GitHub sign-in is required to download
Actions artifacts; unzip the artifact and install its APK. There is no app token
entry or embedded credential. The saved ci_update_channel preference is preserved.
Stable updates continue to download/install public release APKs in the app.

A successful main push still triggers the stable Release workflow. A new stable
project version is built with -Prelease and published using the existing four-ABI
matrix and guarded publisher. An already published stable version is skipped.
Manual Release remains a dry run by default, with successful trusted main CI required
for publication. No snapshot tag or nightly GitHub Release is created. Existing
historical nightly Releases are left intact.

Existing 1.15.9 clients still query prerelease assets for CI updates. Install the
first new trusted main snapshot manually from Actions, or upgrade through a
future stable version containing this updater. The project version is not bumped
by this workflow change. Snapshot discovery queries recent successful main push
runs and inspects at most 10 trusted runs per check to bound unauthenticated API
use, then applies the existing GitHub API rate limits. Older retained artifacts
outside that lookback remain downloadable manually from Actions.
