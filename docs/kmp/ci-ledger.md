# 逐 SHA CI / CD 证据

M0 / IN_PROGRESS。仅 `feature/kmp` → `main` 的同一 Draft PR；不改 Ready、不合并、不创建 tag/Release。稳定版本冻结 `1.15.10 / 180074`，M8 才独立准备 `1.16.0`。

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
- 新 commit SHA / push 时间 / PR / push run / PR run：**PENDING**，推送后追加。
- 本地 Kotlin verification `check installDist`：**SUCCESS**（最终变更 1m15s，105 tests；新增 trust/gate tests 3/3、无 skipped/failure/error）；Rust Core **SUCCESS**：19 unit + 14 archive integration，3 个既有依赖在线服务的测试保持原有 ignored（没有新增忽略）。
- Rust JNI **SUCCESS**：5/5；Rust fmt **SUCCESS**；原 3 Android targets 严格 Clippy `-D warnings` **SUCCESS**。
- `actionlint` 两个 workflow、`git diff --check`、verification `architecture` **SUCCESS**（124 Kotlin 文件，Kotlin/Rust policy PASS）。首次 actionlint 误在 `native/rust` 调用相对路径失败，改从仓库根运行成功，未修改检查规则。
- 本地完整 Android tests/lint/build 首次：**FAILED**（13m29s，295 tasks）。失败为 `:app:buildCMakeDebug[arm64-v8a]` 的 Nettle 4.0 tarball 下载：原两个 URL 均超过现有 120s，总文件 2,630,735 bytes。不是新增 Kotlin/Rust 编译错误；原始证据在本地忽略目录 `app/build/intermediates/cxx/Debug/3n73415c/logs/arm64-v8a/build_stdout_ehviewer.txt`。保留失败记录，先修复依赖获取再重跑，不修改版本、来源或 checksum。
- 修复方式：其他 ABI 已完成下载的同版本 tarball 实测 SHA-256 等于原 CMake 固定值 `3addbc00da01846b232fb3bc453538ea5468da43033f21bb345cb1e9073f5094`；仅复用到本地忽略的 `.cxx/*/*/*/nettle-prefix/src/` 构建缓存。失败的残缺文件哈希不同，未作为有效输入。CMake 自身仍再次验证原 checksum；完整 Android 命令重跑 **SUCCESS**（1m08s，307 tasks：31 executed、276 up-to-date），未删除或跳过任何要求的任务。完整原始重跑日志暂存本机临时目录；不上传含机器路径的日志。
- 环境盘点：ADB 无设备；Simulator runtimes iOS 26.3/26.4/26.5/27.0；本机 `arch -x86_64 uname -m` 返回 x86_64，仅证明 Rosetta 基础命令可执行，不代表 Intel JVM/Skiko/Rust/DMG 通过。Android 进程采样显示 Gradle 在下载依赖，未给出 Android 成功结论。
- 最终本地 Android/JVM 实际测试 25/25（app 22 + common 3）；data/i18n/ui 原有 test task 为 NO-SOURCE，未宣称具备业务测试。Native source、diagnostics、Java 17 bytecode、Release SNAPSHOT 测试签名 APK 与 Debug APK 的身份/JNI/ABI/16 KiB 校验全部 **SUCCESS**。
- 无密钥本地候选打包 **SUCCESS**：实际生成 `release-output/kmp-m0-001-snapshot` 四 APK 与 manifest，version `1.15.10-SNAPSHOT`、versionCode `180074`、`production_signature_required=false`。此为提交前脏工作树本地验证，不能作为新 SHA 的干净 CI 工件；远程必须从提交树重新构建验证。
- 当前门禁：**NOT_PASSED**；没有任何新平台验收结论。CI / dry-run 未成功不能进入 M1。

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
