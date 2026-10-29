# Actual shared-core checks for the current stage

Accepted at `b6e4d3ec687d91531c866b6e67abe4bb4cc5094e` after both complete eleven-job workflows ([Push](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40805794104) / [PR](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40805800099)), their gates and all actual artifacts passed. Each shared job executed all 97 tasks; all eight downloaded AARs, packaged Java-17 classes/resources and actual host XMLs were independently verified against their own source-bound reports. Generated MR content hashes differ between events and are not assumed reproducible. M2 remains incomplete; this accepts the bounded library slice only.

`ci/shared-kmp` is a required Ubuntu x64 job. It compiles the four currently integrated Android core libraries, generates their real AARs/resources and executes their available Android host tests with `--rerun-tasks`. It does not reuse a prior task result as a new test execution. The original full Android regression job remains intact. The always-run aggregate gate accepts only the actual success of this additional job; failure, cancellation, skipped, neutral, pending and missing results remain fatal.

The Kotlin `verification shared-core --output NEW_DIRECTORY` command binds the actual Git SHA/dirty state, retained architecture checks and authoritative app version to inspected compiler outputs, AARs and host JUnit receipts. Dirty CI trees fail. The current source still has only the Android core target; Desktop/iOS shared-core targets are not enabled by this check. M3 must migrate and test those targets independently.

The verifier requires each real AAR and matches its complete packaged class set and every class byte against the real compilation directory. Every class must be Java 17, and each library namespace must match its module. It reads the actual manifest and localization XML with external entities/DTDs disabled, rejects noncanonical/duplicate or oversized archive entries, and requires packaged base localization resources in i18n. Missing AARs/classes, substituted libraries, changed class bytes and malformed resources fail.

The current observed baseline is:

| Module | Real compiled and packaged classes | Existing host tests |
|---|---:|---|
| common | 67 | 3 GalleryTitles tests |
| data | 160 | NO_TEST_SOURCES |
| i18n | 3 | NO_TEST_SOURCES |
| ui | 171 | NO_TEST_SOURCES |

Class counts are inspected, not hardcoded acceptance thresholds. Modules without test sources explicitly report `executed=false` / `NO_TEST_SOURCES`, not successful tests. Existing test sources require positive actual testcase counts, zero failures/errors/skips, consistent XML counts and distinct suite identities. Hidden failure elements, duplicate suites, missing receipts and stale receipts without test sources fail. New test source sets need explicit stage integration. M3 still needs meaningful domain/persistence/migration coverage; this check does not invent those tests.

The retained artifact contains all four actual AARs, the common host-test XML and a source-bound JSON inventory with package/class/resource hashes and test coverage. Its scope is `CURRENT_ANDROID_SHARED_CORE`, with `shared_library_artifacts_verified=true`. Final installation assets, product P0, Desktop/iOS shared targets, minimum systems, tablet/iPad behavior and full dependency/SBOM acceptance are not established; publication stays disabled.

The first local proof invoked the real four `bundleAndroidMainAar` tasks plus host tests with `--rerun-tasks`: 97 tasks executed, 22 seconds, four real AARs, the common three tests re-executed and other modules honestly NO-SOURCE. This is source/build evidence, not a replacement for the new commit's complete Push/PR workflows and all platform artifacts.

Inputs are the user's M2 contract and existing EhViewer convention plugin, core modules, Java-17 verifier and JUnit/Gradle outputs. No additional Animeko code, resources, scripts, dependencies or design are reused. No maintained implementation language, dependency/version, Native platform, original Android acceptance, runner or production `releases.yml` changes. Actual staged secret-free release packaging aggregation is still pending, so M2 remains incomplete.
