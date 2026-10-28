# ADR 0002：真实 Desktop JVM / Rust JNI 所有权 Spike

状态：IMPLEMENTED / MACOS_LOCAL_VERIFIED / CI_NOT_RUN。前置 a5a7612a1 的 push/PR 全部 CI 和四包候选预检 SUCCESS 后才开始；M1 整体仍 IN_PROGRESS。

目标：证明现有 Rust Core/JNI 可以在真实桌面 JVM 加载并执行归档与缓冲区释放。不是 Rust-only 单测，也不是桌面产品、JRE 分发、iOS C ABI 或最终发布包验收。

参考：只读 `app/desktop/src/test/kotlin/DesktopNativeStartupTest.kt` 检查 Animeko 的 JCEF/播放器初始化先后顺序，其四个测试本身不能证明 EhViewer Rust 加载或释放。采用“平台 Native 初始化单独验证”的边界；拒绝 JCEF、播放器依赖和对应测试实现，不复制 AGPL 代码、资源、脚本或依赖。实际复用的是 EhViewer 自身 `native/rust/src/ffi/{jvm,native}.rs`、Core 及既有 Kotlin `NativeArchiveRegression.kt` / `NativeArchiveBenchmark.kt`。许可证与原版权声明保留，无精确外部源码复用。

方案：保持 Cargo/Android staticlib 配置不变，用 Cargo 自身 `rustc --crate-type cdylib --features jvm --target <host>` 独立构建 host 动态库。Kotlin CLI `DesktopNative.kt` 核验 ELF/Mach-O 格式及真实 CPU，再启动显式允许 JNI 与旧回归工具 FileDescriptor 反射访问的独立 JVM。该访问仅用于复用已有测试客户端，不作为未来业务文件接口。macOS 链接库目录由 Homebrew 查询；Linux 使用 host 开发库。此步骤没有把宿主库视作可分发的运行时依赖。

测试输入只取自 EhViewer 固定 Git 基线 `8fdfb6c5550fab506759a5587b1c8ee4050f507e` 的归档 fixtures；大 ZIP 由现有 Kotlin 生成器产生，保存在独立临时目录，结束后清理。真实 JNI 回归必须通过至少 4,170 项断言，含多线程、只读/可写视图、重复释放、close 后持有、数量/字节预算；另对 13 个归档样例执行顺序/随机访问与内容摘要一致性检查。这里是 13 个样例，不是声称支持 13 种不同格式，也不声称和旧 C 实现做了跨库差分。

验证与门禁：新增 `ci/rust-host` 的 `ubuntu-latest` x64 / `macos-latest` ARM64 两项，`fail-fast=false`；任何一项失败使 `rust_host` 和 always-run gate 失败。原 Android/Rust/四包检查与 `ci/ios` 保留。验证器拒绝错误 CPU/文件格式、截断库、未接入平台与非 clean CI source，输出准确 SHA、工具链、库 SHA-256、结果与明确 NOT_VERIFIED 项。生产签名、发布权限、stable version 均未改变。

本地初步验证：现有 Rust 在 ARM64 Mac 生成真实 dylib（1.37s），既有客户端实际 4,167 项回归成功（尚未包含新增工具所要求的大文件字节预算三项）。最终 CLI 的 4,170 项与 13 样例需重新实测；Linux 只有 CI 实测后才可标记成功。iOS C ABI、Intel DMG、其他 ARM64 runtime 仍未验收。首次 sandbox 中 Cargo tree 下载被 DNS 限制，授权环境下锁定依赖构建成功；未修改依赖、锁文件或下载来源。

最终本地验证：113 verifier tests / 0 failed/error/skipped（1m33s）；实际新 CLI 在 aarch64-apple-darwin / JDK 25.0.3 通过 4,170 项与 13 样例，明确 source_dirty=true，动态库 SHA-256 `7da6c9ed83dbe35ce01f8ed343e4ad00db1a8861e72f03e0f064b130ec7ac1f1`。显式 host target 的 Rust 构建 7.20s，整个 smoke 退出码 0。Android 原完整 tests/lint/build SUCCESS（1m41s，315 tasks），APK/Native/诊断/bytecode/16 KiB 和真实四包预检另按 ledger 记录。新 SHA 的两平台 CI 必须均成功；本地未运行 Linux，不将 ELF 头单测视作 Linux 加载证明。
