# ADR 0011：Windows 原生启动器的进程 UTF-8 清单

状态：2f00e595c双Windows指定runner实际SUCCESS，完整双事件九job/gate及全部实物亦SUCCESS；SDK/Unicode双窗口/真实65001/UTF-8与所有权均核验。M1技术spike GATE_PASSED，1.15.10 / Android180074。12f/e84历史失败保留，不冒称产品P0/最终ZIP已实现。

## 实际失败与证据边界

e84be9f06已通过固定JMOD输入、包内Native/许可/版本预检，首次从含中文/空格安装目录执行真实EhViewer.exe时原生JLI启动器报could not find java.dll，随后找不到Java运行环境。尚未进入Kotlin/Compose，没有窗口receipt；不能将JMOD修复称作Windows运行时通过。两事件原Core39/fmt/Clippy与独立原生JVM2000/live0均SUCCESS，原完整job仍因新增启动失败而FAILED。

实际包内java.dll存在且为ARM64 PE；实际jvm.dll与固定java.base.jmod条目SHA739a6472e254e0edce981f32e8d2d25eb9ae0fb60ac7dea6702c8549966bb574一致。固定JDK原生编码为Cp1252；从实际EhViewer.exe资源提取的原清单含身份、asInvoker、DPI和兼容性设置，但没有activeCodePage。提取/转换仅在本地自有reports完成，不把静态XML或原独立FFI阳性当作窗口结果。

## 官方依据与独立设计

- [Microsoft进程UTF-8清单](https://learn.microsoft.com/en-us/windows/apps/design/globalizing/use-utf8-code-page)说明Windows1903起Win32 fusion manifest可为进程声明UTF-8，传统ANSI API按进程代码页处理。只针对本应用，不改变系统locale/注册表。
- [SDK mt.exe](https://learn.microsoft.com/en-us/windows/win32/sbscs/mt-exe)支持提取、验证与嵌入RT_MANIFEST资源。使用runner已安装SDK，优先ARM64工具、必要时SDK x64工具；记录工具路径和SHA，不把工具CPU冒称产品CPU，不改用户指定windows-11-arm runner。
- [OpenJDK25基础JLI源码](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/windows/native/libjli/java_md.c)的GetJDKInstallRoot经窄字符路径查询java.dll，GetApplicationHomeFromDll使用模块路径；结合真实错误和缺失清单，推断进程ACP是此Unicode启动故障的修复方向。没有将基础源码等同于供应商全部25.0.5补丁，也未在无Windows实测时断言修复已成功。
- [GetACP](https://learn.microsoft.com/en-us/windows/win32/api/winnls/nf-winnls-getacp)为实际进程读回代码页提供Win32接口。Kotlin FFM调用Kernel32现有函数，不写C/C++/Java/PowerShell实现。

只沿用M0平台边界，无新增Animeko代码/资源/脚本/依赖树采用。目标为EhViewer自己的DesktopMain/RustCapiProbe、Kotlin WindowsManifest/DesktopWindows工具、真实XML回归及诊断入口。项目许可与既有第三方分发材料保持；标准XML命名空间/Win32接口不是AGPL精确复用。

## 必选行为

1. jpackage完成后先复制实际应用目录到报告，再在这个自有副本操作清单；原Gradle输出不变，SDK失败仍保留可审计包。
2. 提取原清单，以安全Kotlin DOM保留所有原身份、权限、DPI和兼容配置，仅加入ASMV3 application/windowsSettings下的SMI2019 activeCodePage=UTF-8。拒绝DOCTYPE、重复歧义设置及不正确根类型，无网络实体访问。
3. SDK验证、嵌入并再次提取；按命名空间、属性和内容比较，要求所有原语义与UTF-8新增项完整保持，篡改或遗漏即失败。保留前后清单、日志、原/新启动器摘要和SDK工具摘要；仍独立核验产品ARM64 PE。
4. 原中文/空格安装路径、UTF-8/Base64报告参数、两次真实窗口、每次2000/live0/全部错误释放关闭与绘制帧均保留。窗口内直接FFM GetACP必须实测65001，JVM native.encoding亦须UTF-8，包内java.home/库路径/版本继续严格匹配。
5. 保持SOFTWARE_COMPAT明确测量，不宣称硬件加速/最终ZIP/最低系统/P0/完整SBOM。当前原生Windows测试平台为Windows11 ARM64；上述1903特性说明不构成更低系统实际验收。

不增加ASCII安装目录替代、跳过/重试/continue-on-error、限时放宽或全局代码页设置。原生产releases.yml、Android验收、Rust源码/锁文件、版本与单英文Draft PR不变。

## SDK 写入失败的最小修复

12f77b504双事件实际使用SDK 10.0.26100.0/arm64/mt.exe，提取与验证SUCCESS，写入报告中的EhViewer.exe资源时返回c101008d / Access is denied；无readback或窗口receipt。完整日志和实际失败包保留。Push实际ZIP中的启动器Unix属性为0444，但ZIP不证明原生Windows DOS标志及全部根因；准确结论以新SHA前后属性证据为准。

仅在新建报告目录的自有启动器副本上读取DOS属性、记录read_only与Files.isWritable，清除read-only，再记录并严格要求可写，之后执行原SDK验证/嵌入/读回。原Gradle镜像、用户文件、ACL、系统设置均不修改；不重试、不忽略失败、不缩短Unicode目录、不放宽原所有权/架构/窗口门禁。成功receipt保留前后标志；失败时保留属性JSON和实际副本，以便区分只读与其他拒绝访问原因。此修改仅Kotlin平台文件API，无新增依赖或Animeko精确复用。

## 本地验证

2f00e595c远程结案：Push40756676048与PR40756686761均完整SUCCESS。两份实际包各35 Aarch64 PE与九材料逐SHA匹配，Core/JVM证明与独立实物逐字节一致。原DOS只读true/不可写经自有副本修改变false/可写；SDK10.0.26100.0 ARM64嵌入/读回及原语义保持PASS，读回SHA2ea9c580343f8885cc19041832fb12243eb1623e634236e46d4ae30000b815fd。两次Unicode/空格目录真实包内MicrosoftARM JVM25.0.5.0.2/GetACP65001/UTF-8/SOFTWARE_COMPAT/1frame/2000/live0/错误释放关闭均PASS。完整运行链接/工件ID/官方摘要及其他平台证据见ledger；这不覆盖更早失败。

工具全套128项/零失败错误跳过、格式/build/installDist通过；覆盖权限/DPI/身份/兼容配置保留、SDK读回篡改拒绝、命名空间幂等、DOCTYPE/歧义拒绝。对实际CI启动器提取的原清单执行同一Kotlin CLI，新增UTF-8后其余设置通过语义核验，输出SHA ac65a7077e8afa025939788231a2fd2e701ffbd0b630aee7d12b7c58d8507ac5。这不替代Windows SDK嵌入或实际启动。

Intel桌面编译成功（28s）、重建DMG/格式成功（1m21s）、原完整Android319任务成功（1m54s）。最终工具全量在保留包顺序调整后再通过（45s）。原Native/四APK/snapshot预检、真实DMG两次METAL窗口与4,170项/13归档样例均PASS；刚构建实际JAR在本机ARM真实Rust C ABI亦2,000/live0/全部错误释放关闭PASS。新SHA CI结果继续写入ledger；未实测的平台保持未验收。
