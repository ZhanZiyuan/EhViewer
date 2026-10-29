# Actual shared-core checks for the current stage

## Current M3 slice: portable domain, GATE_PASSED; M3 IN_PROGRESS

M3-002 / LOCAL_VERIFIED / CI_PENDING: Native domain compilation and three real common tests execute in the mandatory ci/ios job on ARM64. Its separate domain-native artifact is now a required original input to aggregation. Ubuntu shared-core continues to report only its actual Android/JVM artifacts/tests, with the Native evidence job explicitly identified; it does not pretend to execute iOS there. Stage schema3 independently checks both actual KLIBs, complete compiler file inventories/API dumps, the executed test binary/platform and positive XML. All original checks remain;23 actual artifacts per event are required for new-SHA acceptance. See ADR0025; full M3/product integration is incomplete.

At e5ed5350b both complete twelve-job gates and all22 actual artifacts per event passed independent acceptance. Each forced shared build executes123 tasks. Five actual AARs/401 Java17 classes, real two-class domain JVM JAR, Android/JVM domain3 tests each and common consumer3 tests match complete compiler/file/XML evidence. Both real six-package aggregators independently execute that exact JAR's seven title/four-method API probe. All44 official archives/844214195 bytes and1810 original payload files/1318582654 bytes were independently rehashed. No Native/iOS domain or direct desktop product integration is inferred. The following local-development and earlier M2 descriptions retain their historical provenance; full evidence is in the ledger/ADR0024.

M2 closed at17a17e with both full twelve-job gates and all22 actual artifacts per event. The first M3 slice preserves all four legacy Android libraries and adds the real domain AAR, desktop-named JVM JAR and tests. Core/common reexports the moved production title/archive API. Common/data/i18n/ui retain Android-only targets; domain alone declares the reviewed JVM target. iOS domain and direct desktop UI integration remain NOT_INTEGRATED.

`shared-core` now reports ANDROID_CORE_AND_PORTABLE_DOMAIN_JVM/schema2. Five AARs are matched to actual compiler bytes at Java17; domain-jvm.jar is likewise checked against every real JVM class, loaded from its own code source, and called for seven title cases/four original archive-interface methods. Actual observed Android class counts are common65/domain2/data160/i18n3/ui171; domain JVM contains two Java17 classes. Common Android tests3, domain Android tests3 and domain JVM tests3 execute; the three modules without tests remain NO_TEST_SOURCES. Test source sets/target declarations are never substitute execution receipts. Both the original Android checklist and forced shared builds gain domain tests without losing any previous task.

Local prototype26s/38 tasks, full196 tools tests/46s and full Android367 tasks/3m12s passed. Actual five-AAR/JAR/test/API inspection passed; the link probe ran on the local aarch64 JVM25.0.3, separately from bytecode17 compatibility. These are local dirty-tree/source17a17e development results, not new-SHA hosted proof. Full next-SHA dual workflows, all native artifacts and actual staged JAR binding are still mandatory. No Native/iOS domain, full data/UI migration or final P0 is claimed. See ADR0024 and domain-core-migration.

## Historical M2 Android-only baseline

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
