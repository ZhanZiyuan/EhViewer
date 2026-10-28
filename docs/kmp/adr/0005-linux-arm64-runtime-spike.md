# ADR 0005：Linux aarch64 包内运行时技术证明

状态：Linux应用目录/runtime技术切片 / GATE_PASSED，M1整体仍IN_PROGRESS。c787c570e的双事件全部八job/gate、Android四包与全部实际工件均SUCCESS；初始SDK与WebP失败历史保留。前置be27ac67a的双事件七job、Android候选四包和原生Intel同包消费全部SUCCESS，实际工件核验及同一Draft PR记录完成后才开始本切片。版本继续1.15.10 / 180074。

## 独立设计与来源

只沿用M0已审阅的Animeko `app/desktop/build.gradle.kts`、`app/desktop/src/main/kotlin/DesktopNativeStartup.kt`所体现的平台资源/Native初始化边界；本次按需重读desktop构建目标片段，未采用其视频、JCEF、Native依赖选择或脚本。实际目标为EhViewer自己的`app/desktop/build.gradle.kts`、`DesktopMain.kt`、`tools/verification/.../DesktopLinux.kt`和`ci.yml`。原M1窗口/JNI/许可证打包扩展一个Linux目标，没有复制AGPL实现、资源、测试或版本树。

[Compose官方分发文档](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)区分`createDistributable`应用目录和安装器，并规定`linux-arm64`资源目录。该切片验证真实应用目录及其包内JVM，不伪装为AppImage；最终AppImage结构、可执行入口和11包聚合仍为M6独立工作。

采用指定`ubuntu-24.04-arm`原生runner和Temurin25.0.5 AArch64；硬件/JVM/Rust triple必须一致，不使用x64模拟结果。现有CMP1.11.1实际选择的Skiko0.144.6在官方Maven发布了Linux ARM64 runtime：归档SHA-256=`313082bbc829dc09664f9bde09b18b668396a57522f43279e373c1a67198419f`，独立下载并核对官方摘要，其唯一Native为ELF64/AArch64，SHA=`d4b06daa856c00707183127e5ff269d082db307f677a72a6076b8cbb7f54121e`。许可证仍Apache-2.0，复用已核对的上游原文材料；没有依赖升级。

[Adoptium官方元数据](https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=aarch64&heap_size=normal&image_type=jdk&os=linux&vendor=eclipse)实际返回25.0.5+8.0.LTS/Linux/aarch64及`OpenJDK25U-jdk_aarch64_linux_hotspot_25.0.5_8.tar.gz`，SHA=`bb5b2ded252f21c6090cf780b0a408ad0d83bbb557e702955643557f13231880`。这里只核对可用性；实际安装、字节和运行仍以对应CI证据为准。

- [官方Maven runtime](https://repo.maven.apache.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-linux-arm64/0.144.6/)
- [setup-java架构与校验说明](https://github.com/actions/setup-java)

`BuildDesktopNative`在原Intel路径之外只接受Linux/aarch64，使用Android同一固定WebP1.6.0源码及原归档摘要，静态PIC构建WebP，再编译原完整Rust JNI/Core；不禁用归档、图片或任何Core模块。Linux链接系统libarchive是M1技术证明，不能替代M4固定版本/安全依赖审计。现有Mac install_name和Skiko双架构筛选保持，Intel回归必须再次通过。

## 实测门槛

Kotlin验证器复制应用目录到自有临时安装位置，拒绝出界符号链接；逐个检查ELF64/AArch64、动态搜索路径和实际依赖解析，拒绝缺库或外部JDK/构建目录。固定Skiko实际Native摘要，检查依赖JAR内Native架构、六份安装后许可证原文和版本。两次真实包内launcher必须使用包内jlink JVM、显示Compose窗口/实际绘制，并各完成100次原Rust Core hash调用；同一包内Rust另由同版本原生测试JDK执行4,170项所有权/字节预算断言和13归档样例。

`xvfb-run`提供CI显示服务，程序仍运行原生AArch64 JVM/Skiko和真实Compose绘制。记录OS、Rust/JDK、干净源码、包内Native摘要和实际调用数；任何缺失/异常/清理失败均失败。新增必选job纳入always-run gate，所有非success均拒绝，原Android/iOS/双host/ARM构建IntelDMG/原生Intel检查不删减。

## 当前证据与边界

本机仅有macOS ARM64；Linux Core的`cargo check --locked -p ehviewer_core --target aarch64-unknown-linux-gnu`已真实SUCCESS（3.87s），只证明编译。官方Skiko原始ELF检查只证明发布的二进制格式；均不当作Linux运行通过。本机Docker CLI存在但daemon socket不存在，未取得容器执行证据。

本地需完成工具全量测试、Linux错平台拒绝、原Intel DMG真实重建/启动及完整Android回归。然后独立commit/push，等待新SHA的两事件全部八job及实际Linux工件成功。结果按SHA记录ledger和同一Draft PR；完成前不宣称Linux阳性，也不开始Windows修复。

最低Linux/glibc基线、AppImage、全P0、完整依赖许可证/SBOM仍NOT_VERIFIED。Windows ARM64原Core已有Unix fd/mode_t真实编译失败，另切片移植；不禁用其归档功能来凑M1绿灯。

本地最终：117项工具测试/0失败错误跳过、Linux错平台拒绝、完整JNI/Core交叉编译、原Intel DMG真实安装/两次启动/32 Native/六份材料/4,170项+13样例、原完整Android319 tasks和精确候选四包全部SUCCESS；源码边界/actionlint/diff检查通过。均为be27ac67a+dirty源；Linux仍无运行阳性，下一SHA两事件全部八job与实际工件须独立成功，详见ledger。

1026cc1d6初次两事件Linux job均真实FAILED：ARM镜像没有Android SDK，根Gradle配置`:app`时直接失败，尚未进入Native/GUI测试。修复使用`app/desktop/settings.gradle.kts`独立入口及同一源码/依赖版本，不配置Android项目；版本/Native/GPL路径改为相对模块实际位置，原根工程与Android全量CI保留。Linux构建日志另存报告，pipefail与实际失败fixture保证tee不会吞掉错误；不放宽任何原断言。旧失败及后续SHA独立验证见ledger。

修复本地：取消Android SDK环境变量后独立projects/packageDmg/lint SUCCESS（19/19执行，只有desktop且无子项目），新DMG完整真实安装/启动/Native/许可证及4,170项+13样例PASS。原根工程Android319 tasks及四包预检、118项工具测试/0失败错误跳过也SUCCESS；0/93实际脚本fixture验证日志与失败传播。均为1026cc1d6+dirty源，Linux阳性仍待新SHA CI，不能反推初始失败为成功。

43a798721的SDK修复让两条Linux实际构建和工具测试成功，但首次包内加载均FAILED于WebPInitDecoderConfigInternal缺符号。失败工件12796839940经官方ZIP摘要校验后实读ELF：无动态WebP依赖，UND包含该符号及WebPGetFeaturesInternal，固定源码demux/anim_decode.c确实引用decoder库。根非Androidbuild.rs声明webp先于webpdemux；[GNU ld](https://sourceware.org/binutils/docs/ld/Options.html)单次归档扫描及[Cargo](https://doc.rust-lang.org/cargo/reference/build-scripts.html)参数顺序约定解释这一真实静态依赖缺失。Mac原包nm未见相同缺符号，保留已有实测结论。

独立修复将非Android库顺序改为webpdemux→webp，Linux构建启用-z defs，安装验证对Rust实际ldd -r并拒绝未解析重定位；原Android CMake、全部图片/归档模块及所有运行断言保持。不升级/禁用依赖，不据编译通过宣称运行成功；本地与下一SHA双事件实测结果见ledger。

链接修复本地：Core19+14/C ABI6/JNI5、fmt/原三Android target严格Clippy、工具118项测试、原Android319 tasks/四包、Intel实际DMG/32 Native/两次启动与Mac ARM64 host的4,170项+13样例均SUCCESS。Mac重链接结果未变，后续打包任务复用该内容，实际安装/启动仍重新执行。以上为43a798721+dirty源；Linux真实阳性仍未取得，必须另交新SHA并完整CI复验。

c787c570e远程最终：两事件全部八job/gate与实际工件全部SUCCESS。两个Linux报告均为干净head/test-merge、Ubuntu24.04.5/JDK25.0.5/原生AArch64/应用1.15.10，30个ELF Native/依赖、六份许可证、两次包内JVM/Compose各100次Core/实际frame与同库4,170项+13样例PASS；真实ELF已无UND WebP，ldd -r无未解析项。Rust/Skiko/JVM/入口摘要两事件相同。实际工件和run链接均见ledger；这不构成AppImage、最低OS、完整P0或Windows runtime验收。
