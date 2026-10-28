# ADR 0010：Windows ARM64 包内 JVM / Skiko / Rust 窗口

状态：本地完整验证通过，待独立提交及指定原生runner的Windows实际阳性。前置75b36b2ef双事件全部九job/gate及全部实际工件已核验并同步同一英文Draft PR。M1整体IN_PROGRESS，版本1.15.10 / Android180074。

## 范围、来源与差异

本切片只验证真实Windows ARM64 jpackage应用目录及包内运行时/Compose窗口，不声称最终ZIP、Desktop P0、最低系统、硬件加速或完整SBOM完成。原39项Core/C ABI、独立JVM FFI及全部原平台job仍必选；新增步骤失败或工件上传失败都会使现有Windows job及总gate失败。无新的跳过、重试、continue-on-error或限时放宽。

按需只读复核Animeko `app/desktop/build.gradle.kts` 中Windows平台Native选择片段；仅延续M0已审计的平台资源/启动职责边界。目标是EhViewer自己的`app/desktop/build.gradle.kts`、`DesktopMain.kt`、`RustCapiProbe.kt`、`tools/verification/.../DesktopWindows.kt`及`ci.yml`。拒绝其mpv/FFmpeg/JCEF依赖、初始化实现和发布脚本；本项目保留原漫画/归档/Rust功能，用自己既有五函数C ABI与明确所有权。未复制或机械改写AGPL代码/资源/脚本/依赖树，无精确复用许可请求。

- [JDK25 jpackage官方规范](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html)：app-image携带运行时，必须在对应平台生成。本切片使用已固定并实测的Microsoft ARM64 JDK25.0.5.0.2，不从本机交叉伪造Windows包。
- [固定CMP1.11.1的目标选择](https://github.com/JetBrains/compose-multiplatform/blob/v1.11.1/gradle-plugins/compose/src/main/kotlin/org/jetbrains/compose/ComposePlugin.kt)：使用现有currentOs目标，不升级版本。既有Skiko0.144.6 Windows ARM64上游JAR SHA f1b2d2ec7e523caffd3b9b7d475d5f9ccca19f314c3e91b8ea689a732648d91c；实际DLL独立提取摘要 f72ac08cb6163da84817a102203ce9103015cee42e4dbb80e2b337b3e3bbadbf。
- 沿用ADR0007/0008的Native/JDK独立许可核查；项目GPL材料、Skiko LICENSE/NOTICE/origin与五份实际vcpkg copyright进入应用，jlink的java.base许可证必须与固定JDK匹配。此有限材料核验不等于完整传递许可/SBOM审计。

## 实现与真实退出准则

1. Kotlin verifier拒绝非Windows ARM64宿主，核对同一干净源码的Core39/零filtered与真实JVM2000/live0/释放关闭证明；原Core报告须与JVM所测输入逐字节匹配。验证固定JDK原ZIP、java.exe、jlink、jpackage、JVM的身份/ARM64 PE，重用已经实测的C ABI与10个依赖DLL并逐个核验摘要。
2. 只在新的忽略build目录生成Windows资源与九份许可/来源文件。当前Gradle Windows资源目录仅由这个Kotlin工具准备；Mac/Linux原Native构建任务与路径保持。现有Compose createDistributable产生真实EhViewer.exe与jlink运行时，不生成改扩展名的假包。
3. 保留完整实际应用目录，再复制到含空格/中文的独立安装目录；复制拒绝符号链接/特殊文件、重叠源目录和已有目标，不清空用户目录。核验所有EXE/DLL的真实ARM64 PE、包内JVM与全部C ABI/依赖/许可摘要、固定Skiko DLL及JAR内Native架构。
4. 使用包自身EhViewer.exe两次启动。报告路径通过UTF-8/Base64 ASCII传输，实际安装与DLL路径仍含中文。每次沿用同一个Kotlin所有权调用器，实际进行2,000次SHA-1独立比较、过小容量不写入、失效/重复释放拒绝与live0，并确认库关闭后才创建Compose窗口；必须window.isShowing、真实绘制帧、包内java.home/版本/库路径均匹配。
5. CI显式选择SOFTWARE_COMPAT并要求实测render_api相同；这是首次Windows窗口技术证明的CPU后端，不把它写成Direct3D/OpenGL或硬件加速阳性。每次原两分钟启动上限、窗口等待30秒不放宽。
6. 同一windows-11-arm job保留旧Native/JVM工件，再保留新的真实应用目录、构建/启动日志及精确源码/摘要报告；任一步失败均非零，失败证据保留。原生产releases.yml/版本/Android验收与单Draft PR保持。

自主维护实现仅Kotlin；未新增Shell/PowerShell/Java/C/C++实现。FFM通用调用由原RustCapiProbe抽出一个内部函数供独立CLI与窗口共同使用，Windows宽字符加载和实际释放检查不复制、不删减。Unix原100次fd/JNI调用保持；所有原host/DMG/Linux回归继续验证该路径。

## 验证记录

最终完整验证工具回归123项/0失败错误跳过、格式与installDist在本机通过（42s）；复制回归实际验证Unicode/字节保真、拒绝覆盖用户文件、拒绝嵌套目标和越界链接。工作流actionlint/源码政策通过。原Android完整319任务与原四APK/Native预检、桌面编译及实际Intel DMG两次METAL窗口和4,170项/13样例均通过；本机ARM真实C ABI共享调用器2,000/live0/全部错误与库关闭亦通过。Windows指定runner结果继续写入ci-ledger；本地非Windows拒绝和静态PE检查不能当作Windows窗口阳性。
