# Incremental CI execution

The maintainer's acceleration instruction supersedes the earlier duplicate Push/PR and per-commit full-matrix process. Existing commits, historical results and M0–M2 acceptance remain unchanged. The single Draft PR remains #15; production signing, release publication protections and the frozen 1.15.10 / 180074 version remain unchanged.

## Check tiers

| Tier | Required execution |
| --- | --- |
| Fast | Source/license/architecture policy, all verifier regressions, shared Android/JVM tests and artifacts, original Android Rust tests/Clippy, application unit tests, lint, Debug/Release APKs, benchmark build, four-ABI/signature/JNI/16-KiB checks and diagnostics |
| Platform | Fast plus each affected platform and its real producer dependencies; portable `core/` API changes expand to every integrated platform |
| Full | All integrated platform checks and the source-bound package dry run; mandatory for CI/build/Native/release changes, unknown paths, main, phase completion and final version freeze |

This is the current migration matrix, not a declaration that all five final applications or all eleven release artifacts are accepted. Existing evidence explicitly records missing product integration and final packages.

`.github/ci-plan.json` declares paths, mandatory jobs and producer dependencies. Kotlin computes a dependency closure and records each path's reason, source SHA, PR head and comparison baseline. It compares the actual merge against the newest reachable successful CI head belonging to the same PR. The first run, unavailable matching history, main and manual Full requests select Full. An API error fails planning; it cannot authorize a narrower plan. Failed commits are included in the next diff because they cannot become the successful baseline.

Phase completion must update `docs/kmp/milestones/Mx.json`; that path forces Full. Changes to any Gradle script, version catalog, Native source, CI policy, release workflow or changelog also force Full. Before merging, run Full again on the final head. `workflow_dispatch` defaults to Full. Do not label a stage complete until its product acceptance and this run both succeed.

## Stable gate and receipts

`feature/kmp` uses PR CI only. Main retains Push CI. The `ci/gate` name and existing main ruleset are retained. The always-running gate reads all actual dependency results and the source job's plan, compares the downloaded plan with the job output, and recalculates it from the checked-out declarative policy. Every required result must be `success`; missing, failed, cancelled, neutral, pending and required-but-skipped results fail. Only explicitly unaffected jobs may be skipped. An unexpected failure in an unaffected job also fails.

The source job builds and tests the JVM 17 verifier once. Other native runners consume that run's distribution and test XML instead of compiling and testing the same verifier repeatedly. Shared compilation uses normal Gradle input/cache tracking. Native execution retains necessary fresh-link/test checks and owned Simulator cleanup; cached declarations cannot stand in for execution.

Full package acquisition retains one authoritative archive digest/CRC/payload pass. Release candidate validation still checks source, version, architecture, actual files, actual test receipts and hashes. Production `releases.yml` is unchanged in this change.

Actions Summary automatically records the plan and each actual job result, including failures, with its run URL and SHA. These are the per-commit CI record going forward; do not create commits just to add historical links. Keep `ci-ledger.md` as historical evidence and add substantive phase closures alongside product changes.

The old YAML-order/list-format assertions are replaced by plan/gate behavioral regressions: path routing, dependency closure, forced full coverage, invalid/cyclic policy, unsafe paths, source mismatch, every required job's failure states, allowed unaffected skips and mandatory Android checks. Existing production trust, failure propagation and package validation tests remain.

No Animeko implementation or workflow was copied. This change independently simplifies EhViewer's existing CI, following the maintainer's execution requirements. GitHub's documented `needs` results and `always()` semantics define the gate behavior: https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#needs-context and https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#jobsjob_idif.

## Next product delivery

Resume M3 with shared Ktor networking and persistent Cookie/Session, followed by gallery search, details and an actual image page. Preserve Android's browser-cookie integration and existing Rust HTML/CBOR parser. Acceptance must include real Desktop/iOS business data and error/cancellation/session tests; this work must not expand into another infrastructure-only sequence.

The previous fdf57a0 PR run exposed an actual ten-minute common Native link timeout caused by whole-graph forced recompilation. Native verification now reruns only the module's two compiles, test link and test execution. Unchanged domain/build-logic dependencies may retain their normal results; every tested module's KLIB, executable and XML must still be fresh and all original tests must actually execute. No timeout or acceptance requirement was relaxed. The old failed run remains a failure.
