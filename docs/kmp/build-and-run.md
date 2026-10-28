# 构建与运行（按已接入切片）

稳定版本源仍为 `app/build.gradle.kts` 的 1.15.10 / 180074。这里只记录实际可用的入口；不代表五平台 P0 或 11 包发布已完成。

## Android

保留原 `:app`、SDK 37、minSdk 26、NDK 29.0.14206865、JDK 25 / Java 17 字节码。完整检查与 APK/Native/诊断验证命令见 [ci-ledger.md](ci-ledger.md) 和 [verification 工具](../../tools/verification/README.md)。Feature 构建不使用 `-Prelease` 或生产签名；测试签名 SNAPSHOT 不能覆盖生产安装。

## iOS Kotlin-only 启动 Spike

需要 macOS ARM64、Xcode、已安装且可用的 iOS Simulator runtime、JDK 25。Kotlin/Native 首次会下载官方编译器/LLVM/依赖。没有手写 Swift/Objective-C 入口，生成的编译器/SwiftPM 中间产物留在忽略的 build/cache 目录。

```text
./gradlew :app:ios:packageSimulatorApp :app:ios:linkDebugExecutableIosArm64 :app:ios:spotlessCheck --console=plain --no-daemon --max-workers=1 -Dorg.gradle.parallel=false
./gradlew -p tools/verification check installDist --console=plain --no-daemon
tools/verification/build/install/verification/bin/verification ios-startup --app app/ios/build/simulator/EhViewer.app --output reports/ios-startup
```

`packageSimulatorApp` 从真实 Kotlin/Native executable 创建 `.app`，从 Android 权威源生成 plist 版本，仅执行显式 ad-hoc Simulator 签名。device executable 编译输出不是已签名真机应用，也不是 unsigned IPA 的交付证明。

`ios-startup` 核对 Mach-O arm64 / IOSSIMULATOR、bundle、签名和 source 状态；只创建并使用本次 UUID 命名的独立 Simulator，不复用、擦除或删除用户已有设备。机型来自 runtime 的 supportedDeviceTypes。实际安装、启动、等待 Compose ready、截图、切入 Settings、回前台、终止与第二次冷启动后，保存 JSON / 生命周期日志 / PNG，再 shutdown/delete 本次设备。每次冷启动单独捕获 stdout/stderr 与 PID；后台/恢复必须保持原 PID，严格终止后原进程必须退出，第二次冷启动 PID 必须不同。环境与阶段记录在失败时也保留，统一日志不再承担滚动计数断言。任何断言或清理失败均使检查失败。输出目录必须不存在；重复运行请选择新的报告目录，工具不会覆盖旧证据。

启动前还检查 `CADisableMinimumFrameDurationOnPhone=true`；[Compose 官方要求](https://kotlinlang.org/docs/multiplatform/compose-uikit-integration.html)缺少该项会在运行时崩溃，不关闭默认严格检查。默认选择最新可用系统；复验某个 runner 时可追加 `--runtime com.apple.CoreSimulator.SimRuntime.iOS-26-5`，指定系统不存在或不可用时直接失败，不回退。每个系统使用不同的报告目录。

## Desktop JNI host Spike

macOS ARM64 需安装 libarchive/webp；Linux x64 需 libarchive-dev/libwebp-dev。使用项目固定 Rust toolchain 与 JDK 25，从 EhViewer 根运行：

```text
./gradlew -p tools/verification check installDist --console=plain --no-daemon
tools/verification/build/install/verification/bin/verification desktop-native-smoke --output reports/desktop-native
```

CLI 实际编译 host cdylib、检查文件格式/CPU，再在独立 JVM 执行既有 JNI 所有权与归档回归。输入只来自 EhViewer 固定 Git 基线及 Kotlin 生成的 ZIP，不依赖 Animeko。报告目录必须全新。此切片仅支持 Linux x86_64 / macOS ARM64 host smoke；其他 CPU/OS、桌面 GUI/打包 runtime、iOS C ABI 明确未验收。见 ADR 0002。

## iOS Rust C ABI Spike

安装固定 Rust toolchain 的 `aarch64-apple-ios` / `aarch64-apple-ios-sim` targets 后，原 `:app:ios:packageSimulatorApp :app:ios:linkDebugExecutableIosArm64` 命令会编译 Rust staticlib，用固定 cbindgen 自动生成头并通过 `.def` 链接；不需要手写 Swift/ObjC/C 入口。Cargo JSON/诊断与生成头在 `app/ios/build/rust/`，CI 单独保留这些构建证据，不上传 staticlib/DWARF。

原 `ios-startup` 命令继续执行完整生命周期，并额外要求每次冷启动的实测 Rust 证据：至少2000个结果复制/释放、live=0。缺失、重复或不完整证据都会失败。只验证缓存身份算法的真实 Core 调用与所有权，完整 Apple 归档/图片、开发签名真机和最终 unsigned IPA 仍未验收。见 ADR 0003。

本地实测为 Xcode 27.1 + iOS 27.0 / iPhone 18 Pro；Info.plist 的 15.0 是声明的编译下限，没有验收 iOS 15 设备。CI 使用 macos-latest ARM64，限制并发和编译内存；逐 SHA 的远程结论见 ledger。

此切片只有启动探针画面与最小 Rust Core 所有权验证。图库、登录、阅读、下载、完整 Native 归档/图片、开发签名真机与 11 包分发仍需后续工作，不能把探针当作 iOS 完整产品。

## ARM 主机的 Intel DMG Spike

需要 macOS ARM64、可用的 Rosetta、固定 Rust toolchain 的 `x86_64-apple-darwin` target、CMake和真实macOS x86_64 JDK25.0.5。以该JDK设置JAVA_HOME后执行：

```text
./gradlew :app:desktop:packageDmg :app:desktop:spotlessCheck --console=plain --no-daemon --max-workers=1 -Dorg.gradle.parallel=false -Dorg.gradle.jvmargs=-Xmx2g
./gradlew -p tools/verification check installDist --console=plain --no-daemon
tools/verification/build/install/verification/bin/verification desktop-intel-dmg --dmg app/desktop/build/compose/binaries/main/dmg --output reports/<fresh-intel-report>
```

`--dmg`接受真实DMG文件或恰有一个DMG的目录。打包自动构建同一固定WebP源码和Rust JNI，验证Skiko原始SHA并选取Intel条目；原依赖缓存保持只读。验证器检查真实UDIF/全部包内Native和JAR内架构、临时安装、两次包内JVM/Compose/Rust启动、逐Native动态依赖及同库4,170项/13样例。临时挂载/安装由工具拥有并清理，仓库GPL许可继续保留。运行时是jlink镜像及真正launcher，镜像无需另带java命令；完整JNI测试由同版本Intel测试JDK运行，包内launcher自身独立执行实测Core调用。

这仍是M1探针，不是Desktop P0。be27ac67a已在macOS26.6.1原生Intel上消费ARM构建的同一DMG并通过双事件检查；最低系统和签名/公证仍未验收。内部DMG名不代表M6最终11包已实现。设计、上游双架构JAR问题与失败/修复见ADR0004和ledger。

安装后的应用应在`Contents/app/resources/licenses/`保留项目GPL、当前固定WebP的COPYING/PATENTS/AUTHORS及Skiko的LICENSE/NOTICE。验证器在挂载/安装后逐一核对六份原文摘要，缺失或被修改即失败；来源见同目录的元数据及ADR0004。这是直接Native材料的入包检查，完整依赖许可证/SBOM仍待M4/M8，不因该检查通过而标记全部完成。

## 原生Intel消费验证

`ci/desktop (macos-x86_64-native)`只消费上述ARM job生成的同一DMG，不构建产品。它在`macos-26-intel`核对真实Intel/x64环境，用同版本Intel测试JDK执行：

```text
tools/verification/build/install/verification/bin/verification desktop-intel-native --dmg <ARM-artifact-dmg-directory> --producer-report <ARM-artifact-intel-dmg.json> --output reports/<fresh-native-report>
```

生产者报告必须是当前精确源码SHA的干净成功结果，ARM/Rosetta/目标和包本身SHA均匹配；下载路径位于已忽略的reports内，CI源码仍须干净。消费测试不接受ARM/Rosetta冒充原生Intel。实际源码、构建工具来源、消费环境、包摘要与全部运行检查分别记录；物理Intel的PASS只能来自该机器上完整实测成功。be27ac67a双事件已完整实测成功，证据见ledger；本机ARM负向或Rosetta通过不算Intel阳性。

## Linux aarch64包内运行时

仅在原生Linux ARM64、对应JDK25.0.5及固定Rust工具链执行。CI使用ubuntu-24.04-arm，安装CMake/libarchive开发库、binutils、Xvfb及实际图形/字体依赖；WebP由Kotlin task从原固定源码/摘要构建。原Android/Intel路径不依赖Linux构建机。

```text
./gradlew -p app/desktop createDistributable spotlessCheck --console=plain --no-daemon --max-workers=1 -Dorg.gradle.parallel=false -Dorg.gradle.jvmargs=-Xmx2g
./gradlew -p tools/verification check installDist --console=plain --no-daemon
SKIKO_RENDER_API=SOFTWARE_COMPAT xvfb-run -a tools/verification/build/install/verification/bin/verification desktop-linux-arm64 --image app/desktop/build/compose/binaries/main/app/EhViewer --output reports/<fresh-linux-report>
```

独立入口仍使用同一Desktop源码/build文件和仓库version catalog，避免根工程配置Android SDK；原Android CI仍在根工程完整执行，不省略任何验收。1026cc1d6初次Linux CI已因根工程缺SDK失败，修复SHA必须重新真实验证；构建日志即使失败也保留且退出非零。

43a798721随后真实打包成功，但首次加载因静态WebP依赖顺序失败。非Android必须先链接webpdemux再webp，Linux以-z defs拒绝未解析符号；验证器对包内Rust另运行ldd -r。该修复及后续CI证据见ADR0005/ledger，不能把打包成功当作应用已启动。

验证器检查临时安装后的每个ELF/JAR Native、实际动态依赖、固定Skiko摘要、六份许可证、配置与运行时版本、两次包内JVM/Compose/Rust启动和同库4,170项/13归档样例。临时安装自动删除，报告保留；任何缺库、错误CPU/外部JVM或绘制失败均拒绝。c787c570e已通过原生Ubuntu24.04.5/JDK25.0.5双事件实际运行及全部八job/gate，工件已核验。该命令验证应用目录，不是AppImage；最低OS、完整P0及最终AppImage仍未验收，见ADR0005/ledger。

999418419 的 PR 第二次 GL context 初始化真实失败/超时，旧失败保留。无 GPU 的 Xvfb CI 显式使用 Skiko 官方 SOFTWARE_COMPAT 后端；实际窗口绘制后报告 render_api，验证器要求与请求一致。仍创建两次真实窗口/使用包内 JVM/调用同库，不增加原超时或重试，不将 CPU 绘制称作 OpenGL/硬件加速通过。4d6f62cc2已取得双事件完整八job/gate和全部实际工件SUCCESS，两份Linux报告每次实际后端均SOFTWARE_COMPAT，原回归全通过；OpenGL仍未验收。

## Windows Core 文件接口前置检查

在 `native/rust` 下执行；需固定 toolchain 及 `aarch64-pc-windows-msvc` 标准库：

```text
cargo check --locked -p ehviewer_core -p ehviewer_capi --lib --target aarch64-pc-windows-msvc
```

`write_zip_file` 借用 Rust File，原 Unix fd writer 继续保留。库检查不执行链接或测试；本机 `--all-targets` 已因现有 aws-lc-sys 测试依赖缺 Windows SDK/windows.h 真实失败，未忽略或冒称通过。其后Windows ARM64原生测试由下述独立CI取得真实阳性；本机交叉检查仍不能代替它。包内 JVM/Skiko/Rust、真实窗口和最终 ZIP 尚待独立验证，见 ADR0006/0007/ledger。

## Windows ARM64 原生 Core/C ABI 测试

在Windows ARM64、原生JDK及Visual Studio/Windows SDK的完整Git历史checkout根目录运行；需新报告目录且`app/desktop/build/windows-native-core`不存在：

```text
.\gradlew.bat -p tools/verification installDist spotlessCheck --console=plain --no-daemon
.\tools\verification\build\install\verification\bin\verification.bat windows-native-core --output reports/<fresh-windows-core>
```

Kotlin工具固定vcpkg/Native来源、构建真实依赖、核验JDK/rustc/DLL/测试EXE的ARM64 PE，再执行完整适用Core/C ABI测试、fmt和严格Clippy。EXE/DLL/头/许可证/manifest/status/日志与摘要留在报告目录，任何失败均非零；只管理自建build目录，不删除现有用户目录或重置Git。Linux/macOS原完整工具测试与全部原平台门禁保持必选，Windows新增必选job。4a14bd69d双事件全部九job/gate与实际工件已SUCCESS：Windows各39测试通过、零filtered、仅原3在线ignored，实际10 DLL/3测试EXE均ARM64。该命令不证明Windows包内JVM/Skiko窗口、最终ZIP或完整许可SBOM，见ADR0007/ledger。

## Windows ARM64 JVM / Rust C ABI 调用

先在同一干净checkout完成上节原生Core测试，再使用新目录：

```text
tools\verification\build\install\verification\bin\verification.bat windows-jvm-ffi --core-report reports/windows-native-core/windows-native-core.json --output reports/windows-jvm-ffi
```

工具重新下载并校验固定Microsoft ARM64 JDK，构建既有C API动态库，在含空格/Unicode的自建目录中运行真实原生JVM调用器。2,000次不同输入、复制/错误/释放与active0均须实际通过，保留DLL、JDK输入/许可、原生证明、编译/调用日志和receipt。原Core39项/fmt/Clippy及全部原job不减少。75b36b2ef双事件全部九job/gate、Windows实际调用和所有实物已成功；72a5ce23a容量取消/上传失败保留。窗口与最终ZIP未验证，见ADR0008。

7f1afefe7双事件真实失败在Unicode目录通过JVM参数传输时变为`??`，尚未调用Rust；固定JDK原生编码为Cp1252。独立修复以UTF-8/Base64 ASCII在helper→Gradle→调用器传路径，再在调用器内严格解码；真实目录和宽字符加载保持，`path_transport`及实际加载路径须读回核对。普通本地Gradle `ffiLibrary`/`ffiOutput`属性继续支持；Windows helper使用对应Base64属性。失败记录与新SHA完整门禁见ledger。

## 离线发布状态机测试

`tools/verification`的完整`check`还使用仓库固定Rust工具链构建无额外crate的test-only IPC客户端，执行fmt与严格Clippy。实际workflow Bash、29种发布场景、原60秒用例上限和所有断言保持；每个模拟CLI仍是真实子进程，状态由私有loopback上的Kotlin模型处理。该工具不进入产品包，原生Intel仅编译此测试客户端，产品DMG仍只由ARM producer构建。见ADR0009。

Test输入显式包含客户端二进制和JVM架构；本地切换ARM/Intel时须确认`:test`确实执行，不能把缓存结果视为新架构验证。JUnit XML/HTML保留在既有Intel producer证据工件中，以便真实超时可诊断；正式`releases.yml`保持不变。

## Windows ARM64 包内运行时与真实窗口

在上述Core39项与独立JVM FFI证明生成后，同一原生Windows ARM64 checkout中运行；构建/资源/输出目录须为全新：

```text
tools/verification/build/install/verification/bin/verification.bat desktop-windows-arm64 --jvm-report reports/windows-jvm-ffi/windows-jvm-ffi.json --core-report reports/windows-native-core/windows-native-core.json --output reports/desktop-windows-arm64
```

Kotlin工具严格绑定同源码的Core/JVM证明与真实DLL/JDK摘要，准备必要资源，使用固定Microsoft ARM64 JDK执行现有Compose createDistributable。真实EhViewer.exe、jlink JVM、Skiko、C ABI与十个Native依赖保留在`image/EhViewer`。另在含空格/中文的安装目录两次启动包内窗口，每次2,000所有权调用/live0/完整错误释放关闭、包内java.home/库路径/版本、窗口显示与实际绘制帧均必选。报告路径显式UTF-8/Base64，原Unicode库/安装路径保持。

CI显式SOFTWARE_COMPAT并核对实测后端；这只证明CPU绘制，不证明硬件加速、最终ZIP、最低系统或Desktop P0。所有EXE/DLL/JAR Native真实ARM64 PE与摘要、九份许可/来源文件及JDK许可均需核验。工具拒绝覆盖用户目录、嵌套安装与符号链接，不清空既有目录。设计/许可与逐SHA结果见ADR0010/ledger；本机macOS拒绝结果不当作Windows阳性。
