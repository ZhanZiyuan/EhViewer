# M0 工程基线

2026-10-27；基线 `423b8af13632896b55780783b54b5f720ec4c9dd`。M0 首个提交的本地与 push/PR CI、四包候选预检均已通过，见 ledger；后续平台能力仍按各阶段实测。

## Git、版本与证据

EhViewer remote 为 `https://github.com/ZhanZiyuan/EhViewer.git`，初始工作树干净。`origin/main` 刷新后建立 `feature/kmp`；`main`、`feature/rusty` 未修改。没有既存的 `feature/kmp` 远程分支或对应 PR（初次查询）。

已复核 `app/build.gradle.kts`：`releaseVersion = "1.15.10"`、`versionCode = 180074`、`applicationId = "moe.tarsin.ehviewer"`；SDK 37 / minSdk 26，NDK 29.0.14206865，build-tools 37.0.0。M0–M7 冻结这些正式版本值，不新增 `1.16.0` 应用版本或 tag。

远程 main 历史基线证据：

- [CI push SUCCESS](https://github.com/ZhanZiyuan/EhViewer/actions/runs/39199763664)。
- [Release SUCCESS](https://github.com/ZhanZiyuan/EhViewer/actions/runs/39201331638)；现存正式版本为 `v1.15.10`。历史成功不代替新提交测试。
- M0 初查远程 `v1.16.0` tag 与 Release API 均返回 HTTP 404（不存在）。M8 冻结前必须重新查询并校验已分发 APK 的最大 versionCode；不能据当前 Gradle 常量直接保证未来版本号可用。

## 真实源码与迁移边界

`git ls-files` 基线：390 `.kt`、12 `.kts`、38 `.rs`、63 XML、23 JSON、6 YAML；无已跟踪自主 Java/Swift/ObjC/C/C++ 应用源文件。声明式/资源/历史工具仍有 Gradle wrapper (`gradlew`、`.bat`、wrapper jar)、CMakeLists、第三方补丁、ProGuard、TOML、LICENSE/NOTICE 等；不会删除它们来美化语言统计。

| 模块 | 当前事实 | 风险 / 迁移点 |
|---|---|---|
| `:app` | Android UI、MainActivity、EhEngine、SpiderQueen、DownloadManager、ReaderScreen/GalleryPager/WebtoonViewer、PageLoader | HTTP/调度/阅读状态与 Android 生命周期、文件及后台服务逐步解耦 |
| `:core:common` | commonMain 21 文件、androidMain 4、commonTest 1；已有 ArchiveCatalog 与模型 | Android 文件系统/path actual 尚无 Desktop/iOS 对应实现 |
| `:core:data` | commonMain 36、androidMain 4；Room、DataStore、EhCookieStore、Ktor core | CookieManager 依赖 Android WebView；Room AndroidSQLiteDriver、appCtx 与路径需要平台工厂；KSP 仅 Android |
| `:core:ui` | commonMain 56、androidMain 2；共享组件、图标与主题 | 现有页面主体仍在 app；输入/安全区域/窗口能力须分离 |
| `:core:i18n` | commonMain 22（Kotlin/资源合计），Moko | iOS/desktop 资源生成及包内查找待测 |
| convention | 已用 KMP Android 插件、jvm 层次，但只注册 Android 产品 target | 不是五平台已接入；source-policy 还明确禁止 non-Android target，需要在 M1 有阶段合同的变更中扩展 |
| Rust | `ehviewer_core` 与根 JNI staticlib 分离；jvm feature 可用于宿主回归 | 正式产品仍只有 Android；没有 iOS C ABI、桌面动态库发布契约或跨平台所有权测试 |

`core/ui` 现有 `androidx/compose/...` 源文件含 Android Open Source Project 版权头，是既有 fork，迁移保留其声明并另核许可。四 core 的 host test task 存在不代表都有测试：本次执行 common 有测试，data/i18n/ui 为原有 NO-SOURCE；M3 必须补业务契约与失败路径覆盖，不能以任务存在声称覆盖完备。

Rust 已有 ZIP/RAR/7z/TAR 处理、GIF/WebP、解析与排序、只读 lease、池容量与输入上限。Android CMake/Corrosion 负责第三方 libarchive/liblzma/Nettle/WebP，保留全部固定来源和补丁；第三方源码/编译产物中的 C/C++ 不等于自主维护业务代码。Apple 静态链接、Windows fd/系统接口、编解码器与许可逐目标核验，不能假定 POSIX 代码可直接跑 Windows。

原 `native/third-party/CMakeLists.txt` 的版本锁定：xz 5.8.3 (`4b73f2ec…`)、libarchive 3.8.7 (`ded82291…`)、WebP 1.6.0 (`4fa21912…`)、Corrosion 0.6.1 (`3725e651…`)；Nettle 4.0 tarball SHA-256 `3addbc00da01846b232fb3bc453538ea5468da43033f21bb345cb1e9073f5094`。Cargo 的 tl/libwebp-sys2 git 依赖由现有 Cargo.lock 固定。此处是来源/版本盘点；第三方逐文件许可、静态链接义务和最终 SBOM 仍待 M4/M8 核验，不能把主项目 GPL 声明当作全部依赖审计已完成。

## CI 与 Release 审计

原 `ci.yml` 为一个 Android job，同时执行 Rust fmt/Core/JNI/三 Android target clippy、verification tests/installDist/architecture、convention check、spotless、app/core 单测、lint、Debug/Release/benchmark 构建、JNI/ABI/16 KiB/诊断/bytecode 验证，以及四 APK snapshot。

发现：生产 signing restore、诊断保留把所有 `push` 视为可信；feature push 也会尝试生产凭据。首个安全修复把生产路径限制为 main push/manual，feature/PR 强制现有测试签名 snapshot 路径；不削减 Android 检查。`feature/kmp` push/PR 不自动取消，增加 always-run `ci/gate`，Android job 必须 success。

原 `releases.yml` 保留 `prepare → build[4 ABI] → package → publish`：原签名、每 ABI 加密诊断、source/tag 校验、严格四 APK、SHA-256、draft 续传及冲突保护。它的原手工 dry-run 仍要求生产密钥，**不能作为 feature 分支预检**；首个修复禁止 feature 手工进入该生产 workflow。M0 的无生产密钥候选打包在 CI snapshot 步骤完成，M2 才拆为独立、稳定命名的阶段 checks/dry-run；M6 扩展原 releases 到 11/11，保留历史 Android-only 版本语义。

M0 暂只有 Android 为产品 target；shared/Rust/source-policy/四 APK 候选检查仍由原 Android job 承载。新增 iOS/desktop 尚未接入，状态为 **NOT_IMPLEMENTED / NOT_VERIFIED**，不是 skipped-success。完整稳定 check 拆分和仓库 Ruleset 仍待 M2，不声明已满足所有后期 gate。

## 本机能力与尚缺证据

本机 Apple ARM64；JDK 25.0.3、Xcode 27.1 (27A9275)、固定 Rust nightly 已安装，Android SDK 与 Homebrew libarchive/webp 存在。实际构建和测试结论逐次记录在 [ci-ledger.md](ci-ledger.md)。GitHub 连接器已验证身份与仓库权限；沙盒内 gh 的认证/DNS 失败，经授权的沙盒外只读查询成功。

Android 真机升级/性能基线、完整本地 Android build、iOS Simulator/开发签名真机、macOS Intel Rosetta DMG、Windows/Linux 两架构启动、11 包 manifest 仍需实测。没有相应证据时禁止把 M0 或后续阶段标记 GATE_PASSED。
