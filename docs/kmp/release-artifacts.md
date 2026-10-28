# Eleven-target delivery contract

M2 / IN_PROGRESS. M1 passed on `2f00e595ca74cd89b1d8d860077884b8306fdf54`, with both complete CI events and actual artifacts verified. The production `releases.yml` remains unchanged in this slice. Its prepare → build(matrix) → package → publish chain and original Android signing, diagnostics and publication protections remain authoritative.

`tools/verification` now has one Kotlin target contract, `ReleaseContract.kt`. The `release-contract` command reads the current local Git SHA and the single `releaseVersion` / Android `versionCode` declarations. A requested tag must match that source version. It emits a **plan**, with `verification_scope=CONTRACT_ONLY`, `artifacts_verified=false`, and `publish_enabled=false`. It creates no tag, Release or installation package and reads no production credentials.

| Platform / CPU | Exact planned asset | Build runner / CPU | Required content |
|---|---|---|---|
| Android arm64-v8a | `EhViewer-x.y.z-arm64-v8a.apk` | ubuntu-latest / X64 | Original signed APK / arm64-v8a |
| Android armeabi-v7a | `EhViewer-x.y.z-armeabi-v7a.apk` | ubuntu-latest / X64 | Original signed APK / armeabi-v7a |
| Android universal | `EhViewer-x.y.z-universal.apk` | ubuntu-latest / X64 | All three original Native ABIs |
| Android x86_64 | `EhViewer-x.y.z-x86_64.apk` | ubuntu-latest / X64 | Original signed APK / x86_64 |
| iOS arm64 | `EhViewer-x.y.z-arm64-unsigned.ipa` | macos-latest / ARM64 | Standard Payload app / device arm64 Mach-O |
| macOS x86_64 | `EhViewer-x.y.z-macos-x86_64.dmg` | macos-latest / ARM64 | Real Intel launcher/JVM/Skiko/Rust; Rosetta required |
| macOS arm64 | `EhViewer-x.y.z-macos-arm64.dmg` | macos-latest / ARM64 | Real ARM64 launcher/JVM/Skiko/Rust |
| Linux x86_64 | `EhViewer-x.y.z-linux-x86_64.AppImage` | ubuntu-latest / X64 | Real AppImage / executable / matching runtime |
| Linux aarch64 | `EhViewer-x.y.z-linux-aarch64.AppImage` | ubuntu-24.04-arm / ARM64 | Real AppImage / executable / matching runtime |
| Windows x86_64 | `EhViewer-x.y.z-windows-x86_64.zip` | windows-latest / X64 | Portable ZIP / executable / matching runtime |
| Windows arm64 | `EhViewer-x.y.z-windows-arm64.zip` | windows-11-arm / ARM64 | Portable ZIP / executable / matching runtime |

The Android production-certificate requirement describes final trusted releases. Feature/PR snapshots retain the existing explicit test-signing policy; this plan does not invoke production signing or weaken that policy. iOS remains unsigned as requested and cannot be described as directly installable. Development-signed real-device evidence is unavailable. Android phone/tablet, iPhone/iPad and shared icon requirements remain M5–M7 acceptance work.

`verifyReleaseAssetNames` checks only the exact eleven root-level names. It rejects every missing target, duplicates, extras (including diagnostics), nested paths, incorrect case and mixed versions. Passing it does **not** validate actual bytes, package format, CPU, bundled runtime, installation, signatures, source identity or SHA-256. M6 must implement those additional checks and atomic aggregation in the original workflow; M8 must verify all eleven real packages before its independent version update. No AppImage or Windows ZIP is synthesized by renaming an existing archive.

Current coverage: the original four Android SNAPSHOT APKs have actual per-SHA preflight; iOS, ARM-produced Intel DMG, Linux ARM64 and Windows ARM64 have the M1 technical probes recorded in the ledger. Final eleven-asset aggregation, unsigned device IPA packaging, both final DMGs, both AppImages and both ZIPs are **NOT_IMPLEMENTED / NOT_VERIFIED** as complete release products. M0–M7 retain releaseVersion `1.15.10` / versionCode `180074`; `planned_tag` is metadata, not an existing tag or publication authorization.

Run from the EhViewer checkout after installing the verifier:

```text
tools/verification/build/install/verification/bin/verification release-contract
tools/verification/build/install/verification/bin/verification release-contract --tag v1.15.10
```

CI adds this secret-free contract check to the original mandatory Android job and retains `reports/release-contract.json` in its existing verification artifact. Separate phase/source/license checks and the full cross-platform dry-run remain subsequent M2 work. No reference checkout is needed on any runner.
