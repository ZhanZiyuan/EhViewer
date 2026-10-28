# 逐 SHA CI / CD 证据

M0 / GATE_PASSED；M1 / IN_PROGRESS。仅 `feature/kmp` → `main` 的同一 Draft PR；不改 Ready、不合并、不创建 tag/Release。稳定版本冻结 `1.15.10 / 180074`，M8 才独立准备 `1.16.0`。

## 已有基线

| SHA | 来源 | CI | Release | 结论 |
|---|---|---|---|---|
| `423b8af13632896b55780783b54b5f720ec4c9dd` | 刷新后的 origin/main | [39199763664](https://github.com/ZhanZiyuan/EhViewer/actions/runs/39199763664) push SUCCESS | [39201331638](https://github.com/ZhanZiyuan/EhViewer/actions/runs/39201331638) workflow_run SUCCESS | 真实历史基线，不替代新 SHA 验证 |

初始 `feature/rusty` HEAD `e4d8cb5ced81d903995cad4b03fc733af3e58691` 已被上述 main merge 包含；工作树干净，没有覆盖用户修改。

## M0-001：安全启动迁移与双项目审计

此条随首个实现提交创建。提交自身的 SHA 无法写入自身文件：commit 后在工作树追加精确 SHA/运行链接，并先更新同一 PR；下一次允许的证据提交收录前一 SHA 的最终结果，禁止 amend/强推或虚构自引用 SHA。

- 改动：只读参考与迁移审计；main-only 生产签名/诊断路径；feature/PR 测试签名 snapshot；生产 Release 入口限制 main；feature/kmp 不取消旧 run；always-run `ci/gate`。
- 原 Android tests/lint/Debug/Release/benchmark、Rust、架构/语言、JNI/ABI/16 KiB/诊断检查全部保留。
- M0 候选包 dry-run 是 CI 中真实四 ABI `prepare-snapshot --test-signing` 及 manifest/哈希/上传路径；不是正式生产签名验证，也不是未来 11 包全部成功。
- 新生产边界测试实际执行 snapshot shell 步骤，验证 feature push/manual、PR 使用测试签名，以及 main 缺公钥时失败；生产 Release 的原离线状态机保留。
- Commit：`1134f2b1c20740fe0002ee8b48c33482d90f1ad1`，`chore(kmp): audit baselines and isolate feature CI signing`；单独 push：2026-10-27 22:20:17 +0800。
- 唯一 [Draft PR #15](https://github.com/ZhanZiyuan/EhViewer/pull/15)：`feature/kmp → main`。连接器创建返回 403 后，使用本机既有 gh 授权创建；未改 Ready、未合并、未创建 tag/Release。
- [Push CI 40520151545](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40520151545) 与 [PR CI 40520356257](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40520356257)：**SUCCESS**，API head_sha 均为上述提交；全部适用检查真实成功后才开始 M1。
- Push：[android SUCCESS / 19m08s](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40520151545/job/122244720547)、[ci/gate SUCCESS](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40520151545/job/122255624198)。PR：[android SUCCESS / 18m40s](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40520356257/job/122245456051)、[ci/gate SUCCESS](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40520356257/job/122256109750)。
- 两条运行的完整 Android、APK/签名模式/JNI/ABI/16 KiB 校验、四 APK snapshot dry-run 和上传步骤均 SUCCESS；仅不适用的生产签名恢复/加密诊断保留为 skipped，未用 skipped 替代适用检查。
- 已下载并核验两份实际 CI snapshot manifest：source SHA 分别匹配 push/head 与 PR test-merge；source_dirty=false，version=1.15.10-SNAPSHOT，versionCode=180074，production_signature_required=false，四包名称及 SHA-256 字段有效。首次本机下载 TLS 超时，重试成功；不是 CI 失败。
- PR test-merge SHA：`48ffd1160fd432f296cd0c42e2f3fc6997b8698e`；base SHA：`423b8af13632896b55780783b54b5f720ec4c9dd`。PR 默认 checkout 的合成提交与 push 源码提交分别记录，不能把二者混为一谈。
- 本地 Kotlin verification `check installDist`：**SUCCESS**（最终变更 1m15s，105 tests；新增 trust/gate tests 3/3、无 skipped/failure/error）；Rust Core **SUCCESS**：19 unit + 14 archive integration，3 个既有依赖在线服务的测试保持原有 ignored（没有新增忽略）。
- Rust JNI **SUCCESS**：5/5；Rust fmt **SUCCESS**；原 3 Android targets 严格 Clippy `-D warnings` **SUCCESS**。
- `actionlint` 两个 workflow、`git diff --check`、verification `architecture` **SUCCESS**（124 Kotlin 文件，Kotlin/Rust policy PASS）。首次 actionlint 误在 `native/rust` 调用相对路径失败，改从仓库根运行成功，未修改检查规则。
- 本地完整 Android tests/lint/build 首次：**FAILED**（13m29s，295 tasks）。失败为 `:app:buildCMakeDebug[arm64-v8a]` 的 Nettle 4.0 tarball 下载：原两个 URL 均超过现有 120s，总文件 2,630,735 bytes。不是新增 Kotlin/Rust 编译错误；原始证据在本地忽略目录 `app/build/intermediates/cxx/Debug/3n73415c/logs/arm64-v8a/build_stdout_ehviewer.txt`。保留失败记录，先修复依赖获取再重跑，不修改版本、来源或 checksum。
- 修复方式：其他 ABI 已完成下载的同版本 tarball 实测 SHA-256 等于原 CMake 固定值 `3addbc00da01846b232fb3bc453538ea5468da43033f21bb345cb1e9073f5094`；仅复用到本地忽略的 `.cxx/*/*/*/nettle-prefix/src/` 构建缓存。失败的残缺文件哈希不同，未作为有效输入。CMake 自身仍再次验证原 checksum；完整 Android 命令重跑 **SUCCESS**（1m08s，307 tasks：31 executed、276 up-to-date），未删除或跳过任何要求的任务。完整原始重跑日志暂存本机临时目录；不上传含机器路径的日志。
- 环境盘点：ADB 无设备；Simulator runtimes iOS 26.3/26.4/26.5/27.0；本机 `arch -x86_64 uname -m` 返回 x86_64，仅证明 Rosetta 基础命令可执行，不代表 Intel JVM/Skiko/Rust/DMG 通过。Android 进程采样显示 Gradle 在下载依赖，未给出 Android 成功结论。
- 最终本地 Android/JVM 实际测试 25/25（app 22 + common 3）；data/i18n/ui 原有 test task 为 NO-SOURCE，未宣称具备业务测试。Native source、diagnostics、Java 17 bytecode、Release SNAPSHOT 测试签名 APK 与 Debug APK 的身份/JNI/ABI/16 KiB 校验全部 **SUCCESS**。
- 无密钥本地候选打包 **SUCCESS**：实际生成 `release-output/kmp-m0-001-snapshot` 四 APK 与 manifest，version `1.15.10-SNAPSHOT`、versionCode `180074`、`production_signature_required=false`。此为提交前脏工作树本地验证，不能作为新 SHA 的干净 CI 工件；远程必须从提交树重新构建验证。
- 当前 M0 门禁：**GATE_PASSED**；没有任何新平台验收结论。M1 每个新增切片仍需独立本地与远程验证。

## 可复现命令

在 EhViewer 根运行（本地 macOS host Rust 需配置 libarchive/webp 的库搜索路径）：

```text
./gradlew -p tools/verification check installDist --console=plain --no-daemon
tools/verification/build/install/verification/bin/verification architecture
./gradlew :build-logic:convention:check spotlessCheck :app:testDebugUnitTest :core:common:testAndroidHostTest :core:data:testAndroidHostTest :core:i18n:testAndroidHostTest :core:ui:testAndroidHostTest :app:lintRelease :app:assembleDebug :app:assembleRelease :benchmark:assembleNonMinifiedRelease --console=plain --no-daemon
cargo fmt --all -- --check
cargo test --locked -p ehviewer_core
cargo test --locked --features jvm --lib
cargo clippy --locked --workspace --all-features --target aarch64-linux-android -- -D warnings
cargo clippy --locked --workspace --all-features --target thumbv7neon-linux-androideabi -- -D warnings
cargo clippy --locked --workspace --all-features --target x86_64-linux-android -- -D warnings
actionlint .github/workflows/ci.yml .github/workflows/releases.yml
```

Rust 命令在 `native/rust` 执行；本机测试不启用 `-Prelease`，不读取或设置生产凭据。准确命令结果、环境差异、失败/修复关系随运行追加。远程 Actions 只读轮询必须同时核对 event、head_sha/PR head 与 test-merge SHA、job conclusion，不能只看 PR 汇总绿色。

## M1-001：Kotlin-only iOS 启动切片

前置 M0 SHA 两条 CI 与候选包预检均已 SUCCESS 后才开始。范围为 `app/ios` 的 Kotlin/Native executable + UIKit delegate + CMP 画面，声明式 plist、Kotlin DSL 打包、Kotlin 生命周期验证器和必选 `ci/ios`。无 Swift/ObjC/C/C++ 自主源文件，无参考项目实现复制；见 ADR 0001。尚无 iOS P0、Rust C ABI 或 unsigned IPA 验收结论。

- 首次 Native compile：FAILED（2m25s），UIKit window override 与 ObjCClass 参数用法错误；修正后 Simulator `.app`（ad-hoc）和 iosArm64 executable 实际构建 SUCCESS（4m53s）。未访问开发者签名身份。
- 实测 Simulator Mach-O：arm64，LC_BUILD_VERSION=IOSSIMULATOR，minos=15.0，SDK=26.4；构建主机 Xcode=27.1。最低 OS 15 设备支持仍未验证。
- 首次 smoke：FAILED，在创建设备前后返回不兼容机型（旧 iPhone 6s Plus / iOS 27），未视为应用启动成功。选择器已修正为仅使用 runtime 的 supportedDeviceTypes，并加失败用例；真实重跑待完成。
- Android 完整回归 SUCCESS（3m44s，315 tasks），原 APK/Native/诊断/bytecode 校验与真实四包 snapshot dry-run SUCCESS。验证器最终 109 tests SUCCESS；actionlint SUCCESS。
- 第二次 smoke：安装后在 UIApplicationMain 初始化 delegate 时 SIGABRT，崩溃栈 MissingInitImp；补显式 `ObjCObjectBase.OverrideInit`，修正注解导入位置后，两个 executable 重建 SUCCESS（20s）。
- 第三次 smoke：真实安装、冷启动、Compose ready/active 和截图均成功；已人工检查画面显示 EhViewer Kotlin/Native 启动文本。但后台断言 FAILED，不计为完整 smoke 通过；保留 failure.txt / failed-lifecycle.log。专用 Simulator 清理确认无残留。
- 改用显式 Kotlin UIWindowSceneDelegate + plist scene 配置，两 executable 再次构建 SUCCESS（20s）；第四次完整 smoke **SUCCESS**：iOS 27.0 / iPhone 18 Pro / ARM64，两个不同 PID 的 cold-start/compose-ready、后台、回前台与截图均成功，清理退出码 0，无本次 Simulator 残留。截图已人工检查。
- 本地报告 `reports/ios-m1-attempt4/startup.json` 明确 source_dirty=true（提交前），source_commit 为前置 M0 HEAD；不能当作新 SHA 的远程结果。实际 executable SHA-256 `be1e9be6b616d442e8a0eb661a8a212cd871e9c9b561e623d33d04833a0160de`。CI 强制 clean source，需从新提交树重建、重跑。
- Commit：`8388fb3667b77525dac21b187bec0e7d70107317`，`feat(ios): prove Kotlin-only UIKit startup in simulator`；单独 push：2026-10-27 23:25:38 +0800。
- [Push CI 40530854323](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40530854323)、[PR CI 40530863965](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40530863965)：**FAILED**。两条 android（含原 Rust/source-policy/四包 dry-run）SUCCESS；两条 ci/ios 的两个 executable 与验证器 tests 构建 SUCCESS，但真实 Simulator smoke FAILED，ci/gate 正确失败。停止下一功能切片，只修复本门禁。
- PR [iOS job 122282943222](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40530863965/job/122282943222)：cold-start/compose-ready/active/background/active 已记录，严格 terminate 返回 code 3（found nothing to terminate）。Push [iOS job 122282908934](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40530854323/job/122282908934)：生命周期计数断言失败。两份工件已下载保留；现有日志不足以证明应用退出原因，不以本地成功替代远程失败。
- PR test-merge SHA：`9b35b4147714a38e8016a0f30e811349bc0e3997`；base 仍为 `423b8af13632896b55780783b54b5f720ec4c9dd`，PR 仍 Draft。
- 整个 M1 仍 IN_PROGRESS；Rust C ABI/Desktop FFI、Intel DMG 与其余跨架构 spike 尚未完成。

## M1-002：修复启动参数与逐进程生命周期验证

仅纠正 M1-001 失败门禁，不开始新功能。UIKit 入口传递真实 argc/argv；验证器逐 PID 捕获输出、检查存活、相同 PID 前后台恢复和严格终止后的不同 PID 冷启动。参考依据、设计差异、无代码复制与许可证处理见 ADR 0001，未修改 Android/Rust 实现、依赖版本或 runner。

- iosSimulatorArm64 与 iosArm64 executable / Simulator ad-hoc 打包 / Spotless：SUCCESS（24s）。
- 本地 Android 原完整 tests/lint/build：SUCCESS（2m50s，315 tasks）；Native/diagnostics/bytecode/签名/JNI/ABI/16 KiB 检查与真实四 APK snapshot：SUCCESS，1.15.10-SNAPSHOT / 180074、测试签名模式。报告 source_dirty=true，不能替代新 SHA CI。
- 修复后第一次本地 smoke：FAILED，新的 stdout/stderr 指向 Documents 下仓库报告目录，launchd_sim 在创建进程前返回 EPERM / launch failed；没有执行应用入口。保留 `reports/ios-m1-repair-attempt1` 的环境、阶段与系统日志。改为仅写本次应用自身沙箱 tmp，在 shutdown/delete 前复制诊断到报告；等待重新实测，不据此次失败判断 argv 修复有效性。
- 最终 Kotlin verifier `spotlessApply check installDist`：SUCCESS（1m06s），110 tests / 0 failure/error/skipped；actionlint 与 diff whitespace 检查 SUCCESS。
- 第二次本地 smoke：**SUCCESS**，iOS 27.0 / iPhone 18 Pro / ARM64；first/foreground PID=28131、严格 terminate 后 second PID=28247，逐进程 cold/Compose/active 和 background/active 全部通过，截图已人工检查；清理退出码 0。报告 `reports/ios-m1-repair-attempt2`，提交前 source_dirty=true；executable SHA-256 `b36bae647786ec066e8bb8b5a6211c93760cc1e272c2d808cf18a07a802f6b68`。本机通过不证明远程故障已解决，须等待新 SHA 的两条必选 CI。
- Commit：`5b6745f43587b2a5bdaf08d4a632f6bdd76a22c7`，`fix(ios): preserve UIKit arguments and verify process lifecycle`；2026-10-28 07:37 +0800 单独 push。远程 feature/kmp 仍只包含该单次新增提交；main 未更改，PR #15 仍 Draft。
- [Push CI 40603368208](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40603368208)、[PR CI 40603374669](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40603374669)：**FAILED（最终）**，两者 API head_sha 为上述修复 SHA。两条 ci/ios 真实 smoke 均 FAILED，其两个 executable 与 110 tests 已 SUCCESS；两条 Android（含四包 dry-run）均 SUCCESS，两条 gate 均 FAILED。不能开始下一功能，仅修复本门禁；未取消任何运行。
- PR test-merge SHA：`e2cfc8d3696510932d8e47ed5ce1c37bfb4c1742`；base SHA：`423b8af13632896b55780783b54b5f720ec4c9dd`；Draft=true。
- 新诊断确定 Push 崩溃原因：[iOS job 122536730496](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40603368208/job/122536730496) 的 stderr 为 Compose `PlistSanityCheck` IllegalStateException：缺少 `CADisableMinimumFrameDurationOnPhone=true`，系统记录 SIGABRT；PID=29371。干净 source、iOS 26.5 / iPhone 17 Pro、Xcode 26.6（17F113）。工件 12779892841 已下载并保留。此为本轮直接证据，不能反推缺少 stderr 的每条旧运行根因。
- PR [iOS job 122536752790](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40603374669/job/122536752790) 也由 stderr 确认相同 PlistSanityCheck 异常；PID=30112。实际 source=e2cfc8d3696510932d8e47ed5ce1c37bfb4c1742、dirty=false；工件 12779654058 已下载保留。
- 已下载两条 Android verification reports 并核验实际 snapshot 报告：push source=5b6745f43587b2a5bdaf08d4a632f6bdd76a22c7，PR source=e2cfc8d3696510932d8e47ed5ce1c37bfb4c1742，均 dirty=false、1.15.10-SNAPSHOT / 180074、测试签名、4 attachments。Android 成功不覆盖 iOS/gate 失败。

## M1-003：满足 Compose iOS plist 契约

只修复已确认的启动崩溃：补充声明式 Info.plist 必需的高刷新率布尔项，保留 Compose 默认严格检查；验证器在创建设备前检查该项。新增可选 `--runtime` 用于准确复验失败 runner 的 iOS 26.5；不传时 CI 仍选择最新可用 iOS，显式 runtime 缺失时失败，不静默回退。依据为官方 UIKit integration / Apple plist 文档；未复制参考实现、未改依赖、版本或 runner。

- 两个 Native target / Simulator 重新打包 / Spotless：SUCCESS（10s，17 tasks 复用已有成功输出）。
- Kotlin verifier `spotlessApply check installDist`：SUCCESS（1m34s），111 tests，0 failure/error/skipped，包含显式系统不可用时拒绝回退。真实已编译应用副本删除该 plist 键后，预检非零退出且没有创建 Simulator/报告目录；键为 false 的副本也被拒绝。未关闭 Compose 严格检查。
- 完整 Android 原 tests/lint/build：SUCCESS（2m52s，315 tasks）；Native/诊断/bytecode/身份/签名/JNI/ABI/16 KiB 与真实四包 snapshot dry-run：SUCCESS，1.15.10-SNAPSHOT / 180074、test signing、source_dirty=true。actionlint、source-policy 与 diff check SUCCESS。
- iOS 26.5 / iPhone 17 Pro：完整 smoke SUCCESS，first/foreground PID=37219、严格 terminate 后 second PID=37340；iOS 27.0 / iPhone 18 Pro：完整 smoke SUCCESS，PID=38277 → 38370。两次均检查真实 Compose 截图、清理退出码 0、无本次设备残留。报告 `reports/ios-m1-plist-26-5` / `reports/ios-m1-plist-27`，source_dirty=true；相同 executable SHA-256 `cfd35fc1acd6137be526c7cccf0451f53c8c6be8934a76df20e93b83cc10303b`。仍须从新提交树执行 push/PR 全部 CI，不能以本机结果代替。
- Commit：`a5a7612a1d7fc528ba6a88b583304e997c78bd46`，`fix(ios): satisfy Compose plist startup requirements`；2026-10-28 08:00 +0800 单独 push。
- [Push CI 40605938631](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40605938631)、[PR CI 40605947634](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40605947634)：**SUCCESS（最终）**，API event 分别 push/pull_request、head_sha 均为上述提交。PR test-merge=`31c900d6187c6e91c1ca50f110ceae2d1e3d718a`，base=`423b8af13632896b55780783b54b5f720ec4c9dd`，Draft=true。两条 android/四包 dry-run、ci/ios、ci/gate 全部真实 SUCCESS 后才允许下一功能。
- PR [ci/ios 122545342014](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40605947634/job/122545342014)：**SUCCESS**。实际工件 12780749443 已下载核验：source=31c900d6187c6e91c1ca50f110ceae2d1e3d718a、dirty=false，iOS 26.5 / iPhone 17 Pro / Xcode 26.6；1.15.10 / 180074、ARM64；前后台 PID=31554，严格终止后第二冷启动 PID=32631，全部 PASS，画面截图已检查，真机字段仍 NOT_VERIFIED。
- Push [ci/ios 122545311598](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40605938631/job/122545311598)：**SUCCESS**。实际工件 12781677121 已下载，source=a5a7612a1d7fc528ba6a88b583304e997c78bd46、dirty=false，iOS 26.5 / iPhone 17 Pro、ARM64、1.15.10 / 180074；PID=34985 → 36137，严格终止与全部生命周期 PASS，截图已检查。
- 两条 Android：[push 122545312282](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40605938631/job/122545312282)、[PR 122545341452](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40605947634/job/122545341452) 均 SUCCESS；总门禁：[push 122553527666](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40605938631/job/122553527666)、[PR 122553188956](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40605947634/job/122553188956) 均 SUCCESS。原 Rust、完整 Android、APK/Native/16 KiB、真实四包 dry-run 与上传全部通过，没有取消或跳过适用检查。
- 两份实际 Android verification reports 已下载，snapshot source 分别匹配 push head / PR test-merge，dirty=false、1.15.10-SNAPSHOT / 180074、test signing、精确 4 attachments；四 APK 报告的 ABI 集合、versionName/versionCode、证书与 SHA-256 字段均已核验。生产签名与加密诊断仅因 feature/PR 不适用而保持 skipped，不算生产路径验收。
- 当前结论：**Kotlin-only iOS 启动切片 GATE_PASSED；M1 整体仍 IN_PROGRESS**。Rust Desktop/iOS FFI、Intel DMG、其他 ARM64 runtime、iOS 真机与 P0 仍未验收。失败历史不改写，M8 前稳定版继续冻结。

## M1-004：真实 Desktop JVM / Rust JNI host 回归

前置 a5a7612a1 的 push/PR 全部门禁与四包 dry-run SUCCESS 后才开始。新增 Kotlin `desktop-native-smoke`，保留 Cargo staticlib/Android 配置，通过独立 cdylib 命令加载现有 Rust Core/JNI；macOS ARM64 与 Linux x64 双 host CI 成为必选，gate 必须同时接受 Android、iOS、整个 Rust host 矩阵 SUCCESS。设计与参考路径/差异/许可处理见 ADR 0002；不是产品 launcher、JRE 包、iOS C ABI 或其他架构验收。

- 本地最终 verifier `spotlessApply check installDist` SUCCESS（1m33s），113 tests、0 failure/error/skipped；新增错误 CPU/格式/截断库断言与三依赖 gate 的非 success 拒绝测试均成功。actionlint、diff check SUCCESS。
- 真实 macOS ARM64 dylib 构建、独立 JDK 25.0.3 JVM 装载 SUCCESS：4,170 项所有权/字节预算断言 + 13 归档样例的抽取/随机访问摘要检查全部通过。`reports/desktop-native-m1-attempt1`，source=a5a7612a1d7fc528ba6a88b583304e997c78bd46、dirty=true；Rust 固定 nightly-2026-08-22，host 构建 7.20s，library SHA-256 `7da6c9ed83dbe35ce01f8ed343e4ad00db1a8861e72f03e0f064b130ec7ac1f1`。
- 先前手工 host probe 只有 4,167 项（没有大文件预算三项），不当作最终 CLI 成功证据；最终确实另行执行 4,170 项。首次只读 Cargo tree 在 sandbox 下载 DNS 失败；授权环境锁定依赖构建成功，未改下载来源或依赖锁。
- 完整 Android 原 tests/lint/build SUCCESS（1m41s，315 tasks）；原 Rust 实现与 Android Native 构建方式未修改。Linux 本地未执行；需新 SHA CI 真实成功后才能标记。
- 原 APK 身份/测试签名/JNI/ABI/16 KiB、Native sources、diagnostics、bytecode、source policy 与真实四 APK snapshot dry-run 全部 SUCCESS（set -e 逐项执行），版本仍 1.15.10-SNAPSHOT / 180074、test signing；本地 dirty source 不替代远程新 SHA。iOS 入口源码未改，CI 仍必须从新提交树重跑两个 target 与真实 smoke。
- Commit：`8f12b329d4d6b2386c8d36b9a59afe4a786dd8b3`，`test(native): gate real desktop JNI archive ownership`；2026-10-28 08:34 +0800 单独 push。
- [Push CI 40609642826](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40609642826)、[PR CI 40609649180](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40609649180)：**SUCCESS（最终）**，API event=push/pull_request、head_sha 均为上述提交。PR test-merge=`99225a2989b0356f285382bfeaee6247e5cf355f`，base=`423b8af13632896b55780783b54b5f720ec4c9dd`，Draft=true。每条运行 Android/四包 dry-run、iOS、Rust host 的 Linux x64/macOS ARM64 两项与 gate 全部真实 SUCCESS 后才进入下一功能。
- 四个真实 host job 均 SUCCESS：[push Linux 122557774341](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40609642826/job/122557774341)、[push macOS 122557774468](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40609642826/job/122557774468)、[PR Linux 122557795941](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40609649180/job/122557795941)、[PR macOS 122557796525](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40609649180/job/122557796525)。四份工件已下载：push source=8f12b329d4d6b2386c8d36b9a59afe4a786dd8b3，PR source=99225a2989b0356f285382bfeaee6247e5cf355f，均 dirty=false；实际 Rust target/JVM CPU 相符，JDK=25.0.4.1、固定 Rust toolchain；4,170 项与 13 样例均 PASS。工件 IDs：12781334054、12782287324、12782925145、12782486658。packaged_runtime / ios_c_abi 明确 NOT_VERIFIED。
- 原 Android：[push 122557773852](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40609642826/job/122557773852)、[PR 122557795940](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40609649180/job/122557795940)；iOS：[push 122557774196](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40609642826/job/122557774196)、[PR 122557795582](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40609649180/job/122557795582)；总门禁：[push 122565545155](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40609642826/job/122565545155)、[PR 122564037470](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40609649180/job/122564037470) 全部 SUCCESS，没有取消或跳过适用检查。
- 已下载两条 Android snapshot 实际报告，source 分别匹配 push/head、PR/test-merge，dirty=false、1.15.10-SNAPSHOT / 180074、测试签名、精确四包；两份 iOS 启动报告亦为干净 source、1.15.10 / 180074、真实 ARM64，PR PID=24260 → 26720、push PID=33246 → 34156，全部生命周期与严格 terminate PASS，PR 画面截图已检查。
- 当前结论：**Desktop JNI host 切片 GATE_PASSED；M1 整体仍 IN_PROGRESS**。iOS Rust C ABI、Intel DMG、其他 ARM64 runtime、真机/P0 与最终 11 包仍未验收。

## M1-005：iOS Kotlin/Native ↔ Rust owned-buffer C ABI

前置 8f12b329d 两套五项门禁及候选四包全部 SUCCESS 后开始。实际复用 EhViewer Core hash，新增有界所有权句柄、复制/释放、错误和 panic 边界；固定 cbindgen 自动生成头/.def/Kotlin 绑定，链接真实 Simulator/device executable。完整设计、安全不变量、参考/差异/许可和明确未验收项见 ADR 0003；不改变 Android JNI/staticlib、稳定版本或 runner。

- Rust C ABI 6/6 SUCCESS（初次12.51s，最终安全契约复验2.30s）；Core 19 unit + 14 integration SUCCESS，原3个在线 ignored 未改；JNI 5/5、fmt与三个 Android target workspace/all-features Clippy `-D warnings` 全部 SUCCESS。最终 C ABI 严格 Clippy 再验 SUCCESS（0.74s）。
- 依赖锁仅新增 cbindgen 0.29.4 build-only 工具及其传递依赖；无既有 crate 版本升级，原三处引用变化是多版本名字消歧。官方 metadata 核查 MPL-2.0；没有复制第三方实现。Rust Apple Simulator release staticlib 实际构建 SUCCESS（18.30s）。
- 初次两个 Apple executable / 生成绑定 / 打包 / lint SUCCESS（45s）。增加实测证据 data class 后原 ktlint 文件命名规则 FAILED（12s）；重命名为 RustInteropEvidence.kt 后同一完整命令 SUCCESS（21s），未抑制 lint。补充 Cargo JSON/诊断保留后再次 SUCCESS（1m44s，复用既有 Native 输出）。
- 最终安全契约更新后两 Apple executable / 打包 / lint SUCCESS（35s）。最终 verifier `check installDist` SUCCESS（114 tests，0 failure/error/skipped）；完整 Android 原 tests/lint/build SUCCESS（3m24s，315 tasks），原 APK/Native/diagnostics/bytecode/JNI/ABI/16 KiB 与真实四包 snapshot dry-run 全部 SUCCESS，1.15.10-SNAPSHOT / 180074、测试签名。Desktop JNI 回归再次实测 4,170 项所有权/字节预算断言 + 13 归档样例 SUCCESS。actionlint / diff check SUCCESS。
- 最终同一 executable SHA-256=`8b85eb438c2b96b93d82c798a72444ce255aa81a715822dfd55ff6c41f83a027` 在 iOS 26.5 / iPhone 17 Pro（PID 70204 → 70296）与 iOS 27.0 / iPhone 18 Pro（PID 71227 → 71510）完整 smoke 均 SUCCESS。每个系统两次冷启动实测结果数均为 [2000,2000]、live=0，原前后台/新 PID/严格 terminate/Compose 画面断言保留；截图已检查，清理退出码0、无本次 Simulator 残留。报告 `reports/ios-capi-final-26-5` / `reports/ios-capi-final-27`；提交前 source_dirty=true，不能替代新 SHA 的干净 CI。
- 提交前审查补充 cinterop task 的 archive 内容输入，避免 Rust 实现变化而头声明不变时复用旧绑定中的静态库。两 Apple target / 打包 / lint 最终复验 SUCCESS（16s），二进制哈希仍与上述两系统实测一致。新 SHA CI 尚未执行，状态 LOCAL_VALIDATION；iOS 真机/完整 Native 归档及其余 M1 spike 不在本切片结论内。
- Commit：`ad68c3c0e89d1509d63c4712b74e5ef1fc2ba16c`，`feat(native): prove owned Rust C ABI from Kotlin iOS`；2026-10-28 09:27 +0800 单独 push。[Push CI 40615138227](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40615138227)、[PR CI 40615142625](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40615142625)：**SUCCESS（最终）**，API event=push/pull_request、head_sha 均为本提交，全部五 job（含双 host 矩阵）SUCCESS 后才进入下一功能。
- 本轮 PR test-merge=`4963a12dd3ed28cb176fe82c0b9c7a1cc7ee4f03`、base=`423b8af13632896b55780783b54b5f720ec4c9dd`，PR #15 仍 Draft。两条 Linux x64 JNI job 已 SUCCESS，工件 12784289643 / 12784788615 已下载，source 分别匹配 head/test-merge、dirty=false、4,170 项与 13 样例 PASS；这不代替尚未完成的其他 job。
- 两条 iOS job 均 SUCCESS：[push 122575742312](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40615138227/job/122575742312)、[PR 122575755825](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40615142625/job/122575755825)。启动工件 12785726312 / 12785821039 已下载核验，source 分别匹配 head/test-merge、dirty=false；iOS 26.5 / iPhone 17 Pro / ARM64，1.15.10 / 180074。Push PID=27371 → 28126、PR PID=28453 → 29262，两次冷启动均各实测2000个结果、live=0，原生命周期/严格 terminate 与 Compose 画面全部 PASS，截图已检查。device 仍为编译链接验证，开发签名真机 NOT_VERIFIED。
- 两份 Cargo 证据工件 12784979183 / 12785466904 已下载：各自两个 Apple target 的当前 staticlib 均实际生成（fresh=false），生成头跨 target、push/PR 一致，声明五个 ABI 函数。四份 Desktop host 工件也已完整核验；macOS 工件 12783819824 / 12785256236，dirty=false、实际 CPU/JVM 匹配、4,170 项与13样例 PASS。
- 原 Android：[push 122575741953](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40615138227/job/122575741953)、[PR 122575756043](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40615142625/job/122575756043) 均 SUCCESS；完整 Gradle tests/lint/build 分别16m22s/17m25s。两条 Rust 日志均证明 C ABI 6/6、Core19+14、JNI5/5、三个 Android target 严格 Clippy 全部 SUCCESS。两份实际 Android 报告工件 12785083856 / 12786240977 已下载：source 匹配 head/test-merge、dirty=false、1.15.10-SNAPSHOT / 180074、测试签名、精确4包；APK SHA-256 与 snapshot 逐 ABI 一致，原 Native/diagnostics/bytecode/16 KiB 检查保留。
- 总 gate：[push 122582776699](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40615138227/job/122582776699)、[PR 122583280880](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40615142625/job/122583280880) 均 SUCCESS，没有取消或跳过任何适用检查。当前结论：**iOS Rust owned-buffer C ABI 切片 GATE_PASSED；M1 整体仍 IN_PROGRESS**。Intel DMG、其他 ARM64 runtime、完整 Native 归档/图片、真机/P0 与最终11包仍未验收。

## M1-006：ARM / Rosetta Intel DMG Spike

前置ad68c3c0e两套全部CI及候选四包SUCCESS后开始。新增独立Kotlin Desktop窗口和包内JVM/Skiko/Rust，固定既有WebP源码、真实jlink/jpackage DMG、Kotlin挂载/临时安装/架构/启动/同库JNI验证器与必选ARM runner Intel门禁。来源、差异、许可、所有本地失败/修复记录见ADR0004。

- 官方校验通过的Intel JDK25.0.5+8在本机ARM/Rosetta真实运行；原JNI cross-link最初因缺WebP失败，sys fork的build=false导致静态环境变量无效；同一固定WebP commit实际交叉编译后成功。手工相对-L路径错误也保留，改绝对路径后真实4,170项+13样例SUCCESS。
- 初次真实DMG build SUCCESS（2m38s），独立包内launcher/JVM/Compose/Rust启动成功。完整安装首次FAILED于GPL提示/stdin关闭；明确输入仓库GPL测试安装确认后继续。第二次FAILED于真实Skiko源JAR同时含ARM64/x64，Compose抽取x64后残留ARM64；官方Maven SHA已核对。保留严格校验、原缓存和许可证，Kotlin构建输出只筛除明确ARM64两项；同版本重建SUCCESS（2m）。
- 筛选后完整DMG smoke SUCCESS：32个Mach-O薄x86_64、JAR残余Native=0、两次包内runtime/Compose各100次Rust Core调用和实际绘制、同库4,170项+13归档样例；DMG SHA=`d2d71da8610fcc1d999c3de98afe2c80ccd746a9d12482d38b91ad488b98e688`，提交前dirty=true。最终每Native动态依赖/环境记录与CI条件重编译复验仍在进行；新SHA尚未提交/运行，不判定GATE_PASSED。
- 原完整Android tests/lint/build SUCCESS（2m51s，319 tasks）；Kotlin verifier114 tests与四依赖严格gate测试SUCCESS。APK/Native/16KiB/真实四包、共享助手ARM host回归及最终DMG另在进行。稳定版仍1.15.10 / 180074；物理Intel硬件、最低OS、签名/公证、Desktop P0与剩余M1风险未验收。
- 最终本地：CI同条件2GiB/单worker强制重编译打包SUCCESS（1m20s，20/20执行）；补真实Intel JDK guard后再验SUCCESS（46s），DMG仍为`091f66865e1f8746562e5e60b19474ee35190954dbf4f0a11e4cdf12662fe7c9`。增强检查曾误将Skiko的LC_ID当作外部依赖，FAILED后按实际otool -D分类修正；最终完整DMG smoke SUCCESS（`reports/intel-dmg-final-installname`）：32个薄x86_64 Native、真实install ID/加载依赖、两次包内runtime/Compose各100次Core调用、同库4,170项+13样例全部通过。
- 最终verifier114 tests / 0失败/错误/跳过 SUCCESS（1m26s）；原APK/Native/diagnostics/bytecode/JNI/ABI/16KiB与真实4包snapshot全部SUCCESS，1.15.10-SNAPSHOT / 180074、测试签名。共享助手ARM host回归4,170项+13样例SUCCESS。根插件/模块变更后原两iOS executable/lint再验SUCCESS（23s），iOS26.5完整smoke SUCCESS：PID12505→12747、每次实测2000个结果、live=0，严格终止/生命周期和清理均通过。所有报告为前置ad68c3c0e + dirty源；新SHA CI尚未执行，状态LOCAL_VALIDATION。
- Commit：`aec6f057fab01c621df6700ee3511221e53a2e03`，`feat(desktop): prove Intel DMG on ARM with Rosetta`；2026-10-28 10:26 +0800单独push。[Push CI40620938493](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40620938493)、[PR CI40620941911](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40620941911) 已触发，当前IN_PROGRESS，等待每条六job及候选四包真实SUCCESS，未判定门禁通过。PR test-merge=`df2155ae99b45c46c02365c3b32b48931d8d9351`，base仍423b8af13632896b55780783b54b5f720ec4c9dd，PR #15仍Draft。
- 两条Linux x64 JNI job已SUCCESS；工件12787452131/12787292364由连接器取得后，下载ZIP先与官方artifact.digest逐一核对SHA-256，再解包核验：source分别为aec6f057f/df2155ae99、dirty=false、实际x86_64 target，4,170项+13样例PASS。其他job尚未全部完成，不能进入下一功能。只读补查的最低OS元数据不一致另记ADR0004，未把默认10.13宣称为支持范围。
- 本轮出现失败：[push macOS ARM64 JNI job122594518391](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40620938493/job/122594518391) **FAILED**，02:45:55Z日志为`Command timed out: cargo`（现有15分钟上限），随后工件上传因报告目录无文件FAILED。超时分支删除捕获文件且未以CommandFailure返回，故缺少Cargo原始输出，不能判断下载/锁等待/编译根因。PR对应job122594529765 SUCCESS；PR六job/gate均已SUCCESS，仍不能替代push失败。暂停新增功能，只修复失败诊断和重新验证；现有超时上限与所有断言保持不变。
- aec6f057f最终：Push40620938493 **FAILED**（严格gate122606350905 FAILED），PR40620941911 **SUCCESS**（gate122603028414 SUCCESS），API event/head_sha均已核对。两条真实Intel DMG job均SUCCESS：[push122594518202](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40620938493/job/122594518202)、[PR122594529684](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40620941911/job/122594529684)。整轮仍FAILED，不进入下一功能。
- PR实际DMG工件12788297900已下载、ZIP与官方artifact.digest一致，包自身SHA=`ccfd68c0b375b70a823c860402446eb20b674ef1cf094452e08b824bc6b48b07`与报告一致：source=df2155ae99、dirty=false，macOS26.6.2/Xcode26.6/IntelJDK25.0.5、物理ARM/Rosetta=1，32个薄x86_64 Native及加载依赖、两次包内runtime/Compose各100次实测Core调用/实际frame、同库4,170项+13样例全部PASS。Push工件12788837227已保留下载标识，待包完整下载核验；不拿PR工件替代其实际内容。
- 其余成功工件也已下载并校验官方ZIP摘要：两份Android报告12787129839/12788271018，source=head/test-merge、dirty=false、1.15.10-SNAPSHOT/180074、测试签名、精确四包及逐ABI哈希一致；两份iOS启动12788161039/12787179295，source匹配、dirty=false、1.15.10/180074、iOS26.5，push PID24268→24916、PR PID27957→29172，各两次2000/live0、原全部生命周期PASS。PR macOS ARM64 host工件12788435415亦为干净正确源、4,170项+13样例PASS；两份Apple Rust构建证据12788066122/12787324131已下载。

## M1-007：超时输出保留与自有进程树清理（该轮FAILED，后续M1-008修复）

仅修复M1-006失败门禁暴露的诊断缺陷，不新增平台功能。超时现在携带原始部分输出和明确标记返回CommandFailure，既有Cargo日志捕获可留存失败工件；超时前取自有后代进程快照，全部强制终止后结束父进程。超时仍失败，原5/15分钟等限制没有放宽，也不重试/忽略编译错误。

- 新增真实父/子JVM回归：父进程记录子PID，两者输出后等待；10秒到达后验证错误类型/超时标记/部分输出，并确认子进程退出，失败时也清理本次fixture。源码均Kotlin，无Shell业务实现。工具全量测试本地SUCCESS（1m14s，115 tests，0失败/错误/跳过）；Android、两Native host/DMG回归正收集。根因仍未证实，不宣称Cargo超时本身已解决。
- 最终工具`spotlessApply check installDist` SUCCESS（1m13s），115 tests/0失败/错误/跳过；真实超时进程树测试10.033s成功，超时信息记录精确ISO Duration。原完整Android tests/lint/build SUCCESS（1m32s，319 tasks，44执行/275复用），原Native/diagnostics/bytecode/APK身份/签名/JNI/ABI/16KiB与真实精确四包snapshot dry-run均SUCCESS，1.15.10-SNAPSHOT/180074、测试签名。
- 成功路径实际回归：ARM host4,170项+13样例PASS；Intel DMG32个Native/依赖/两次包内runtime/Compose各100次Core调用与同库4,170项+13样例PASS；iOS26.5完整smoke PASS，PID28837→28934，各两次2000/live0、前后台/严格终止/新PID和清理全部成功。仅修复超时诊断/进程清理，app/native业务、依赖、版本、workflow与所有时间上限未改变。报告均为aec6f057f + dirty源，下一修复SHA仍须独立全量push/PR CI。
- Commit：`df31e91338bf31794ca28677e3a415652d2d723c`，`fix(ci): retain timed-out output and terminate descendants`；2026-10-28单独push。[Push CI40624994746](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40624994746)、[PR CI40625000720](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40625000720) 当前IN_PROGRESS，未判定修复门禁通过；PR test-merge=`375c5089adf564e708314959dc05bd6a8a9ba787`，base423b8af13632896b55780783b54b5f720ec4c9dd、Draft=true。
- 前置失败轮次的push DMG工件12788837227现已完整下载，ZIP官方摘要核验成功；包自身SHA=`7c574e19cc10be3d015a224141f83bc0ecc16109d0e320b7fe1fcb444526f305`与报告一致。source=aec6f057f、dirty=false、macOS26.6.2/IntelJDK25.0.5，32个x86_64 Native/两次包内runtime/Compose各100次Core调用/同库4,170项+13样例均PASS。补齐此成功证据不改变该轮push/gate FAILED结论。
- df31e9133 的四个实际 host job 均 SUCCESS：push Linux122607666513 / macOS ARM64122607666517、PR Linux122607685742 / macOS ARM64122607685298。工件12788947682、12788703033、12789396006、12788893785已下载且ZIP SHA-256与官方digest逐一一致；实际报告source分别为df31e9133 / 375c5089a、dirty=false、CPU/固定Rust对应，4,170项所有权/预算断言与13归档样例全部PASS。该成功结果不能代替尚在运行的Android/iOS/DMG及总gate，也不能证明前次Cargo超时根因。
- 本轮 Push iOS122607666550 **FAILED**：03:27:19Z真实日志显示Cargo在获取原WebP子模块`https://chromium.googlesource.com/webm/libwebp`时出现三次SecureTransport连接关闭警告，达到原15分钟Rust Apple构建上限后失败；尚未构建/启动Simulator。原始Cargo工件12789646918已下载并核验官方ZIP摘要，诊断确实留存；Android122607666250已SUCCESS。整轮不能通过，不新增功能。此证据只解释本轮iOS依赖获取失败，不反推缺少输出的前次macOS host超时。

## M1-008：使用系统 Git 获取锁定 Cargo 依赖（GATE_PASSED）

仅针对M1-007实际iOS SecureTransport故障，将CI的`CARGO_NET_GIT_FETCH_WITH_CLI`设为true。采用[Cargo官方配置](https://doc.rust-lang.org/cargo/reference/config.html#netgit-fetch-with-cli)，更换下载后端；Cargo.lock、依赖版本/原始URL/子模块commit、TLS验证、原全部时间上限与测试断言保持不变。没有新增重试、镜像、忽略失败或生产权限；未修改releases工作流或平台业务源码。

- 全新临时`CARGO_HOME`中，`cargo fetch --locked --target aarch64-apple-ios-sim`使用该配置真实SUCCESS，24.60秒。重新读取实际checkout HEAD为锁文件`138c7114eb7cc9c3d0a6c5d074d1dda30550b765`，WebP子模块HEAD为原`a4d7a715337ded4451fec90ff8ce79728e04126c`；两者tracked内容无变化（Cargo自身生成未跟踪`.cargo-ok`标记）。未复用已有Git缓存，锁文件diff为空。该本机证据不能保证远程网络成功，下一SHA必须独立全量复验。
- 本地actionlint SUCCESS；完整工具、Android及Apple Native回归正在执行，尚未提交/推送。M1整体仍IN_PROGRESS。
- 本地完整Android原tests/lint/build SUCCESS（1m36s，319 tasks：44执行/275复用）；115项工具测试/0失败错误跳过 SUCCESS（1m52s）。原Native/diagnostics/bytecode/身份/测试签名/JNI/ABI/16KiB与精确四包snapshot预检全部SUCCESS，1.15.10-SNAPSHOT/180074。使用全新Cargo缓存强制重建两Apple Rust target SUCCESS（42s，6/6执行），实际Kotlin/Native两executable/Simulator打包 SUCCESS（23s）。iOS26.5完整smoke SUCCESS，PID40806→40928，各2000次实测调用/live0，前后台/严格终止/新PID与清理均成功；ARM host4,170项+13样例SUCCESS。上述报告source=df31e9133、dirty=true；Intel JVM工具强制执行及真实DMG重建正继续，未以命中测试缓存当作该JVM重新执行。
- df31e9133旧轮次成功工件补验：两份Android报告12789428272/12789344299已核对官方ZIP摘要与干净push/test-merge源码、1.15.10-SNAPSHOT/180074、测试签名、精确四ABI/hash；PR iOS12789254918为干净375c5089a、iOS26.5/iPhone17Pro/1.15.10/180074，PID29803→30786，两次2000/live0及完整生命周期PASS。Push iOS实际失败仍保留，不用PR成功覆盖。
- Intel最终本地验证：真实x86_64 JDK25.0.5以`--rerun-tasks`执行115项工具测试SUCCESS（4m32s，0失败/错误/跳过，真实进程树超时测试10.055s）；完整DMG20/20任务强制执行SUCCESS（1m42s）。新包SHA=`be3c76caffedcc841ec66a65952c18e3c425f4ea13a3763e81fbaa534725a817`，32个薄x86_64 Native/加载依赖、两次实际包内JVM/Compose各100次Core调用/1个实测frame，以及包内同库4,170项+13归档样例全部PASS。报告为df31e9133+dirty源，只作为提交前回归。actionlint/diff check SUCCESS；稳定版1.15.10/180074、main及原releases工作流无变化。
- Commit：`b876b67c52de198764623efda143af0b3f72d540`，`fix(ci): fetch locked Cargo sources with system Git`；2026-10-28 11:40 +0800单独push。[Push CI40627981508](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40627981508)、[PR CI40627987574](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40627987574)已触发，当前PENDING；不能开始下一功能。PR test-merge=`c237aaee28bb7925d9abb850cd9edc3f832c7c72`，base仍423b8af13632896b55780783b54b5f720ec4c9dd，Draft=true。旧df31e9133运行未取消，继续收集剩余job最终结论。
- df31e9133旧轮次已最终收齐：Push40624994746 **FAILED**（iOS122607666550/gate122618006275失败），PR40625000720 **SUCCESS**（六job含gate122617534581全部SUCCESS）；API事件/head均再次核对。两条Intel job122607666509/122607685607均SUCCESS；PR真实DMG工件12790506031已下载并核验官方ZIP摘要、包自身SHA、干净375c5089a源码与完整32Native/两次包内runtime/同库4,170项+13样例PASS；push工件12790491829正在下载。旧运行没有取消；新修复两事件均已开始，只等待其全量结果。
- df31e9133两份实际DMG现均已完整下载并验证官方ZIP digest、包SHA与报告一致：push工件12790491829 / SHA=`440b8992b54c916a4f3d885230c5a8b327a50c7c2c99228e9e19ae6182440c70`，PR工件12790506031 / SHA=`c349fd5b4bfabbb247a693b0d088efa8a9bce772d2eb3ccbbd75d66d060ae285`。各自source匹配head/test-merge、dirty=false，完整32个x86_64 Native/依赖、两次实际包内runtime/Compose各100次Core调用、同库4,170项+13样例全部PASS；旧push整体仍FAILED。
- b876b67c5当前四个host job均已SUCCESS：push Linux122618031311 / macOS ARM64 122618030990，PR Linux122617570437 / macOS ARM64 122617570354。工件12790646462、12789919500、12790805035、12789809799全部下载并核对官方ZIP摘要及实际报告：source=b876b67c5/c237aaee2、dirty=false，实际JVM/Native CPU对应、固定Rust，4,170项+13样例均PASS。其余Android/iOS/DMG/总gate尚未完成，不提前宣告整轮成功。
- 新PR [iOS122617570446](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40627987574/job/122617570446)已SUCCESS。实际启动工件12790768192与Rust Apple构建工件12791206297已下载、ZIP摘要与官方digest一致；source=c237aaee2、dirty=false，iOS26.5/iPhone17Pro、1.15.10/180074，PID41467→42156，两次2000/live0、全部生命周期和清理PASS，截图已实际检查。两target生成头SHA均为`c922f090b75be5774d570b23a2c3bae8429453d5be453a836eaf95a67303345a`。完整job日志确认`CARGO_NET_GIT_FETCH_WITH_CLI=true`，原Native构建12m26s与工具测试1m58s成功；本轮未见原SecureTransport警告。其他适用job及push仍需成功。
- 新push [iOS122618031395](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40627981508/job/122618031395)亦SUCCESS，实际启动12791021458与Rust Apple12790916602工件已下载并核验官方ZIP摘要。source=b876b67c5、dirty=false，iOS26.5/iPhone17Pro/1.15.10/180074，PID35659→36411，两次2000/live0、完整生命周期/清理PASS。两Rust target实际build-finished=true，生成头与PR完全一致；截图工件保留。只剩Android/Intel DMG及总gate待整轮验收，尚不判定GATE_PASSED。
- 新PR40627987574已最终SUCCESS，六job/gate122624461323全部成功，API event=pull_request/head=b876b67c5已核对。PR Intel job122617570482真实DMG工件12790674177已下载、官方ZIP摘要/UDIF/包自身SHA核验一致（`b69d678b8d97ece5d914fdc49e3d113072b4de19087fbd3bd4e887b4540132cf`），source=c237aaee2、dirty=false、32个薄x86_64 Native/全部依赖、两次包内runtime/Compose各100次Core调用与同库4,170项+13样例PASS。物理Intel/最低OS/P0仍未验收；不能替代尚在运行的push Intel/gate。
- 两事件Android job122618031289/122617570250均SUCCESS；实际报告工件12790694072/12791086533已下载、ZIP官方摘要一致。各自source匹配b876b67c5/c237aaee2、dirty=false、1.15.10-SNAPSHOT/180074、测试签名、精确4 ABI，逐包SHA与snapshot manifest完全相符。原Rust/Android/Native/16KiB等检查均保持，生产签名路径在feature/PR不适用，未声称验收生产材料。
- 最终：`b876b67c52de198764623efda143af0b3f72d540` 的 [Push40627981508](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40627981508) 与 [PR40627987574](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40627987574) **SUCCESS**，API status=completed、event=push/pull_request、head_sha均精确匹配。两条六job全部SUCCESS；push gate122627073844 / PR gate122624461323均真实SUCCESS。PR test-merge=c237aaee28bb7925d9abb850cd9edc3f832c7c72，base423b8af13632896b55780783b54b5f720ec4c9dd、Draft=true。
- Push Intel job122618031378亦已SUCCESS；实际DMG工件12791117743完整下载、官方ZIP摘要/包自身SHA=`bd89eec51c24b0e9a585e0b74ed82bdc3666d06dcfebe653696c0b09b90e16c4`与报告一致。source=b876b67c5、dirty=false、32个x86_64 Native/依赖、两次包内runtime/Compose各100次Core调用、同库4,170项+13样例全部PASS。两事件全部Android/四包、iOS/C ABI、双host、真实Intel DMG与总gate、实际工件均验收后，才允许下一独立改动。
- 结论：本轮修复及ARM→Intel/Rosetta技术切片 **GATE_PASSED**；M1整体仍IN_PROGRESS。后续需修复已确认的Native许可证材料未入包问题，并继续物理Intel、Linux aarch64/Windows ARM64 runtime证明。最低OS、完整P0、开发签名设备和11包仍未验收；此前失败历史不改写，也不据本轮成功反推旧macOS Cargo超时根因。

## M1-009：将直接Native许可证材料保留到安装后的应用（GATE_PASSED）

仅在b876b67c5全量双事件/工件SUCCESS写入本台账和同一Draft PR后开始。实际旧`.app`没有静态WebP材料，Skiko runtime JAR也无LICENSE/NOTICE；独立修复将六份材料及来源元数据放入`Contents/app/resources/licenses`，保留原DMG GPL提示。验证器逐份验证原文摘要，完整依赖许可审计仍显式NOT_VERIFIED；无版本/业务逻辑/runner/工作流变更。

- Skiko0.144.6官方commit9a5b398bb2044fff7e7a84fbfd6f4b803e4427c0的LICENSE/NOTICE已独立读取并复核Git tree/blob；新增资源原文Git blob分别精确等于261eeb9e9f8b2b4b0d119366dda99c6fd7d35c64/bc646e35dcbf08a6eb0404c88864cf26b55f9581。Apache-2.0 LICENSE与上游NOTICE原文保留，不是Animeko/AGPL实现复用。WebP COPYING/PATENTS/AUTHORS来自原固定commit及已校验归档；未升级依赖。
- Desktop Kotlin DSL格式化SUCCESS（11s）；全量工具测试和真实Intel DMG强制重建正在执行，旧包缺材料拒绝/新包完整安装启动尚待验证，未提交/推送本切片。
- 最终本地工具格式/编译/check/installDist SUCCESS（1m16s），115 tests/0失败错误跳过；真实旧DMG的负向检查按预期拒绝（`Missing installed Native notice: EhViewer/LICENSE.txt`），临时镜像卸载成功。新DMG20/20任务强制执行SUCCESS（1m22s），实际安装后六份材料SHA全部匹配，32个Native/依赖、两次包内JVM/Compose各100次Core调用/1个实测frame、同库4,170项+13归档样例全部PASS。包SHA=`6893a8ff55f9c23f8860cfecb1422b9e3f4e43882e5612301bbf36a6c48bbdde`，报告`reports/notices-intel-dmg`。
- 原完整Android tests/lint/build SUCCESS（1m24s，319 tasks，44执行/275复用），原Native/诊断/Java17/身份/测试签名/JNI/ABI/16KiB和真实四包snapshot全部SUCCESS，1.15.10-SNAPSHOT/180074。ARM host4,170项+13样例PASS；两iOS target/打包/lint SUCCESS（8s，5执行/21复用），iOS26.5完整smoke PASS，PID61480→61576，各2000/live0、完整生命周期/清理成功。以上均b876b67c5+dirty本地源，不替代下一SHA干净CI。
- 新资源暂存后source-policy/architecture、diff whitespace与actionlint均SUCCESS；Git index两许可证blob精确匹配官方值。路径限定的`-text`仅保持已审计资源原文字节，不改变源码语言识别或任何Android门槛。workflow/稳定版本/业务源码/依赖版本保持；新SHA仍必须单独全量push/PR CI验收。
- Commit：`3c8a2f2853d1f9d8806ab6666a15dfaad46e2177`，`fix(desktop): retain native notices in installed app`；2026-10-28 12:26 +0800单独push。[Push CI40632345976](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40632345976)、[PR CI40632352805](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40632352805)已触发，当前IN_PROGRESS/QUEUED，尚不宣告本切片通过。PR test-merge=`d3b422a5bd0ee9046244f4950e3050205225bf92`，base仍423b8af13632896b55780783b54b5f720ec4c9dd，Draft=true。下一功能等待本SHA全部适用CI和真实工件SUCCESS。
- 本轮四个host job均SUCCESS：push Linux122631394058 / macOS ARM64 122631393011，PR Linux122631416551 / macOS ARM64 122631416434。工件12791953477、12791808072、12791693186、12792645771已全部下载并核对官方ZIP摘要，实际报告source=3c8a2f285/d3b422a5b、dirty=false、JVM/Native CPU相符，4,170项+13样例均PASS。Android/iOS/真实DMG及总gate尚未完成，不据host结果提前判断本轮通过。
- 本轮两Android job122631394090/122631417073均SUCCESS；实际报告12792224299/12792109370已完整下载、官方ZIP摘要与干净3c8a2f285/d3b422a5b源码/1.15.10-SNAPSHOT/180074/测试签名/精确四ABI及逐包manifest SHA均核对成功。iOS/DMG/总gate仍须完成，未视为整轮通过。
- 两iOS实际启动工件12792607362/12792828320及Rust Apple构建12792891538/12793155705已下载并核对官方ZIP摘要：干净3c8a2f285/d3b422a5b源、iOS26.5/iPhone17Pro、1.15.10/180074；push PID27246→28418，PR PID26518→27152，均两次2000/live0、完整生命周期及清理PASS。四份生成头SHA均为c922f090b75be5774d570b23a2c3bae8429453d5be453a836eaf95a67303345a。尚待真实DMG/许可证摘要/总gate完成，不将局部成功当作整轮验收。
- 最终：[Push40632345976](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40632345976) / [PR40632352805](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40632352805)均**SUCCESS**，API status=completed/event=push/pull_request/head_sha=3c8a2f2853d1f9d8806ab6666a15dfaad46e2177全部核对。双事件六job均SUCCESS，gate122638569086/122639290532均真实SUCCESS；base423b8af13632896b55780783b54b5f720ec4c9dd、test-merge d3b422a5bd0ee9046244f4950e3050205225bf92、Draft=true。
- 两条Intel job122631403116/122631416255亦均SUCCESS。实际完整DMG工件12792697772/12792429857已下载并校验官方ZIP digest、包自身SHA与报告一致：push=`6d1b6ae6a8b8ce80b93653bf7b3a61429432b9e2562286445d55395f219c3d97`，PR=`6719e9f5275d1c9eb6da78ff9ef59d7f1ca159fc0d40de567e1be805b1014529`。各自干净源码精确匹配head/test-merge、1.15.10、真实32个x86_64 Native/依赖、六份安装后许可证原文摘要、两次包内runtime/Compose各100次Core调用、同库4,170项+13样例全部PASS。
- 本切片**GATE_PASSED**，先将以上证据记录同一PR后才开始下一独立改动。M1整体仍IN_PROGRESS；物理Intel、Linux/Windows ARM64 runtime、最低OS、完整P0/依赖许可证与SBOM、开发签名设备、11包和M2–M8仍未验收，稳定版本继续冻结。

## M1-010：原生Intel消费ARM构建的同一DMG（GATE_PASSED）

前置3c8a2f285全部双事件门禁/实际工件SUCCESS写ledger和同一Draft PR后，新增独立必选消费job及Kotlin命令。产品仍只在macos-latest ARM64构建；macos-26-intel只下载同一运行的同SHA DMG/生产者报告，验证干净精确源码、ARM/Rosetta生产环境及实际包摘要，再执行完整原安装/许可证/Native/两次包内runtime/同库回归。原releases矩阵/业务/版本不变，CI仍无生产密钥和只读权限。设计与官方标签依据见ADR0004。

- 本机实测x64 Rosetta下硬件标志仍1/1、CPU品牌Apple M4；未知OID用sysctl -i为空且退出0，其他权限/命令失败不忽略。新原生Intel模式对真实ARM/Rosetta按预期拒绝，错误明确且未创建安装报告目录；该负向不算原生Intel通过。
- 本地工具格式/编译/check/installDist SUCCESS（1m18s），116 tests/0失败错误跳过；新增来源边界用例拒绝错误源码SHA/脏源/失败生产者/错误主机或Rosetta/目标/包摘要。Gate新增第五依赖，35次门禁状态检查均按规则验证，原测试不删减。完整Android和原ARM/Rosetta路径回归正在进行；真正原生Intel阳性尚待新SHA必选CI，未提交/推送该切片。
- 最终本地原完整Android tests/lint/build SUCCESS（1m29s，319 tasks，44执行/275复用）；原Native/diagnostics/Java17/身份/测试签名/JNI/ABI/16KiB及真实四包snapshot均SUCCESS，1.15.10-SNAPSHOT/180074。原产品DMG构建/lint SUCCESS（27s，20任务复用已验证输出），产品源码未改；新验证器对实际包重跑原ARM路径SUCCESS，明确记录arm64/rosetta/Apple M4/1/1，六份材料、32 Native/依赖、两次包内runtime/Compose各100次Core调用/1个frame、同库4,170项+13样例全部通过。报告source=3c8a2f285+dirty，本机不报告Intel阳性。
- 新增测试job只下载并校验ARM工件，编译验证工具；没有产品/Rust构建步骤。消费报告将构建工具来源标为已校验的ARM报告，保留真实消费机环境。原release四段/矩阵/生产材料隔离与正式版本未改，actionlint和diff检查SUCCESS。新SHA必须独立push并通过新增必选Intel job及全部原push/PR门禁，才可继续。
- Commit：`be27ac67ad90ea189ef1a5b951d42d432052cdb9`，`test(desktop): verify ARM-built DMG on native Intel`；2026-10-28 13:06 +0800单独push。[Push CI40636080918](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40636080918)、[PR CI40636086745](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40636086745)已触发，尚待原全部检查与新增必选Intel消费job真实成功。PR test-merge=`a339f0e832b59c88ff4db40ef82c0ed329822f88`，base仍423b8af13632896b55780783b54b5f720ec4c9dd，Draft=true。当前不声明原生Intel已通过，不开始下一功能。
- 本轮四host实际工件12793995864/12793772371/12793797141/12793703772已下载并核对官方ZIP摘要，source=be27ac67a/a339f0e83、dirty=false，JVM/Native实际CPU与固定Rust对应，4,170项+13样例均PASS。对应push Linux122643250163/macOS ARM64 122643251137、PR Linux122643268248/macOS ARM64 122643268272的源/结果均保留；这不是原生Intel消费阳性。
- Push iOS122643249691已SUCCESS，真实启动12794255823及Rust Apple12794161160工件已下载/官方ZIP摘要一致：干净be27ac67a、iOS26.5/iPhone17Pro/1.15.10/180074，PID18825→19326，两次2000/live0、完整生命周期/清理PASS，两target生成头SHA均c922f090b75be5774d570b23a2c3bae8429453d5be453a836eaf95a67303345a。其余原检查/新增Intel消费者/总gate仍需真实成功。
- PR iOS实际启动12794491014及Rust Apple12794885130工件亦已下载、官方ZIP摘要一致：干净a339f0e83、同runtime/device/冻结版本，PID39030→40384，两次2000/live0、完整生命周期/清理PASS，生成头与push一致。新原生Intel消费阳性仍未取得，不据既有平台成功提前放行。
- 本轮两Android实际报告12794575832/12794198282已完整下载，官方ZIP摘要及干净be27ac67a/a339f0e83源、1.15.10-SNAPSHOT/180074、测试签名、精确四ABI与逐包manifest SHA全部相符。原保障保留；ARM生产者、原生Intel消费者及总gate尚未完成，不提前放行。
- PR40636086745已最终**SUCCESS**，API status=completed/event=pull_request/head_sha=be27ac67ad90ea189ef1a5b951d42d432052cdb9已核对。原生Intel job122652206101实际SUCCESS，消费工件12795245201和ARM生产工件12794279110均已下载、官方ZIP摘要核验一致。干净test-merge a339f0e83、Intel Core i7-8700B/macOS26.6.1，ARM/Rosetta标志均为空（未知OID），execution_mode=native-intel；包SHA=`81efe58232c97918ece4577758a0c7cabd539bf4765df50e62562c8422038406`与ARM生产者完全一致，生产报告SHA=`26edacae622996f0d4c07c7f4fddae456e536d2f8476ca5ac4e22e7be48aaeb3`也逐字节匹配。六份材料、32 Native/依赖、两次包内runtime/Compose各100次Core调用/1个frame、同库4,170项+13样例全部PASS；构建工具来源明确为ARM报告。首次取得原生Intel阳性，仍须push完整七job成功，不开始下一功能。
- 最终：[Push40636080918](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40636080918) / [PR40636086745](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40636086745)均**SUCCESS**，API status=completed/event=push/pull_request/head_sha=be27ac67ad90ea189ef1a5b951d42d432052cdb9精确核对；两事件全部七job真实SUCCESS，gate122657460955/122653921983均成功。base/test-merge/Draft状态再次核对不变。
- Push原生Intel job122655755954已SUCCESS，实际消费工件12795202332与ARM工件12794819020完整下载、官方ZIP摘要校验一致。干净be27ac67a、同Intel Core i7-8700B/macOS26.6.1，真实native-intel、无ARM/Rosetta；同包SHA=`0d831ba72df66d0fca0f1428d509657af3f0488471a873aad2a1e5b20b632479`，ARM报告SHA=`e38a9a9fedd6e99abe79409314f8cd1e1192134e56edc23972f6bb2c2796ff08`均匹配。两个事件的生产/消费32个Native逐路径/摘要/依赖、六份材料、两次包内runtime/100次Core/实际frame及4,170项+13样例逐项比对全部相等；产品仅ARM构建，Intel没有产品/Rust构建。
- 结论：本切片**GATE_PASSED**，原生Intel的当前系统技术证明已完成；最低OS、签名/公证、完整P0仍NOT_VERIFIED。先把最终结果同步同一Draft PR，再开始M1 Linux aarch64 runtime独立切片。M1整体仍IN_PROGRESS，Windows ARM64已有真实Core编译失败待修复，不提前进入M2或升版。

## M1-011：Linux aarch64包内JVM/Skiko/Rust运行时（LOCAL_VALIDATION）

前置be27ac67a全部双事件七job/gate及实际工件SUCCESS写入ledger和同一Draft PR后开始。仅扩展原Desktop探针到原生Linux ARM64应用目录，复用原窗口、完整JNI/Core和六份材料；Kotlin检查ELF、动态依赖、固定Skiko、实际包内JVM/窗口/调用数与同库回归。新增指定ubuntu-24.04-arm必选job，原检查保留，gate六依赖/八job全部必须success。原releases/Android业务/稳定版本不变；设计、来源与未验收边界见ADR0005。

- 本地工具格式/编译/check/installDist SUCCESS（1m29s），117 tests/0失败错误跳过；ELF错误CPU/格式/字节序/类型/截断按预期拒绝。新Linux命令在实际macOS上按预期拒绝且未创建报告目录，该负向不算Linux运行阳性。Linux ARM64完整JNI/Core `cargo check --locked --features jvm --target aarch64-unknown-linux-gnu` SUCCESS（2.95s），仅编译检查，不算链接/运行通过。
- 原Intel DMG真实重建/lint SUCCESS（1m54s，22 tasks，12执行/10复用），原macOS真实启动/归档回归和完整Android检查正在执行。Linux ARM64第三方Skiko原生文件的实际NEEDED含libGL/libX11/fontconfig/libEGL/libstdc++/libm/libc和AArch64 loader，CI显式安装对应图形依赖，未假定只靠JAR即可启动。尚未提交/推送，不宣称Linux阳性。
- 原Intel DMG实际安装/启动回归已SUCCESS，SHA=`d8a8510de5cabf1bcc5d33c81634d73e2e6780035b33365cd5a5a59337614e44`，32 Native/依赖、六份材料、两次包内JVM/Compose各100次Core/1个frame与4,170项+13样例均通过，实际运行版本1.15.10。原完整Android tests/lint/build SUCCESS（1m46s，319 tasks，46执行/273复用），原APK/Native/diagnostics/Java17/JNI/ABI/16KiB及真实精确四包snapshot全部SUCCESS，1.15.10-SNAPSHOT/180074/测试签名。
- 官方JDK元数据实际确认25.0.5+8.0.LTS/Linux/aarch64存在，未把元数据或交叉编译当作运行成功。审查补充对目录符号链接也执行出界拒绝，工具最终复验正在执行。以上仍为be27ac67a+dirty本地源，下一SHA必须独立push并取得双事件全部八job和实际Linux阳性工件，才能继续。
- 最终符号链接边界更新后工具格式/编译/check/installDist全部SUCCESS，117 tests/0失败错误跳过；暂存源码语言/architecture、actionlint和diff检查均SUCCESS。原Intel/Android/四包实际回归证据不变；没有修改Cargo.lock、原releases.yml、Android业务或稳定版本。LOCAL_VALIDATION完成，Linux真实阳性仍须新SHA必选CI，不能据本机编译/负向检查放行。
- Commit：`1026cc1d6c816d12e0261db8cb6a892c9b51956a`，`feat(desktop): add native Linux ARM64 runtime probe`；2026-10-28 14:04 +0800单独push。[Push40641664966](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40641664966)、[PR40641670321](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40641670321)已触发，当前IN_PROGRESS/QUEUED。PR test-merge=`67109295fefcb06b56854d6404aa396dc1d86d8d`，base仍423b8af13632896b55780783b54b5f720ec4c9dd，Draft=true。等待双事件全部八job/gate及实际Linux工件成功，失败时只修复，不开始Windows功能。
- 本轮两Linux ARM64 job [push122660985727](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40641664966/job/122660985727) / [PR122661001729](https://github.com/ZhanZiyuan/EhViewer/actions/runs/40641670321/job/122661001729)均**FAILED**。原始日志精确定位根Gradle配置`:app`时`SDK location not found`；该ARM镜像没有Android SDK，尚未执行Desktop Native构建或运行smoke。SDK/JDK/工具链不能仅由其他runner推断。随后上传无文件亦失败，是未生成输出的次生错误。原失败保留、旧运行不取消，暂停全部新平台功能，只进行构建入口与诊断修复。
- 旧轮次四host已SUCCESS；实际工件12796117458/12795838078/12795982439/12795119810全部下载并核对官方ZIP摘要：source=1026cc1d6/67109295f、dirty=false，实际Native/JVM CPU相符，4,170项+13样例全部PASS。此成功不覆盖Linux ARM64配置失败，其他旧job继续收集。

## M1-012：Desktop独立构建入口与失败日志保留（LOCAL_VALIDATION）

仅修复M1-011真实Linux runner缺SDK导致的配置失败。`app/desktop/settings.gradle.kts`引用同一仓库version catalog和现有官方仓库，以同一build.gradle.kts/源码作为独立Desktop构建；资源、Native输入、版本/GPL路径锚定模块实际位置。Linux调用`-p app/desktop`，不配置Android项目；原Android全量CI命令、原根settings/插件/验收、其他平台job均不改。不是伪造SDK或跳过Android测试。

- Linux Gradle原始输出用tee保留到独立报告文件，明确set -euo pipefail；构建失败仍须非零，运行证据目录仍须全新。新增实际执行工作流脚本的0/93退出fixture，检查日志保留和失败不被tee吞掉。原Native/GUI/归档/架构断言不改，Linux阳性仍未取得；本机无SDK环境的独立Intel构建及完整回归正在执行。
- 取消ANDROID_HOME/ANDROID_SDK_ROOT后，独立入口projects/packageDmg/lint真实SUCCESS（1m39s，19/19任务实际执行）；工程报告只有desktop、无任何子项目。新DMG完整安装/启动/32 Native与依赖/六份材料/两次包内JVM/Compose各100次Core/1个frame及同库4,170项+13样例全部PASS，SHA=`80962d9712959d983c15cfc6fb6b8fc1457a440ad0615d6bcb7c2254256eb9ba`。原根工程Android全量命令亦SUCCESS（2m3s，319 tasks，46执行/273复用）；工具最终含失败注入回归及APK预检仍在执行。
- 最终工具格式/编译/check/installDist SUCCESS（2m7s），118 tests/0失败错误跳过；真实0/93退出fixture证明日志保留且tee不吞掉Gradle失败。原Native/diagnostics/Java17/APK身份/测试签名/JNI/ABI/16KiB与精确4包snapshot均SUCCESS，1.15.10-SNAPSHOT/180074。actionlint/diff检查通过。以上为1026cc1d6+dirty源，仅修复既有失败；Linux运行阳性仍未取得，下一修复SHA必须双事件全量复验。
