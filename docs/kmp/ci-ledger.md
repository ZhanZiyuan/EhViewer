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
