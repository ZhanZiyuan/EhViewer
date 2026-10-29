# Reviewed migration phase

M3-005 / IN_PROGRESS: common and domain each declare exactly Android/JVM/iosArm64/iosSimulatorArm64; data/i18n/ui remain Android-only. All12 jobs/barriers remain. The new common-native artifact is required alongside domain-native, making23 stage inputs/24 artifacts per event. Declaration metadata is not execution proof. M3-004 was GATE_PASSED at98c1f12d4; older descriptions below are historical. See ADR0028.

M3-004 / IN_PROGRESS: common now declares Android/JVM, domain retains Android/JVM/two iOS targets, data/i18n/ui stay Android-only. Phase checks preserve all12 actual jobs and require source-producer barriers for the five real common JVM consumers in four existing job declarations. These declarations do not certify execution; each new SHA still needs the full dual workflows and actual shared/host/Native/package evidence. M3-003 is GATE_PASSED at d3d2e47d1; complete M3 remains unfinished. See ADR0027.

M3-003 / GATE_PASSED at d3d2e47d1 (historical slice) adds the nine existing gallery models and original protocol regression tests to the already accepted domain targets. It does not expand the platform/job/runner scope or complete product integration. Android/JVM/Native must each execute all eight domain tests; actual compiler/class/API/XML and original package proofs remain mandatory. See ADR0026; M3-002 is accepted at4cdb47f76, full M3 remains incomplete.

M3-002 / GATE_PASSED at4cdb47f76 after both full twelve-job gates/all23 actual artifacts per event. Actual production-domain iosArm64/iosSimulatorArm64 compilation and three common Native tests execute in mandatory ci/ios. Aggregation requires the separate domain-native artifact among22 original inputs. Phase declarations do not certify execution; every later SHA still requires dual complete workflows and every actual artifact. M3 is IN_PROGRESS.

Actual M2 acceptance completed at17a17e. M3-001 at e5ed5350b is GATE_PASSED after both full twelve-job workflows and all22 actual artifacts per event: domain gains Android/JVM library compiles/tests, while four legacy modules retain Android-only targets. Actual aggregators repeat the same uploaded domain JAR's API probe. M3 remains IN_PROGRESS; declarations still cannot certify execution or milestone completion. Five final packages, full product P0 and later migrations remain incomplete.

The Kotlin `migration-phase` CLI describes the current **M3** boundary and validates it against the actual job/dependency list and retained architecture checks. It remains mandatory in `ci/source-policy` and the strict always-run gate. Missing, duplicated, renamed or undeclared jobs and removed/duplicated dependencies fail. Failure/cancelled/skipped/neutral/pending/missing results remain fatal; no original job, runner, deadline or signing isolation is removed.

The scanner validates every nonblank/noncomment job header in the reviewed two-space mapping. Unquoted ASCII letter/underscore IDs may include digits, hyphens and uppercase letters; header comments are supported. Unsupported representations, including quoted or flow-style headers, are explicitly rejected, never silently omitted. The mapping must remain in the reviewed layout, with one root jobs key; a layout/parser change requires its own verification. This bounded declaration check does not replace actionlint or claim to implement general YAML. ADR0018 records the actual pre-fix counterexamples and test-first repair.

The current phase retains **1.15.10 / Android180074** and all original Android checks. Only core/domain may declare the exact reviewed desktop JVM and two iOS targets, with no upward or platform imports in common source. All other core modules retain the original Android-only restriction. Phase metadata lists domain Android/JVM/iosArm64/iosSimulatorArm64 and identifies ios as the Native evidence job; all four legacy modules remain Android. Direct iOS/desktop product integration and actual device execution remain unverified. A premature M8 version/code change or ambiguous version authority still fails.

The generated JSON binds the actual source SHA and dirty state, rejects dirty CI trees, and includes the actual retained architecture result. It reports `PHASE_DECLARATIONS_ONLY`, `milestone_complete=false`, `artifacts_verified=false`, `product_p0_verified=false` and `publish_enabled=false`. It is not a build, package or device receipt. Actual job/artifact acceptance remains separately required for both events at every SHA.

| Final delivery ID | Current CI surface | Final delivery acceptance |
|---|---|---|
| android-arm64 | Test-signed APK preflight | NOT_VERIFIED |
| android-armv7 | Test-signed APK preflight | NOT_VERIFIED |
| android-universal | Test-signed APK preflight | NOT_VERIFIED |
| android-x64 | Test-signed APK preflight | NOT_VERIFIED |
| ios-arm64 | Device target build / ARM64 Simulator spike | NOT_VERIFIED |
| macos-x64 | ARM-produced DMG / Rosetta / native Intel consumption spike | NOT_VERIFIED |
| macos-arm64 | Host JNI only; application DMG not integrated | NOT_VERIFIED |
| linux-x64 | Host JNI only; application AppImage not integrated | NOT_VERIFIED |
| linux-arm64 | Application image spike; AppImage not integrated | NOT_VERIFIED |
| windows-x64 | Application/runtime not integrated | NOT_VERIFIED |
| windows-arm64 | Source-bound ZIP and actual extracted native launch spike | NOT_VERIFIED |

The existing Android production release baseline is retained. Feature APK checks use temporary test certificates; they do not establish the future eleven-target production delivery contract. The current Intel spike's DMG name is not the final release name. Directory images on Linux/Windows are not AppImage/ZIP acceptance. iOS device compilation is not a signed-device test or final unsigned IPA structure proof. iPad, full product P0, minimum systems, hardware acceleration and complete license/SBOM acceptance remain explicit outstanding work.

The required shared category now builds the five real Android AARs plus domain JVM JAR/tests, verifies complete compiler bytes/real XML and loads the actual packaged production API. This library step does not imply desktop UI or iOS domain integration. See [shared-core-check.md](shared-core-check.md) and ADR0024.

The new mandatory `ci/release-dry-run` implementation depends on all original logical jobs and is required by the always-run gate. It verifies authenticated actual archive/source ownership and stages six actual candidates: four original APKs, the ARM-produced/native-Intel-consumed DMG and the Windows ARM64 ZIP with extracted native startup proof. The declaration report lists those six targets and five remaining final package targets, with no missing check category declared. This is implementation coverage, not hosted acceptance: milestone_complete/artifacts_verified/product_p0/publish remain false in the declaration-only report. Both new-SHA workflows, the real package job and every actual artifact are still required before M2 acceptance. See [staged-package-dry-run.md](staged-package-dry-run.md).

The production release workflow, original platform jobs, runners, deadlines, dependencies, licenses, source-language guard and stable metadata are retained. The original main snapshot cache policy is retained with its selected producer run exposed to the new verifier. The final eleven-package chain remains M6-M8 work; no missing format is represented by a renamed archive or parser receipt.

This contract is independently derived from the user's PRD and actual EhViewer CI/module audit. No additional Animeko source, resource, script or dependency is reused. CI reads only EhViewer. Validate locally with the full verifier suite and `verification migration-phase`, then use both exact-SHA hosted workflows and their real retained JSON/other platform artifacts before accepting the commit.

M3-006 isolated admission repair is IN_PROGRESS: iOS now explicitly waits for rust_host and the ARM desktop_intel producer, retaining always(), every runner/deadline/test and the strict gate. The phase rejects missing/duplicate/wrong barriers and failure-skipping conditions, and reports ios_admission_dependencies separately from common JVM producer jobs. This changes admission only. Priorbecdcd4 Push failed through hosted ARM capacity cancellation; its PR passed all12 jobs/all24 actual artifacts. See ADR0029; full M3 remains incomplete.
