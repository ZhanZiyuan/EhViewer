# ADR 0008：Windows ARM64 JVM 的 Rust C ABI 所有权调用

状态：75b36b2ef双事件全部九job/gate及所有实际工件SUCCESS，Windows原生JVM Unicode/C ABI证明取得完整门禁。7f1afefe7参数传输失败、72a5ce23a完整门禁的容量取消/上传DNS失败及本机iOS27系统boot阻塞均保留。前置47ebcc959的接受证据见ledger。M1整体IN_PROGRESS，应用版本冻结1.15.10 / 180074。

## 独立范围

既有Desktop JNI探针使用Unix fd，Windows不能直接套用这个接口。本切片从原生JVM调用已在iOS采用、且Windows39项原生测试通过的同一五函数C ABI，验证借用输入、Rust所有权ID、复制、错误和释放。保持原Android/Mac/Linux JNI接口、Core归档/图片模块、原完整测试和全部九job。此处不交付Windows包内JVM/Skiko窗口或最终ZIP；它们在后续独立切片验证。

自主实现均为Kotlin：`app/desktop/.../RustCapiProbe.kt`、Gradle JavaExec任务、`tools/verification/.../WindowsJvm.kt`及解包回归。YAML只编排已有工具。Rust使用既有`ehviewer_capi`源码与锁文件，以Cargo的cdylib输出覆盖构建动态库，不改变C ABI版本/声明，不新增Rust依赖。CI先完整执行原39项Core/C ABI、fmt、严格Clippy，成功后才执行新的必选JVM步骤；任一步失败都会使原Windows job与总gate失败，无重试/continue-on-error。

## 输入与加载边界

| 来源 | 独立决策 | 许可与验证 |
|---|---|---|
| [Microsoft OpenJDK下载](https://learn.microsoft.com/en-us/java/openjdk/download) | Windows ARM64 25.0.5.0.2，官方ZIP SHA-256 `f227cc459fbd65a42c1b28123d68e207eb500899c1c78238f21a038cd33688a3` | 本机已真实下载并核对官方摘要，java/JVM/jlink/jpackage和GUI/console启动器均Aarch64 PE；GPLv2+Classpath Exception材料独立读取。CI重新下载/校验，不能依赖本机目录；完整SBOM仍未完成 |
| [OpenJDK25 Windows loader源码](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/hotspot/os/windows/os_windows.cpp) | `os::dll_load`调用LoadLibrary，不能假定绝对路径能解析同目录传递DLL | 仅研究接口行为，不复制JDK实现；使用独立Kotlin平台接口 |
| [LoadLibraryExW](https://learn.microsoft.com/en-us/windows/win32/api/libloaderapi/nf-libloaderapi-loadlibraryexw) | UTF-16完整路径，DLL_LOAD_DIR与DEFAULT_DIRS合并0x1100；避免依赖构建用vcpkg PATH | Core测试后的实际DLL逐个摘要/PE复核，复制到含Unicode与空格的自建目录；通过GetModuleFileNameW核对真正加载路径 |
| [GetProcAddress](https://learn.microsoft.com/en-us/windows/win32/api/libloaderapi/nf-libloaderapi-getprocaddress)、[FreeLibrary](https://learn.microsoft.com/en-us/windows/win32/api/libloaderapi/nf-libloaderapi-freelibrary) | 精确查找五个导出，只释放自己持有的LoadLibraryEx引用 | Arena管理调用内存，异常清理与正常关闭明确；不释放借用的GetModuleHandle引用 |
| [JDK25 Linker.Option](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.Option.html) | 捕获GetLastError，防止Java运行时覆盖；普通downcall，不使用critical提示 | Windows状态布局/真实调用须由原生runner验证，不能用本机Mac阳性代替 |

Native构建依赖与版本保持ADR0007，复用同一干净源SHA对应的Core报告和实际DLL摘要。JDK ZIP先核对固定SHA，再以独占创建文件解包，拒绝目录逃逸、drive/backslash路径、既有目标和超预算内容。回归验证路径越界与保留既有所有者文件，不把ZIP测试fixture当作可运行JDK。

CLI用固定JDK直接运行原Gradle Wrapper JAR，避免cmd二次解析。7f1afefe7真实CI证明仅用ProcessBuilder仍不足：Java原生编码Cp1252，调用器收到的Unicode目录变为`??`并在Path解析失败，尚未到达Rust。独立修复在helper→Gradle与Gradle→调用器两跳均用UTF-8/Base64 ASCII传路径，在调用器内严格解码并使用原真实Unicode目录，保留宽字符加载/真实路径核对。普通本地Gradle路径属性由任务编码；不修改系统代码页、目录或测试。[JDK25启动器规范](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html)亦说明参数文件有系统编码约束，此处不假定所有启动边界都保持Unicode。失败日志、实际DLL/输入许可与完整门禁继续保留。

FFM源码只在Desktop模块以JDK25编译，Java17字节码目标保留；原验证工具仍可由Windows镜像的原生JDK21启动，不加入FFM编译时依赖。子进程环境只对该调用生效，JVM不继承Core构建临时添加的vcpkg DLL路径。

## 真正调用与证据

生成头的实际函数为abi_version、hash_sha1、buffer_copy、buffer_free、active_buffers；int32错误码与64位size_t/u64按已验证头声明映射。2,000次轮换ASCII、空/null输入、二进制与Unicode UTF-8，结果与独立JVM SHA-1计算比较。每次实际验证长度查询、过小容量不写输出、完整复制、释放、双重释放/失效ID拒绝，最后检查active=0。DLL关闭后才写PASS报告；失败没有成功receipt。

本地Mac ARM64/JBR25.0.3真实Rust动态库与FFM调用已PASS：2,000结果、active0、错误/释放检查和库关闭均成功。这只验证通用调用/ABI。随后75b36b2ef双事件Windows原生Microsoft ARM64 JVM25.0.5.0.2/Cp1252、真实Unicode路径/宽字符加载/GetLastError捕获、2,000/live0/全部错误释放关闭实际PASS，真实DLL的ARM64 PE与SHA独立核验；全部九job/gate及实际工件成功。既有原生Core39通过不等于JVM调用通过，JVM调用通过也不等于Compose窗口/ZIP或完整产品P0通过。

本地工具121项、原完整Android tests/lint/build/四APK预检、Mac ARM64 JNI与Intel同包真实窗口/归档复验均通过。首次并发GUI检查的DMG超时保留，同字节串行复验通过；iOS两target编译成功，但本机唯一iOS27.0 runtime两次在应用安装前boot超时，第二次证据停在Apple系统数据迁移器。本切片不改iOS源码，不将该本地启动标为成功；原有CI的iOS26.5完整启动/生命周期/FFI仍必选，必须独立通过。完整日志、清理与未确认根因见ledger。

沿用M0中Animeko平台边界映射，不新增其代码/资源/脚本/依赖树复用；具体Native协议和调用器为EhViewer独立设计。原AGPL来源处理结论不变。所有提交和PR元信息使用英文，PR继续Draft，不创建tag/Release。
