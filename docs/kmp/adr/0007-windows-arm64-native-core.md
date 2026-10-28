# ADR 0007：Windows ARM64 原生 Core / C ABI 测试

状态：本地适用验证SUCCESS，Windows 原生阳性待新 SHA CI。前置4d6f62cc2双事件各八job/gate及全部实际工件SUCCESS已记录ledger和同一Draft PR后开始。M1整体IN_PROGRESS，稳定版本1.15.10 / 180074。

## 独立范围与来源

此切片只建立真实Windows Native依赖/完整Core与C ABI测试，不声称包内JVM/Skiko窗口或最终Windows ZIP通过。沿用M0的平台职责边界，不新增Animeko代码/资源/脚本/依赖树复用。目标文件为EhViewer的`tools/verification/.../WindowsNative.kt`、通用进程工具/测试和`ci.yml`；所有自主实现为Kotlin，声明式YAML及Kotlin生成的vcpkg JSON/CMake配置分别属于工作流/生成产物，没有维护PowerShell/Shell/C/C++业务实现。

使用用户指定`windows-11-arm`，核查[当前官方镜像清单](https://github.com/actions/runner-images/blob/main/images/windows/Windows11-VS2026-Arm64-Readme.md)已为VS2026，不能硬编码VS2022目录。构建工具使用镜像提供的原生JDK；Kotlin必须核验JVM架构及真实java.exe ARM64 PE。固定根工程nightly日期，显式安装`aarch64-pc-windows-msvc`宿主工具链并核验rustc宿主/PE；三个实际Rust测试EXE和所有实际编解码DLL必须是PE32+/ARM64，拒绝x64、ARM64EC、COFF对象、错误DOS/PE偏移和截断。

## 必要依赖及许可

只在忽略的`app/desktop/build/windows-native-core/`构建第三方依赖，不加入子模块或改动全局vcpkg。固定[vcpkg源码](https://github.com/microsoft/vcpkg/tree/15b12b99a227d51e21c86dffd40385439eac87a0)，独立读取其MIT许可、port/version数据库和3.8.7 port内容。生成manifest明确覆盖libarchive3.8.7（其port tree为5fa6c30380bdf44dc81d78116c3a1f01a87a86b5），避免采用baseline默认3.8.9。生成triplet包含上游原arm64-windows声明，只构建release动态依赖；这是测试使用的真实Native库配置，不是产品Release。

| 输入 | 固定来源/版本 | 独立核查与用途 |
|---|---|---|
| vcpkg | 15b12b99a227d51e21c86dffd40385439eac87a0 | MIT；上游bootstrap/build脚本，禁用metrics，保留真实HEAD/干净tracked状态 |
| libarchive | 3.8.7#0 | [COPYING](https://github.com/libarchive/libarchive/blob/v3.8.7/COPYING)为BSD条款及逐文件例外；port license=null不能当作无许可；Windows Mode ABI依据同版头文件 |
| libwebp | 1.6.0#3 | BSD-3-Clause/上游材料；与现有WebP主版本相同，绑定fork的build=false要求显式链接demux→decoder |
| OpenSSL | baseline固定3.6.5#2 | Apache-2.0；该官方libarchive port的crypto后端，用于实际加密ZIP测试，不更改Android Nettle配置 |
| zlib | baseline固定1.3.2#2 | Zlib；ZIP/deflate/gzip |
| liblzma | baseline固定5.8.4 | 0BSD；7z/LZMA及TAR/xz |

只选择Core已注册的ZIP/TAR/7z/RAR5与gzip/xz所需crypto/lzma，zlib为port必要依赖；不照搬整个默认功能/版本树。原Core归档/图片/GIF/解析模块及全部适用测试保持。复制实际安装的版权文本/摘要、完整vcpkg status、生成manifest/triplet和日志到证据中；逐文件完整许可证/SBOM审计仍NOT_VERIFIED，留在M4/M6，不能用此表宣称完成。

提交前独立核对[WebP1.6.0 CMakeLists](https://github.com/webmproject/libwebp/blob/v1.6.0/CMakeLists.txt)的MSVC前缀声明：import library实际为libwebpdemux.lib/libwebp.lib，不能沿用Unix的-lwebp名称。Kotlin构建器检查archive.lib及两WebP import库存在，再按demux→decoder显式链接正确名称；原Android/Unix链接配置不变。

## 实际执行与门禁

Kotlin工具给每个子进程显式环境，保持父环境和其他调用不变；真实子JVM回归验证注入/不泄漏。PATH使用原有键的大小写，Cargo encoded flags保留含空格路径。Git锁定输入、Native安装、编译、测试、Clippy任一步非零/超时直接失败并保留部分输出；原超时和自有进程树清理保持。第三方Native构建独立上限30分钟，Rust命令15分钟，job60分钟；无重试/continue-on-error。

先完整`cargo test --no-run --message-format=json`验证Core单元、archive集成、C ABI单元三个实际EXE的PE架构/摘要，再正常运行完整`cargo test --no-fail-fast`（包括适用doctests），不传测试过滤器。严格要求零failed/filtered；仅允许原有三个在线服务ignored原名，且File writer回归必须实际执行。Windows Unix fd专属用例不适用，原Unix用例在全部原job继续执行；Windows其余14个归档集成用例保持。然后完整Core/C ABI all-targets Clippy `-D warnings`及workspace fmt。原Android三目标Clippy、JNI和全部验收不变。

报告保留真实测试EXE/DLL/生成头/许可证材料供独立摘要和PE复核，记录干净精确源码、工具链/OS、依赖输入、每个Native摘要和实际测试汇总。原验证工具包含Unix工作流执行fixtures，完整工具测试继续在原Linux/macOS必选job执行；新Windows job构建同一Kotlin工具并执行完整适用Rust原生测试，不把工具构建称作Windows全P0验收。always-run gate加入第七依赖，所有非success状态均拒绝，总计九job。

本机只有macOS ARM64；新Windows入口必须在创建证据/构建目录前明确拒绝本机。该负向、PE格式边界、进程环境用例和原平台回归不能代替Windows阳性。首次本地PE负向fixture错误保留于ledger，修正输入后必须完整重跑；新SHA指定runner实际链接/执行和所有原CI成功前不继续桌面运行时。

本地最终工具120项/格式/构建、原Android完整tests/lint/build/四包预检、Intel DMG/ARM host真实回归、iOS两target和27.0生命周期/两次2000/live0均PASS。ZIP输出UTF-8名称字节新增断言随完整Core19+15/C ABI6回归通过，宿主真实Cargo元数据确认三测试EXE/生成头结构；上述均不当作Windows阳性。详细本地失败/修正/结果和新SHA门禁见ledger，暂存审查通过才提交。
