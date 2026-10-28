# 现代化路线与阶段门禁

状态：M0 / GATE_PASSED，M1 / IN_PROGRESS，2026-10-28。已交付可审计基线和安全逐提交入口，之后严格 M1 → M8。版本到 M8 冻结提交之前维持 `1.15.10 / 180074`。

当前技术证明：Kotlin-only iOS/实际C ABI、Linux x64/macOS ARM64 host JNI、ARM→Intel DMG/Rosetta及原生Intel同包消费、Linux aarch64包内JVM/Skiko/Rust实际软件绘制均已通过各自逐SHA门禁。4a14bd69d双事件完整九job/gate与全部实物证据SUCCESS；旧失败均保留。Windows双事件各39项原生Core/C ABI测试、10 DLL/3测试EXE实际ARM64 PE和全部摘要已独立核验；实际桌面运行时尚未实现，M1未完成。Linux OpenGL/硬件加速另列M5未验收风险，不以CPU软件绘制替代该结论。

| 顺序 | 现有问题与独立工作包 | 参考 / EhViewer 落点 | 收益与退出准则 | 风险、回滚 |
|---|---|---|---|---|
| M0 / P0 | 本地参考缺 Git；原 feature push 触及生产签名 | reference audit；`ci.yml`、`releases.yml`、verification trust tests | 来源/许可映射可审阅；生产材料 main-only；保留 Android tests/build；单 Draft PR 与逐 SHA ledger | 初始 SHA CI 未成功即阻塞；修复正常新提交，不改历史 |
| M1 / P0 | Kotlin-only iOS 可启动性未证明 | Animeko AniIos/ContentView 仅识别差异；新 Kotlin UIKit entry + plist | Simulator `.app` 真实启动、生命周期；不是 framework-only；无 Swift/ObjC 实现 | 工具链/UIApplicationMain/资源失败记录完整日志；不放宽语言限制 |
| M1 / P0 | Desktop/iOS Rust 未形成产品 FFI | 复用目标 `native/rust/core`、JNI；独立 C ABI ADR | host JNI 与 simulator C ABI 调用/释放/错误用例通过 | 第三方链接、panic、所有权；保留现有 JNI ABI，禁止空桥接 |
| M1 / P0 | macOS Intel 在 ARM runner 打 DMG；ARM64 桌面运行时未知 | 参考 desktop build 组织；独立打包 spike | Rosetta + x86_64 JDK/Skiko/Rust/launcher；真实架构与启动证据；Linux/Windows ARM64 native 检查 | 不可行时 BLOCKED，先请求允许 Intel runner；不擅换 runner |
| M2 / P0 | 单 job 状态不足以证明分平台门禁；生产 dry-run 不可用于 feature | 原 workflow/verification，参考测试分层 | `ci/android-regression/shared-kmp/rust-native/desktop/ios/release-dry-run/source-policy/gate` 按已接入阶段真实 success；显式尚未接入清单 | 保留每个 Android 检查；Ruleset 变更单独核验现有规则；不能以 neutral/skipped 通过 |
| M3 / P0 | common 声明与 Android actual 混合；数据库/会话单例 | Animeko builder/平台层；现有 `core/common`、`core/data` | 逐个接口/源集，Cookie/网络错误/存储迁移成功/失败/取消自动测试 | 先契约再独立实现；不得清库或迁入 OAuth 业务 |
| M3 / P1 | 依赖兼容与资源不明 | 两项目版本对照、现有 convention | 仅必要升级独立提交，已有 target 全编译；依赖来源/许可清单 | 不捆绑 CMP/AGP/Ktor 大升级；回滚单个变更 |
| M4 / P0 | SpiderQueen/下载状态与 Android 后台/文件耦合；Native 目标不足 | EhViewer 原调度与归档核心；Animeko 只参考状态分层 | 下载暂停/恢复/取消/重试/重启；恶意归档/lease/多线程/预算测试 | RSS 与缓存预算分别测量；保留 16 KiB、JNI 签名与超时限制 |
| M5 / P0 | 阅读器仍在 Android app；桌面输入/窗口缺失 | 参考 ui-foundation/PlatformWindow；原 ReaderScreen/PageLoader | 共享画面与阅读状态；Android 体验回归；三桌面真实窗口/键鼠/离线导入 | 分页/RTL/长图/缩放和 Coil 解码性能有基线再迁移 |
| M6 / P0 | 原 Release 仅四 APK | 在原 releases 四段结构扩展；Kotlin/Rust verifier/packaging | 精确 11 包、同版本/SHA、CPU/格式/入口/runtime/哈希；失败全或无 | AppImage 与 ZIP 独立构建，不改扩展名；不泄漏诊断/签名材料 |
| M7 / P0 | iOS P0/Keychain/沙盒/后台恢复未实现 | 参考平台边界，独立 UIKit/Native 适配 | F01–F10 成功/失败/取消；Simulator + 开发签名真机 | unsigned IPA 不声称可直接安装；后台受限应可恢复 |
| M8 / P0 | 全平台验收、版本/来源/发布合同最终冻结 | ledger、功能/性能/许可、单一版本权威 | 前一 HEAD 全绿且 11 目标已验；独立 `chore(release): prepare v1.16.0 across five platforms`；再全量 dry-run | 检查 tag/Release/versionCode 冲突；仍 Draft，不创建正式 tag/Release，不自行合并 |

每个工作包：一个可验证改动 → 本地 tests/lint/build → 一次 commit → 单独 push → 等待精确 SHA 的适用 push/PR/dry-run 全部 success → 写 ledger 与同 PR → 下一项。失败、取消、缺失、未知和未完成均不允许继续功能开发；只能修复门禁并保留失败历史。

每次采用参考设计在 reference audit/ADR 记录实际参考路径、目标路径、差异、许可证处理与验证结果。所有新/重写自主实现只使用 Kotlin/Rust；声明式配置和第三方/生成产物单独登记。CI 不依赖本地 Animeko。

## 官方资料核对与当前限制

- [GitHub runner labels](https://docs.github.com/en/actions/how-tos/write-workflows/choose-where-workflows-run/choose-the-runner-for-a-job)：实际 runner 的 OS/CPU 必须在 job 开始核验；按 PRD 固定 `ubuntu-latest`、`ubuntu-24.04-arm`、`macos-latest`、`windows-latest`、`windows-11-arm`，不以名字替代运行证据。
- [Compose native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)：官方包型没有 AppImage/portable ZIP；需 Kotlin/Rust 独立封装与 smoke，jlink runtime 不能遗漏模块。
- [Kotlin C interop](https://kotlinlang.org/docs/native-c-interop.html)：可生成 C 绑定；这不证明 Rust ABI/所有权已正确。
- [CMP entry points](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-entry-points.html)：提供 UIViewController 集成示例；不证明 Kotlin-only iOS executable 已构建、签名和启动。
- [GitHub required checks](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks)：平台保护本身可能接受 skipped/neutral；本项目 gate 只接受真实 success，并分别核对 PR head 与 test-merge SHA。新 gate 的本地用例已经验证 failure/cancelled/skipped/neutral/pending/空状态均失败。
- Apple code-signature 文档在线入口可访问，但本次文本读取只返回 JavaScript 提示，Markdown 链接读取失败；尚未据此声称完成 Apple 签名规范核验，M1/M7 需补充可读官方证据和实测。

M0 未建立 Release 性能对照；任何“性能提升”或“无回退”均不得由代码审阅代替。设备、签名和非宿主平台测试条件逐项列为阻塞，不能用 Android 编译通过代替五平台验收。

2026-10-28 本机只读条件盘点：沙箱内 `xcrun devicectl list devices --timeout 10` 超时，不能据此认定无设备；在可访问 CoreDevice 服务的权限环境以同一命令成功列出设备，所有记录的 Reality 均为 simulated，没有真实设备。`security find-identity -v -p codesigning` 在两种环境均返回 0 valid identities。未导出密钥、签名或安装应用，未把设备标识写入文档。维护者随后明确没有 Apple 开发者账号或签名，要求继续构建原合同中的 unsigned IPA。缺签名不阻止 unsigned 构建与交付；开发签名真机验收保留 NOT_VERIFIED，不能用模拟器结果冒充。iPhone 与 iPad 必须分别记录模拟器验收。

## 2026-10-28 维护者补充要求

- 四份语言 README（根英文、简体中文、繁体中文、日文）的 Thanks/感谢/感謝列表末尾添加 `[Animeko](https://github.com/open-ani/animeko/)`。独立提交47ebcc959已通过双事件全部九job/gate与全部实物核验，GATE_PASSED。
- 当前及后续 commit summary/body、PR title/description 全部使用英文。已逐项检查 origin/main..HEAD 的 18 个摘要均为英文，正文为空；无需改写已推送历史。同一 Draft PR 标题/正文已改为英文并读回核对，历史失败及 11 包合同保留。
- 四个 Android ABI 安装包均须覆盖手机与 Android 平板，iOS unsigned IPA 须覆盖 iPhone/iPad。M5/M7 的自适应布局、窗口/方向、资源和交互矩阵分别验收，暂不增加折叠屏专项适配。
- 五平台保持同一 EhViewer 图标视觉身份，在 Android 自适应/密度资源、Apple 平台图标资源、macOS ICNS、Windows 多尺寸 ICO、Linux 桌面图标集成中遵循各平台规范；以项目自身资源为来源，不复制 Animeko 图标。M5/M6 分别验证显示与安装包资源。
