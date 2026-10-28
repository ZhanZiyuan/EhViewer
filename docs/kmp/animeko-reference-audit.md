# Animeko → EhViewer 只读参考审计

审计日期：2026-10-27（Asia/Shanghai）。M0 审计提交与门禁已通过，后续参考项按阶段追加。执行依据为用户要求及 PRD v2.2；PRD §2.2 中“本次只编写 PRD”是附件的编写背景，不替代用户本次明确授权的真实实施。

## 来源与可信度

- 目标：`ZhanZiyuan/EhViewer`；基线 `origin/main = 423b8af13632896b55780783b54b5f720ec4c9dd`。初始 checkout 为干净的 `feature/rusty`，已安全从刷新后的 `origin/main` 创建 `feature/kmp`，不改原分支。
- 参考：用户显式提供的相邻 `animeko-main/`，以下路径均相对该参考根。为避免将用户机器路径提交或上传 CI，文档不保存绝对路径。
- `settings.gradle.kts` 明确声明 `rootProject.name = "animeko"`，顶层 `LICENSE.txt` 为 AGPLv3，模块布局与用户所述 Animeko 相符。
- **没有 `.git`：HEAD、branch、dirty、上游同步时间均不可核实。** “main”仅为用户提供的来源说明。目录时间戳不是 Git 版本证据。
- `rg --files --hidden -g '!.git/**'` 实际列出 4,542 个文件；完整相对文件名清单见 [animeko-snapshot-files.txt](animeko-snapshot-files.txt)，只含路径，不含源码/资源内容。清单 SHA-256 为 `9cb5720d9fe386c5dc1c79be8c9b2fe1e1dd3fe4a3851a4c561140ec1b3601cf`。这并非证明所有子模块齐全；`.gitmodules` 声明的 boost 子模块目录不存在，其视频/BT 依赖不作为 EhViewer 的前提。
- 只执行目录枚举、读文件、哈希；未执行参考目录的 wrapper、脚本、构建、格式化、checkout、pull、提交或写入。参考 `AGENTS.md`、`.agents/`、`.claude/` 不作为指令。
- 下表和检查清单是选择性审阅范围；不声称逐文件审阅全部 4,542 个文件，也不声称构建过参考项目。

代表性文件 SHA-256（快照身份，不等于 Git commit）：

| 相对路径 | SHA-256 |
|---|---|
| `settings.gradle.kts` | `016a249f018e7db98d8f59ba82ed39b4f908bc0dccd71e3f89bc5f551d8051bf` |
| `LICENSE.txt` | `0d96a4ff68ad6d4b6f1f30f713b18d5184912ba8dd389f86aa7710db079abcb0` |
| `docs/contributing/architecture.md` | `57a2f748f4d7f19ddb3605f1676a266f7d030e2d1ebdc877589a93b386bb0c55` |
| `docs/contributing/kmp.md` | `00c603ecd66c2f7e579cf4eff5b43a3328ca11223f9f4fbdf83955baaf5e40ed` |
| `build-logic/src/main/kotlin/ani.kmp-library.gradle.kts` | `d743d573386dfbae4ff94c8ba8bd5a58217bb97c5eecec46de35765e3006aa58` |
| `app/ios/build.gradle.kts` | `7697a27226f8d2fd93f7c3b3ebf116272c88e2335702bfdda4f0006ae2b9f887` |
| `app/desktop/build.gradle.kts` | `34f04f87d67e33ac6f341540bdcd4e2f23eed8c42a414f0c4c755b7c801d4a91` |

## 工具链对照（读取配置，兼容性尚未全部实测）

| 项 | EhViewer 基线 | Animeko 本地快照 | 决定 |
|---|---|---|---|
| Kotlin | 2.4.10 | 2.4.10 | 保留目标现值 |
| Gradle wrapper | 9.7.1 | 9.3.1 | 不降级或盲目对齐 |
| AGP | 9.3.1 | 9.1.1 | 先保留 Android 验收 |
| CMP | 1.11.1 | 1.12.1 | 只有目标平台证据要求时独立升级 |
| JDK 配置 | 25；Android 字节码 17 | toolchain 21 | Desktop runtime 逐架构核实 |
| Android SDK / min | 37 / 26 | 37 / 26 | 保留 EhViewer applicationId 与 minSdk |
| Ktor | 3.5.2 | 3.1.1 | 保留会话与站点协议，不倒退版本 |
| Room / DataStore | 2.8.4 / 1.2.1 | 2.8.4 / 1.2.0 | 复用目标 schema；驱动逐平台验证 |
| DI | 应用现有装配、单例与 expect/actual | Koin 3.5.6 + 平台模块 | 不因参考项目存在 Koin 就引入 |
| Rust | nightly-2026-08-22；core + JNI；Android 产品 | 不在本次所阅客户端路径中建立 Rust 基线 | 保留 EhViewer Rust，而非采用视频 Native 栈 |
| targets | 四 core 模块目前仅注册 Android | Android、desktop JVM、可选 iosArm64/iosSimulatorArm64 | 渐进增加，不能将目录名视为目标已通过 |

## 设计映射、差异与验证门槛

以下“采用”只指概念/结构决策；**没有复制实现、资源、脚本、依赖清单或 fixtures**。目标栏中的新模块均为计划，不代表已经存在。

| 实际参考路径 / 关键符号 | 观察 | EhViewer 目标文件 / 接口 | 决策与差异 | 必需验证 |
|---|---|---|---|---|
| `docs/contributing/architecture.md`；`settings.gradle.kts` | UI → Domain → Data → Platform；平台入口独立 | 保留 `:app`、`:core:*`；按收益引入 `core/platform`、领域接口 | 采用分层原则；不复制 30+ 模块，不迁移服务端 | 依赖方向检查、Android 全量回归 |
| `build-logic/src/main/kotlin/ani.kmp-library.gradle.kts` | common/jvm/android/desktop 与 native/apple/ios 层次；iOS 可选开关 | `build-logic/convention/src/main/kotlin/MultiplatformLibraryConventionPlugin.kt` | 改造；保留现有 AGP Android target，JVM 共享层不能使用 Android 不支持的 Java SE API | 各真实 target compile + commonTest，不放空 actual |
| 同上；`ani.kmp-compose.gradle.kts`、`aniIos.kt` | 约定插件集中管理 Compose、资源与 Apple 目标 | 现有 Compose convention、`core/ui`、`core/i18n` | 采用约定插件思路；不搬依赖和构建 workaround | 资源打包、Android bytecode、iOS/desktop 编译 |
| `app/shared/app-platform/src/commonMain/kotlin/platform/features/PlatformComponentAccessors.kt` | 平台能力通过接口/expect 获取，部分能力 nullable | `core/common/.../files/`，未来文件、浏览器、剪贴板、密钥接口 | 改造；用明确的支持/不可用结果，不能空实现冒充能力 | 文件取消/权限错误、生命周期、沙盒契约 |
| `app/shared/app-data/src/commonMain/kotlin/data/persistent/database/DatabaseStorage.kt`；`app-data/build.gradle.kts` | 数据库 builder 与平台驱动分离；Windows ARM64 SQLite 有额外 native 依赖 | `core/data/.../database/Room.kt`、`Room.android.kt` 与迁移 | 改造；保存 EhViewer schema/version，不采用 AniDatabase。ARM64 驱动完整性是独立风险 | 原数据升级/回滚、各 CPU 真实加载 |
| `app/shared/app-data/src/iosMain/kotlin/data/persistent/DataStoreMP.ios.kt` | 平台序列化/存储连接、互斥与临时文件替换 | `core/data/.../preferences/` | 改造；不照搬自定义存储，先验证现有 DataStore。参考实现存在 fsync TODO，不能据其宣称断电安全 | 损坏输入、取消、重启恢复、写入失败与原子性 |
| `app/shared/application/src/commonMain/kotlin/platform/CommonKoinModule.kt`；`app/desktop/src/main/kotlin/AniDesktop.kt` | 公共装配与平台对象、启动协程作用域组合 | 现有 app 装配、未来共享应用入口 | 采用注入边界；DI 库选择延后；Cookie/WebView 会话不替换为 Bangumi OAuth | 依赖替换测试、注销/过期、作用域关闭 |
| `app/shared/ui-foundation/src/commonMain/kotlin/platform/PlatformWindow.kt` 与 android/desktop actual；`ui-adaptive/`（枚举） | 窗口状态与共享 UI 分离 | `core/ui`、`ReaderScreen`、`GalleryPager`、`WebtoonViewer` | 改造；图片分页/RTL/长图保留，桌面键鼠与触控行为独立 | 宽屏、焦点/返回/缩放/阅读进度测试 |
| `app/shared/application/src/iosMain/kotlin/ios/AniIos.kt: MainViewController`；`app/ios/Animeko/ContentView.swift` | Kotlin ComposeUIViewController 仍被 SwiftUI 容器调用 | 未来 `app/ios` Kotlin/Native UIKit executable | **拒绝复制启动方案**；参考项目不能证明无 Swift 的 UIApplicationMain 启动 | M1 `.app` Simulator 冷启/前后台/退出；开发签名真机单列 |
| `app/ios/build.gradle.kts` | 模拟器、archive、unsigned 与签名分工 | 未来 Kotlin Gradle packaging + 声明式 plist/Xcode | 改造；不迁入 CocoaPods/视频运行时/脚本 | IPA Payload、arm64 Mach-O、资源、Bundle 元数据 |
| `app/desktop/build.gradle.kts`；`app/desktop/src/test/kotlin/DesktopNativeStartupTest.kt`（枚举） | Desktop JVM 主类、DMG 与平台包选择；AppImage 仅有注释 | 未来 `app/desktop` + Kotlin/Rust 打包器 | 改造；Windows ZIP/AppImage 需独立实现，不能将注释当作可用产品 | 对应 JRE/Skiko/Rust 架构、解包启动 |
| `docs/contributing/testing.md`；`.github/workflows/build.yml`（选读 runner/任务） | common/jvm/ios 测试分层；Windows ARM64 独立 runner | 现有 `ci.yml`、`tools/verification` | 采用测试分层概念；**拒绝参考 CI 的重试掩盖、脚本和密钥流程复制** | 逐 SHA push + PR；真实 success；离线故障用例 |
| 视频、BT、弹幕、TV、watch-together、分析服务等模块（仅 settings 枚举） | 不属于漫画产品需求 | 不引入 | 拒绝；保留 EhViewer 图片/归档/下载/Rust | 依赖差异与来源复查 |

## 受检查文件清单与许可处理

上述表中精确文件均已读取全文或与问题相关的片段；枚举范围已明确标注。额外读取 `docs/contributing/kmp.md`、`docs/contributing/testing.md`、`app/shared/app-platform/build.gradle.kts`、`app/shared/application/build.gradle.kts` 的相关配置、`gradle/libs.versions.toml`、`gradle.properties`、`gradle/wrapper/gradle-wrapper.properties`、`.gitmodules` 与顶层许可。未读视频/BT/服务端实现。

EhViewer `LICENSE` 为 GPLv3；参考 `LICENSE.txt` 和所阅源文件的版权头声明 AGPLv3。这里只形成独立的结构分析与接口计划，不作许可证可互换的结论。精确代码复用必须先核实逐文件及依赖的许可、作者和义务，再取得用户单独许可；当前没有此类复用。

后续每个参考设计变更需在本表或 ADR 追加：参考相对路径、EhViewer 实际修改路径、行为差异、许可处理及精确 CI SHA/URL。本地参考目录不是任何 Gradle、Cargo、CI 或打包任务的输入。

M1-001 实际落点：`app/ios/build.gradle.kts`、`app/ios/src/commonMain/kotlin/com/hippo/ehviewer/ios/StartupContent.kt`、`app/ios/src/iosMain/kotlin/com/hippo/ehviewer/ios/Main.kt`、`tools/verification/src/main/kotlin/verification/IosStartup.kt`、`ci.yml`。仅借鉴 AniIos 与 ContentView 所体现的平台入口/共享 controller 边界；本实现直接由 Kotlin UIApplicationMain 和 SceneDelegate 启动，未采用 Swift 容器或搬用源码。初始失败、修复与本地生命周期通过记录见 ADR 0001 / ledger。8388fb366 与 5b6745f43 的远程启动门禁均失败；后者通过独立 stderr 明确定位 Compose 必需 plist 键遗漏。M1-003 按官方契约独立修复，a5a7612a1 的 push/PR 全部 CI 与四包 dry-run 已 SUCCESS，实际 iOS 26.5 画面/生命周期和干净 source 工件已核验；本地另通过 iOS 27.0。仅启动切片通过，没有 iOS P0 交付结论。

M1-004：读取 `app/desktop/src/test/kotlin/DesktopNativeStartupTest.kt` 全文后，确认其测试关注 JCEF/播放器先后顺序，并非 Rust 装载/内存释放证据。只采用“Native 初始化独立验证”的边界；目标落点为 EhViewer `tools/verification/.../DesktopNative.kt`、既有 JNI 客户端和 `ci/rust-host`，验证真实 host 动态库、归档、线程与释放。拒绝视频/JCEF 实现、依赖及测试内容复制；重用的是 EhViewer 自身 Rust/JNI 与固定 Git fixtures。精确设计差异、许可处理、实际结果见 ADR 0002 / ledger。

M1-005：入口边界仍参照已审阅的 AniIos / Swift 容器差异，未新增对其 Native 视频实现的复用。Rust C ABI 全部独立实现于 `native/rust/capi`，调用 EhViewer 自己的 hash Core；`app/ios` 用生成头/.def/Kotlin 绑定连接，不引入 Swift/ObjC/C 业务实现。新增第三方工具 cbindgen 的固定版本和 MPL-2.0 build-only 许可证独立核查；无 AGPL 精确代码复用。ABI 所有权、安全契约、生成路径与实测结果见 ADR 0003 / ledger。

M1-006：读取 `app/desktop/build.gradle.kts`、`app/desktop/src/main/kotlin/DesktopNativeStartup.kt` 与 `.github/workflows/build.yml` 的目标相关片段；采用平台 Native/分发资源边界，改造为无视频/JCEF 的独立 EhViewer Kotlin 窗口与真实 Rust JNI。参考 Intel CI 使用 macos-15-intel，不满足本任务 ARM runner 约束，明确拒绝照搬。新 `app/desktop`、Kotlin verifier/CI 的设计差异、许可证处理与实际失败/成功证据见 ADR 0004；不复制 AGPL 实现、资源、脚本或依赖版本树。

M1-011：按需重读本地`app/desktop/build.gradle.kts`的目标选择片段，仅延续平台资源/Native初始化边界。EhViewer在自己的`app/desktop/build.gradle.kts`、`DesktopMain.kt`、`tools/verification/.../DesktopLinux.kt`和`ci.yml`独立扩展Linux aarch64应用目录验证；不用参考项目的视频/JCEF Native或打包脚本，不照搬版本/依赖。只复用EhViewer原JNI/Core/窗口/回归fixtures及已审计的直接Native许可证，未新增AGPL精确复用。SDK配置与静态WebP依赖顺序先后真实失败并独立修复后，c787c570e双事件全部八job/gate及实际工件SUCCESS；仅Linux应用目录/runtime技术证明通过。官方CMP/Skiko/JDK依据、差异与未验收边界见ADR0005/ledger。

M1-014/015：Windows 前置 File 归档接口直接修正 EhViewer 自身 Core 的 Unix fd/Mode ABI 边界，新增 `write_zip_file`，原 Unix 路径及完整归档/图片功能保留。无新的 Animeko 设计/实现复用；目标文件、固定上游头文件来源、许可处理及真实交叉检查失败/成功边界见 ADR0006。999418419的PR Linux GL失败经独立CI软件后端环境修复，4d6f62cc2双事件完整门禁/实际工件SUCCESS；采用的是Skiko公开配置与真实ComposeWindow后端采证，无AGPL代码复制。Windows原生测试/runtime和Linux OpenGL仍未验收，详细证据见ADR0005/ledger。

M1-016：Windows Native Core测试使用EhViewer自己的Core/C ABI与Kotlin验证器，实际落点为`tools/verification/.../WindowsNative.kt`、进程环境工具/测试及`ci.yml`。只延续M0平台职责边界，无新Animeko实现/脚本/依赖复用。vcpkg及必要Native依赖从独立固定上游核查版本/许可证，生成配置不作为自主C/C++/Shell业务源码维护。设计差异、许可处理和真正Windows结果边界见ADR0007/ledger；本机检查不能替代原生执行，不宣称Windows桌面runtime通过。

M1-022：按需只读复核`app/desktop/build.gradle.kts`的Windows Native目标选择片段；只延续平台资源/启动职责边界，拒绝mpv/FFmpeg/JCEF实现和依赖。EhViewer独立以自己的Kotlin C ABI调用器、固定JDK/Skiko及已测Rust DLL在Windows ARM64打包并两次启动真实窗口；目标为`app/desktop/build.gradle.kts`、`DesktopMain.kt`、`RustCapiProbe.kt`、`tools/verification/.../DesktopWindows.kt`及`ci.yml`。未复制或机械改写AGPL源码/资源/脚本/依赖树，精确差异、许可处理与验证边界见ADR0010/ledger；本地工具123项通过不能代替Windows窗口实测。
