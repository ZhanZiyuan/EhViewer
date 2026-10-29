# Portable domain core: first Android/JVM migration

M3 / IN_PROGRESS / CI_PENDING. Existing used APIs move into core/domain with their packages and behavior preserved; core/common reexports them. GalleryTitles still drives EhUtils.getSuitableTitle and ArchiveCatalog still defines ArchiveReader's non-JNI boundary. No fake platform implementation or database replacement is introduced.

| Module | Actual work in this slice | Outstanding |
|---|---|---|
| domain | Android convention/AAR, desktop-named JVM JAR, common tests on both, actual packaged-JAR API loading | iOS/Native targets and direct desktop UI integration |
| common/data/i18n/ui | Original Android targets/artifacts/resources/tests and import/dependency boundaries retained | Platform interfaces, persistence/session/data and shared UI migration |

Shared CI forces original compiles/resources/tests plus domain AAR/JVM JAR/tests. Android validation preserves all prior tasks and adds domain host tests. Shared evidence includes five real AARs, the actual JVM artifact, complete compiler/class hashes, real XML results and seven title/four archive-method linkage checks. Aggregation verifies the JAR's actual SHA and independently calls it. Existing source/job/run/transport/version/signature/native checks and six genuine candidate/five final-outstanding distinctions remain mandatory.

Only core/domain can declare this reviewed JVM target. Common-source platform imports and upward dependencies fail; other legacy core modules/conventions remain Android-only. Declared module targets do not mark M3 or final delivery complete. Actual iOS domain compilation/tests will be introduced independently and are not inferred from unrelated simulator startup.

See [ADR0024](adr/0024-portable-domain-android-jvm.md) for reference paths, design differences, license handling and validation. Maintained sources remain Kotlin/Rust, toolchain pins and1.15.10/180074 remain frozen, and work stays in the same English Draft PR.
