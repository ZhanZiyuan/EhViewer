# Actual common JVM filesystem acceptance

M3-004 is GATE_PASSED at98c1f12d4 after both twelve-job gates/all23 actual artifacts per event and the same English Draft PR readback. The following describes that accepted slice; M3-005 now extends common to Native, sharing these real actuals/tests through nonAndroidMain/nonAndroidTest. See [ADR0028](adr/0028-common-native-filesystem.md). Core/common adds only a Java17 JVM target; domain retains Android/JVM/two iOS targets, and data/i18n/ui remain Android-only. Existing Android filesystem implementations are unchanged. See [ADR0027](adr/0027-common-jvm-filesystem.md) for source/reference/license choices and limits.

Build the actual library and execute its tests:

```sh
./gradlew :core:common:desktopJar :core:common:desktopTest :core:common:spotlessCheck --console=plain --no-daemon
./gradlew -p tools/verification check installDist --console=plain --no-daemon
tools/verification/build/install/verification/bin/verification common-jvm-probe --jar core/common/build/libs/common-jvm.jar
```

common-jvm-probe executes actual packaged entry points using real owned disk files. It checks code-source ownership, the real system filesystem, exact Unicode/large-byte/truncation behavior, metadata/list/copy/atomic move/delete, actual I/O errors and source/sink closure after callback/cancellation failures. It reports its actual OS/JVM and successful owned cleanup. It does not establish clean source or final product acceptance.

Production CI uses common-jvm --input SHARED_ARTIFACT_DIRECTORY --output FRESH_RECEIPT_FILE. The input contains shared-core.json, shared-core/common-jvm.jar and the actual common-jvm XML files. The consumer first rejects dirty/different-source/older producer evidence, compares complete Java17 compiler/class bytes and positive eight-test XML, then executes the real JAR on the current native JVM. A partial or missing receipt fails aggregation. Output is a new JSON file in an already owned existing evidence directory; user files are not overwritten.

The shared job forces actual common JVM compilation and both test suites: original titles3 plus real filesystem5. Existing Android common tests remain3; a JVM replacement cannot certify Android document-provider behavior. The five existing native JVM consumers add real OS execution without requiring the Android Gradle project to build on Windows. Each retains its separate receipt in its existing artifact. The final package gate reparses the XML and repeats the real common JAR probe, requires all five clean same-source receipts and original checks, and keeps Native-common/product/P0/all-eleven/publication acceptance false or NOT_INTEGRATED.

No final release asset is added. The source-bound staging candidates remain four APKs, one ARM-produced/native-Intel-consumed DMG and one native Windows ARM64 ZIP; five final targets still require independent implementation and acceptance. Windows x64 is not integrated, Native common is not enabled, and the fixed version remains1.15.10/180074 until M8 prerequisites pass.
