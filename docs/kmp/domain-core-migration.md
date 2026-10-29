# Portable domain core: first Android/JVM migration

Current M3-002: LOCAL_VERIFIED / CI_PENDING for iosArm64/iosSimulatorArm64 production compilation and real common tests on an owned Simulator. Both compiler KLIBs, API dumps and the actual test executable/XML are independently inspected; Native product integration remains incomplete. The mandatory iOS job supplies a separate domain-native artifact, required by aggregation alongside the existing JVM evidence. See [ADR0025](adr/0025-ios-domain-native.md) and the [product matrix](feature-matrix.md). The following M3-001 acceptance remains its historical scope.

M3-001 / GATE_PASSED; M3 / IN_PROGRESS. Existing used APIs move into core/domain with their packages and behavior preserved; core/common reexports them. GalleryTitles still drives EhUtils.getSuitableTitle and ArchiveCatalog still defines ArchiveReader's non-JNI boundary. No fake platform implementation or database replacement is introduced.

Commit e5ed5350b47ad753dffd95ba72d46059a8d73751 passed both full twelve-job workflows: [Push40851262709](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40851262709) and [PR40851270786](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40851270786). All22 actual artifacts per event were independently accepted, including five AARs/401 Java17 classes, the two-class real JVM JAR, domain Android/JVM tests3 each and the original common consumer3 tests. Both actual aggregators load that JAR and repeat seven title calls/four archive methods. Final independent rehashing covers44 official ZIPs/844214195 bytes and1810 original payload files/1318582654 bytes. Full M3, Native/iOS domain and direct desktop product integration remain incomplete; see the ledger and ADR0024.

| Module | Actual work in this slice | Outstanding |
|---|---|---|
| domain | Android convention/AAR, desktop-named JVM JAR, common tests on both, actual packaged-JAR API loading | iOS/Native targets and direct desktop UI integration |
| common/data/i18n/ui | Original Android targets/artifacts/resources/tests and import/dependency boundaries retained | Platform interfaces, persistence/session/data and shared UI migration |

Shared CI forces original compiles/resources/tests plus domain AAR/JVM JAR/tests. Android validation preserves all prior tasks and adds domain host tests. Shared evidence includes five real AARs, the actual JVM artifact, complete compiler/class hashes, real XML results and seven title/four archive-method linkage checks. Aggregation verifies the JAR's actual SHA and independently calls it. Existing source/job/run/transport/version/signature/native checks and six genuine candidate/five final-outstanding distinctions remain mandatory.

Only core/domain can declare this reviewed JVM target. Common-source platform imports and upward dependencies fail; other legacy core modules/conventions remain Android-only. Declared module targets do not mark M3 or final delivery complete. Actual iOS domain compilation/tests will be introduced independently and are not inferred from unrelated simulator startup.

See [ADR0024](adr/0024-portable-domain-android-jvm.md) for reference paths, design differences, license handling and validation. Maintained sources remain Kotlin/Rust, toolchain pins and1.15.10/180074 remain frozen, and work stays in the same English Draft PR.
