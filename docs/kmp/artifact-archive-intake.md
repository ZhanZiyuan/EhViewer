# Verified artifact archive intake

M2 component / IMPLEMENTED / LOCAL_VERIFIED / CI_PENDING. Actual staged package aggregation remains pending. Stable1.15.10 / Android180074, original platform checks and the production release workflow remain unchanged.

The Kotlin `artifact-archive` command accepts an actual downloaded GitHub artifact ZIP and a complete official Actions artifact catalog, a positive artifact/run ID, the full run head SHA, and a fresh extraction directory. It rejects truncated catalogs, duplicated IDs/names, missing identities, another run/head, expiry, unsafe names, absent official SHA-256, and an archive whose size/hash differs from the catalog. These checks run before creating output. The caller obtains catalog/archive bytes through authenticated read-only GitHub access; the inspector does not authenticate caller-supplied metadata or make network requests.

The run head is explicitly separate from the checked-out source: a PR run can record the branch head in GitHub metadata while the archive contents bind a different test-merge SHA. This transport component does not reinterpret either as native execution or product source acceptance. Subsequent staged package validation must inspect actual source-bound receipts and installation bytes.

Before extraction it validates every canonical ZIP path, the complete unique/case-safe entry set, file/parent collisions and declared expansion limits. It creates only a new owned root and ordinary files with CREATE_NEW, never interprets Unix link attributes, checks each streamed size/CRC, and reads every output file back for size/SHA. It verifies that the original archive hash remains unchanged. Parent directories are resolved canonically, preserving existing user data through directory aliases. Failure can leave partial evidence only inside its newly created owned root; no successful receipt is returned. Use a different fresh directory after a failed inspection.

The result is `GITHUB_ARTIFACT_TRANSPORT_ONLY`. It explicitly records `product_package_validation=NOT_EXECUTED`, `native_execution=NOT_EXECUTED`, `executable_permissions=NOT_PRESERVED_BY_THIS_BYTE_INSPECTOR`, and false product-P0/all-eleven/publish acceptance. It does not certify an APK signature, DMG architecture, executable permissions, runtime/entry, AppImage/IPA format, same-package startup or the eleven final products. Those existing/future validators remain mandatory.

Local regressions use actual compiled Kotlin class bytes, hidden metadata and Unicode resources. They challenge changed archive sizes/digests, cross-run/head metadata, expiry, missing digests, incomplete/ambiguous catalogs, traversal/absolute/drive/backslash/control paths, case/parent collisions, directory-only archives, existing user data/aliases, Unix symlink attributes and internal CRC corruption even when the outer archive matches its catalog digest. No new dependencies, Animeko implementation/assets/scripts or other maintained implementation languages are introduced.

Example after independently obtaining the real official catalog and archive:

```text
tools/verification/build/install/verification/bin/verification artifact-archive --catalog CATALOG.json --archive ARTIFACT.zip --artifact-id ARTIFACT_ID --run-id RUN_ID --head-sha FULL_RUN_HEAD_SHA --output NEW_DIRECTORY
```

Both exact new-SHA hosted workflows and all applicable actual artifacts remain required before accepting this component. M2 package aggregation and M3-M8 are incomplete.

Local validation passed the final180 verifier tests with zero failures/errors/skips in43s; the preceding179-test full run passed1m after an initial5s formatting failure. Original Android319 tasks/tests/lint/build passed1m53s,44 executed/275 reused, and all retained feature Native/diagnostic/Java17/Debug-Release test-signature/JNI/ABI/alignment/snapshot/shared/source/phase/contract/architecture checks, actionlint and whitespace passed. An extra manual invocation of the stable-production `release-input-tests` command rejected SNAPSHOT input before its signature check; it is recorded as a failed, inapplicable invocation, with no production policy/signing change and no passing claim for that command.

The exact new CLI also inspected all42 real official archives from accepted50d1 Push40816308738 /PR40816312011:1674 extracted files /1000108811 bytes passed full read-back and independent per-file SHA checks. Both catalogs were freshly obtained through read-only GitHub access. This is actual transport inspection of previously accepted inputs, with native/package execution explicitly NOT_EXECUTED; it cannot replace new-SHA hosted acceptance. See the CI ledger for exact provenance and limitations.
