# Reviewed migration phase

The Kotlin `migration-phase` CLI describes the current **M2** boundary and validates it against the actual CI job/dependency list and original architecture checks. It is a mandatory step of `ci/source-policy`; the always-run `ci/gate` already requires that job's actual success. Missing, duplicated, renamed or newly undeclared jobs and removed/duplicated gate dependencies fail. Existing strict result-state regressions still reject failure, cancelled, skipped, neutral, pending and missing mandatory results.

The scanner validates every nonblank/noncomment job header in the reviewed two-space mapping. Unquoted ASCII letter/underscore IDs may include digits, hyphens and uppercase letters; header comments are supported. Unsupported representations, including quoted or flow-style headers, are explicitly rejected, never silently omitted. The mapping must remain in the reviewed layout, with one root jobs key; a layout/parser change requires its own verification. This bounded declaration check does not replace actionlint or claim to implement general YAML. ADR0018 records the actual pre-fix counterexamples and test-first repair.

The current phase retains stable **1.15.10 / Android 180074**, all original Android checks, and the existing Android-only shared-core guard. It does not enable new product targets or relax architecture imports. A future M3 source-set migration must update this reviewed contract and add real target compilation/tests in its own verified change. A premature M8 version change or ambiguous version authority fails this M2 contract.

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
| windows-arm64 | Application image spike; final ZIP not integrated | NOT_VERIFIED |

The existing Android production release baseline is retained. Feature APK checks use temporary test certificates; they do not establish the future eleven-target production delivery contract. The current Intel spike's DMG name is not the final release name. Directory images on Linux/Windows are not AppImage/ZIP acceptance. iOS device compilation is not a signed-device test or final unsigned IPA structure proof. iPad, full product P0, minimum systems, hardware acceleration and complete license/SBOM acceptance remain explicit outstanding work.

Two required categories are still **pending integration**, not optional/skipped successes:

- `ci/shared-kmp`: dedicated phase-aware shared compilation, tests and resource checks, before M3 enables Desktop/iOS shared-core targets.
- `ci/release-dry-run`: real installed-target secret-free packaging aggregation, extended to all eleven final files in M6-M8, with publish disabled.

M2 stays incomplete while these categories are pending. This slice makes the limitation machine-readable; it does not claim their checks have run. No platform build, timeout, runner, original production workflow, dependency, license or source-language boundary changes.

This contract is independently derived from the user's PRD and actual EhViewer CI/module audit. No additional Animeko source, resource, script or dependency is reused. CI reads only EhViewer. Validate locally with the full verifier suite and `verification migration-phase`, then use both exact-SHA hosted workflows and their real retained JSON/other platform artifacts before accepting the commit.
