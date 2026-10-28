# ADR 0004：ARM 主机构建 Intel DMG Spike

状态：ARM_ROSETTA_SPIKE_GATE_PASSED / M1_IN_PROGRESS。b876b67c5两事件全部六job/gate及实际工件均成功，见ledger；M1整体仍未通过。前置ad68c3c0e全绿后才开始本切片，aec6f057f/df31e9133的失败历史保留。

参考路径：只读 Animeko `app/desktop/build.gradle.kts` 的平台资源/独立 JVM 分发边界、`DesktopNativeStartup.kt` 的平台 Native 初始化边界和 `.github/workflows/build.yml` 的目标 runner。其 Intel job 实际使用 macos-15-intel，不能证明本任务的 ARM runner/Rosetta 路径；拒绝直接采用 runner、JBR/JCEF/播放器、Shell/PowerShell 工具和实现/资源/依赖树。目标落点为 EhViewer 独立的 `app/desktop`、Kotlin verification 和相应 CI。没有复制 AGPL 代码。采用官方 Compose native distribution / JDK jpackage 接口，继续使用 EhViewer 已有 Kotlin/CMP/Rust 与 WebP 固定版本。

范围：只证明最小真实 Compose 窗口、Intel JVM/Skiko/Rust JNI、对应 runtime 和 DMG；共享阅读 UI、P0、原生 Intel 硬件、其他桌面平台及正式发布另行验收。版本从原 Android Kotlin DSL 解析，仍为1.15.10，不提前升级1.16.0，不签名/公证或发布 Release。新实现仅 Kotlin/Gradle Kotlin DSL；WebP C 源码是既有第三方编译输入，没有新增自主 C/C++ 实现。

先行实测：官方 Temurin25.0.5+8 macOS x64 archive SHA-256=`32cd4d2151c91aa00d0fc911edfd05245ed40fa6b1aa894d5f35c928cd67a59d` 校验成功，实际 Mach-O x86_64 Java 在本机 ARM/Rosetta 返回 os.arch=x86_64。临时安装仅在 /tmp，不修改系统 JDK。

原 JNI cross-build 首次 FAILED（缺少 Intel WebP）。尝试现有 sys crate 的静态环境选项仍 FAILED；实际读取锁定 fork Cargo.toml 发现 build=false，故该环境选项并不生成库，未改 fork/锁文件或冒用 ARM 库。采用原 Android 同一 WebP commit `4fa21912338357f89e4fd51cf2368325b59e9bd9`（1.6.0），用官方 CMake 交叉编译 webp/webpdemux。第一次手工链接因 -L 相对路径相对于 Cargo crate 工作目录仍失败；改为真实绝对路径后 SUCCESS。第三方缓存 HEAD/dirty 已验证；CI 方案独立从官方 codeload 固定 commit 获取归档，SHA-256=`923f3382a47a2af185c3240c954cf004428b237bd7317413a95146d01eb4b94b`，不依赖本地 Android 缓存或 Animeko。

生成的真实 x86_64 JNI 在 Intel JDK/Rosetta 上通过原4,170项所有权/预算断言和13个归档样例。当前动态依赖仅系统 libarchive/CoreFoundation/iconv/System，WebP静态链接；该系统归档库结果不能替代 M4 固定版本/安全依赖/完整 Native 发布审计。Gradle 打包将 install_name 改为 @rpath，避免本机构建路径；最终包仍需重新核验架构、依赖与实际启动。最小 Compose/DMG 正在构建，尚无通过结论。

初次 Compose/DMG 构建 SUCCESS（2m38s）；原包内 launcher 已实际使用包内 Intel JVM、绘制 Compose frame并完成100次Rust Core调用。第一轮完整安装 smoke FAILED：jpackage 内嵌本仓库 GPLv3 许可提示，关闭的 stdin 导致 attach canceled。保留 GPL 文本，验证器对自己的临时测试安装明确输入 Y；未移除许可证。

第二轮完整安装 smoke FAILED：严格扫描 JAR 发现上游 Skiko x64 runtime 仍含 ARM64 dylib。读取实际原始 JAR，确实同时含两架构；[Maven 官方 SHA-256](https://repo.maven.apache.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-macos-x64/0.144.6/skiko-awt-runtime-macos-x64-0.144.6.jar.sha256) 与原缓存均为 `aacb31a5d2a70197abe18f9e81698eef15c83435791d4c487a2ffe139913762e`。Compose 插件抽出 x64 Native 后，把 ARM64 项留在依赖 JAR。未放宽架构检查；固定当前 CMP 实际选中的 Skiko0.144.6，独立 Kotlin task 校验源 SHA，仅从构建输出移除两项明确 ARM64 library/checksum，保留其他条目和原缓存。随后 Compose 正常抽取选定 x64 Native。不是升级依赖或替换扩展名。官方 POM 声明 Apache-2.0；WebP 既有 COPYING 为 Google BSD 三条款。原版权/资源保留，没有 AGPL 精确复用。

筛选后 DMG 构建 SUCCESS（2m），完整 smoke SUCCESS：UDIF校验、只读挂载、自有临时安装、32个实际 Mach-O薄x86_64文件（含JVM/Skiko/Rust/launcher）、JAR无残留Native、两次包内runtime/Compose启动各实测100次Core调用及真实绘制；同一包内Rust在外部同版本Intel测试JDK完成4,170项+13样例。DMG SHA-256=`d2d71da8610fcc1d999c3de98afe2c80ccd746a9d12482d38b91ad488b98e688`，报告 `reports/intel-dmg-selected-skiko`，提交前 dirty=true。后续加强每个Native动态依赖的包内/系统路径检查，并补录实际环境与未验收最低OS；最终重建/重验仍在进行，不以此替代新SHA CI。

原完整 Android tests/lint/build SUCCESS（2m51s，319 tasks：303 executed/5 cached/11 up-to-date）；验证器114 tests及四依赖gate的失败/取消/跳过/neutral/未完成拒绝全部SUCCESS。新增必选`ci/desktop (macos-x86_64-rosetta)`仍使用macos-latest ARM64，安装/验证Rosetta后用真实Intel JDK25.0.5编译打包。Android/iOS/双host JNI门禁保留，gate只接受四依赖全部success。原生产Release结构、稳定版本、签名材料与main不变。新SHA尚未提交/运行；物理Intel硬件、最低OS、签名/公证和Desktop P0明确NOT_VERIFIED。

最终本地结果：按CI的2GiB/单worker条件`--rerun-tasks`真实重编译/打包SUCCESS（1m20s，20/20执行），新增真实Intel JDK前置检查后再验SUCCESS（46s，5执行/17复用），产物哈希一致。完整增强smoke最终SUCCESS，报告`reports/intel-dmg-final-installname`，DMG SHA-256=`091f66865e1f8746562e5e60b19474ee35190954dbf4f0a11e4cdf12662fe7c9`；32个薄x86_64 Native逐一核验实际install ID和全部加载依赖，仅包内相对引用或系统库，两次包内runtime/Compose各100次Core调用、同库4,170项+13样例均通过。增强检查一度把otool -L所含LC_ID误作外部加载依赖而FAILED；实际读取otool -D区分install ID后修正分类，外部依赖规则没有放宽。最终114 tests SUCCESS（1m26s，0失败/错误/跳过），原全部APK/Native/diagnostics/bytecode/JNI/ABI/16KiB与精确4包snapshot SUCCESS；原ARM host助手回归4,170项+13样例SUCCESS。原两iOS executable/lint SUCCESS（23s），iOS26.5完整smoke SUCCESS：PID12505→12747，两冷启动各2000、live=0，严格终止/生命周期与清理通过。

环境：本机macOS27.2 / Xcode27.1（27A9275）、Intel Temurin25.0.5、固定Rust nightly（rustc1.100.0-nightly c656540d6 2026-08-21）、CMake4.4.4。Bundle默认声明最低macOS10.13仅是插件元数据，未验收该系统，报告明确NOT_VERIFIED。以上均为提交前dirty源；只在新SHA的push/PR全量适用CI与实际DMG工件真实SUCCESS后，才能标记本切片门禁通过。

提交后只读补查：vtool实际显示Skiko为LC_BUILD_VERSION minos11.0/sdk26.2、包内JVM minos11.0/sdk14.2，Rust为LC_VERSION_MIN_MACOSX10.12/sdk27.0。插件默认plist10.13与Native编译下限不一致，需要后续独立对齐和真实目标OS回归，不能据默认值承诺10.13或11支持。本切片只证明已实测系统上的ARM→Intel构建/启动，物理Intel和最低OS继续未验收。

aec6f057f远程最终：PR40620941911六job/gate全部SUCCESS；Push40620938493因macOS ARM64 host的Cargo15分钟超时及日志丢失而FAILED，gate正确失败。两条ARM→Intel真实DMG job均SUCCESS，PR实际包及源/架构/依赖/两次启动/同库回归工件已下载核验，包SHA=`ccfd68c0b375b70a823c860402446eb20b674ef1cf094452e08b824bc6b48b07`；整个切片不标记GATE_PASSED。后续仅修复超时输出/自有子进程清理并另交新SHA全量复验，不放宽时间或断言。原超时根因因缺Cargo输出仍未知，不能说已经定位。全部job/工件证据见ledger。

## M1-009：将直接Native依赖的许可证材料保留到安装包（验证中）

b876b67c5全量push/PR门禁和实际工件核验成功、写入ledger和同一Draft PR之后，才开始本独立修复。实际检查已安装`.app`及全部依赖JAR，发现WebP静态库材料没有入包，Skiko runtime JAR本身也没有LICENSE/NOTICE；原DMG的GPL提示不等于安装后的应用已保留这些文件。

保留原GPL提示，同时在`appResourcesRootDir`的`common/licenses`中放入项目GPL、WebP COPYING/PATENTS/AUTHORS及Skiko LICENSE/NOTICE和来源元数据。WebP材料来自原固定归档（commit4fa21912、归档SHA923f3382…），不另换来源；Skiko材料来自官方0.144.6的不可变commit`9a5b398bb2044fff7e7a84fbfd6f4b803e4427c0`，独立核对LICENSE/NOTICE Git blob分别为`261eeb9e9f8b2b4b0d119366dda99c6fd7d35c64`/`bc646e35dcbf08a6eb0404c88864cf26b55f9581`。保留原文，未复制任何Animeko/AGPL代码、资源或脚本，没有依赖升级。

Kotlin DSL把上述材料列为构建输入，验证器在真实DMG安装后逐文件检查原文SHA-256并记录结果；没有以EULA提示或文件名代替内容验证。本修复只覆盖已确认遗漏的直接Native材料，不宣称完整Skia/Rust传递依赖许可清单、SBOM或最终M8许可证验收完成，报告显式保留NOT_VERIFIED。原文资源设为路径限定的`-text`以保持跨平台checkout字节，不改变源码语言识别。

最终本地：115项工具测试通过；旧真实DMG按预期拒绝缺失材料，临时镜像已卸载。新包20/20任务强制执行（1m22s）及真实安装/六份摘要/32Native/两次包内runtime/同库4,170项+13样例全部SUCCESS，SHA=`6893a8ff55f9c23f8860cfecb1422b9e3f4e43882e5612301bbf36a6c48bbdde`。原完整Android319 tasks、原四包/Native预检、ARM host及iOS26.5完整生命周期/C ABI回归亦成功。均为b876b67c5+dirty源，下一提交双事件全量CI仍需独立验收；见ledger。
