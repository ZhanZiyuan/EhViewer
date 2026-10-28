# ADR 0006：Windows ARM64 前置的可移植归档文件接口

状态：本地验证 SUCCESS，999418419 的 PR Linux 运行门禁 FAILED，独立环境修复进行中，不能放行。M1 整体 IN_PROGRESS；本切片不宣称 Windows 运行时通过。前置 c787c570e 的双事件全部八 job/gate 和实际工件已 SUCCESS，记录同一 Draft PR 后开始。

## 范围与独立实现

原 `ehviewer_core` 的归档写入及测试直接导入 Unix `RawFd`，`fd` 模块使用 `fcntl`，libarchive 声明使用 Windows 不提供的 `libc::mode_t`。此前 Windows ARM64 库检查真实失败于这几处平台边界。

保留原 Unix `write_zip`/`archive_write_open_fd` 路径和完整 fd/offset 回归，增加 `write_zip_file(&mut File)`。两种入口共享原校验、ZIP/store 和读写逻辑；File 路径用 libarchive 输出回调，借用文件直到 writer free，保留实际 I/O 错误，阻止 panic 跨 C 边界。仅 Unix 的 fd 工具按其真实平台能力导出；归档、图片、GIF、解析及全部 Core 功能没有禁用。

新增跨平台回归验证 Unicode 名称、输入当前偏移、已有输出前缀、输出当前位置、调用方句柄所有权、只读输出失败和危险路径拒绝。原映射/lease/归档格式/加密/并行测试继续执行；只有 Unix fd 专属用例由 `cfg(unix)` 标明适用范围，未忽略跨平台归档测试。

## ABI 与来源

独立读取固定版本 [libarchive 3.8.7 archive_entry.h](https://github.com/libarchive/libarchive/blob/v3.8.7/libarchive/archive_entry.h)：Windows 3.x 的 `__LA_MODE_T` 是 `unsigned short`，Unix 是 `mode_t`；`archive_entry_set_filetype` 参数始终为 `unsigned int`。分别声明 Mode 与 setter 类型，避免把 Windows CRT fd 误当成 Rust/Win32 文件句柄。回调契约依据同版本 [archive.h](https://github.com/libarchive/libarchive/blob/v3.8.7/libarchive/archive.h)。没有维护 C/C++/Java/Swift 或 Shell 实现，没有升级依赖。

此 Native 适配不来自 Animeko；M0 的平台接口边界结论只用于识别职责。实际修改为 EhViewer 自己的 `native/rust/core/src/{lib,archive}.rs`、`archive/sys.rs` 和 `tests/archive.rs`，未读取或复制参考项目的新实现、脚本、资源及版本树。无 AGPL 精确复用；现有 Native 分发义务与完整依赖审计仍由 M4/M6 跟进。

## 验证边界

本机 macOS ARM64 的完整 Core、C ABI、JNI、三个 Android target 严格 Clippy、原 Android tests/lint/build 及 Intel DMG/host 回归必须成功，再独立提交并通过全部既有 push/PR CI。命令与精确结果见 ledger。

Windows `cargo check --locked -p ehviewer_core -p ehviewer_capi --lib --target aarch64-pc-windows-msvc` 只检查完整库源码。尝试 `--all-targets` 真实失败于现有 reqwest 测试依赖 aws-lc-sys 缺 `windows.h`（本机没有 Windows SDK），保留日志 `/tmp/ehviewer-windows-file-crosscheck.log`；不得据库检查或 macOS 测试宣称 Windows 测试成功。后续独立 Windows runner 切片须真实构建/链接依赖、执行完整适用测试，再证明 JVM/Skiko/Rust 窗口运行；不通过禁用归档/图片、替换假实现或忽略用例来放行。

Windows ARM64 runtime、最终 ZIP、最低 OS、全部 P0 和完整 Native 许可证/SBOM 仍 NOT_VERIFIED。稳定版本继续冻结为 1.15.10 / 180074。

本机最终源码：Core19+15、C ABI6、JNI5、fmt/三个 Android 严格 Clippy、完整 Android tests/lint/build/实际四包预检、工具118项测试、Intel DMG 安装/两次真实启动/32 Native/六份材料、Intel与ARM host各4,170项+13样例、iOS两target构建/Simulator27.0完整生命周期及两次2000/live0均SUCCESS。源为c787c570e+dirty；不替代新提交的干净源码双事件CI。详情见ledger。

999418419首次远程：push Linux两次真实启动/完整回归PASS，PR第二次GL context初始化失败并超时，整轮不放行；两者实际Rust/Skiko字节一致。失败属于实际观察，不据此断言File接口或驱动根因。暂停Windows后续功能，先独立修复无GPU CI的显式渲染环境并重跑完整门禁；记录见ADR0005/ledger。
