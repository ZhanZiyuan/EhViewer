# ADR 0003：Kotlin/Native ↔ Rust 所有权 C ABI Spike

状态：IMPLEMENTED / LOCAL_VALIDATION。前置 8f12b329d 的 push/PR 全部 Android、iOS、Linux/macOS JNI、候选四包与 gate 均 SUCCESS 后才开始。M1 整体仍 IN_PROGRESS。

目标：在真实 Kotlin-only iOS 应用里调用同一 EhViewer Rust Core，完成分配、复制和释放，并验证错误边界。不是 iOS 归档/图片/下载 P0，也不是 unsigned IPA、开发签名真机或最低系统验收。

来源与差异：沿用 M0/M1 对 Animeko `app/shared/application/src/iosMain/kotlin/ios/AniIos.kt` 的入口/共享 controller 边界审计；其 Swift 容器和视频 Native 栈没有被移植。本切片实现来自 EhViewer 自身 `native/rust/core/src/hash.rs` 及官方 [Kotlin C interop](https://kotlinlang.org/docs/native-c-interop.html)、[Cargo JSON 消息](https://doc.rust-lang.org/cargo/reference/external-tools.html)、[cbindgen](https://github.com/mozilla/cbindgen) 接口约定。实际落点是 `native/rust/capi`、`app/ios` 的 cinterop/启动探针与 `IosStartup.kt`；未复制 AGPL 代码、资源、脚本或依赖树。

ABI v1：仅借入当前调用期内有效的输入/输出指针。Rust 结果由单调递增 u64 句柄管理，调用方先查询长度再复制，最后 free；不暴露 Rust 堆分配地址，不允许 Kotlin 用另一个 allocator 释放。未知、0、过期或重复释放返回明确错误。单次输入上限 8 MiB，最多 4096 个固定 40-byte hash 结果；容量不足不写输出，结果保留给调用方重试/释放。注册表受 Mutex 保护，ID 不复用，panic 在 Rust 边界捕获；无效的非空指针不能被运行库可靠探测，调用方仍必须遵守 Rust 函数中的安全不变量。算法复用原 SHA-1 缓存身份逻辑，不用于认证。

生成与语言政策：固定 cbindgen 0.29.4（官方 metadata：MPL-2.0，build-only、关闭默认 clap feature）从 Rust 声明自动生成头；头只存在于 Cargo OUT_DIR / Gradle build，不提交人工 C/C++/ObjC 业务实现。`.def` 只有声明性配置，Kotlin/Native 生成绑定。Gradle 根据 Cargo 当前构建的 compiler-artifact 与 build-script-executed 消息精确选择 archive/header，不猜缓存目录或读取 Animeko。构建失败/超时抛错，保留 JSON/诊断日志；C ABI 静态库分别编译 aarch64-apple-ios / aarch64-apple-ios-sim，不混用平台。开启 C interop commonization 供共享 iosMain 使用同一绑定；未改变四个 Core KMP 模块的 Android target。

新增构建依赖的 Cargo 官方 SPDX metadata 已逐项读取（不是法律兼容性结论）；只增加构建工具图，未升级既有 crate，三个已有依赖引用仅为多版本消歧：

| 新增版本 | 声明许可证 |
|---|---|
| cbindgen 0.29.4 | MPL-2.0 |
| winnow 0.7.15 | MIT |
| errno 0.3.14、fastrand 2.5.0、heck 0.5.0、serde_spanned 1.1.2、tempfile 3.27.0、toml 0.9.12+spec-1.1.0、toml_datetime 0.7.5+spec-1.1.0、toml_writer 1.1.3+spec-1.1.0 | MIT OR Apache-2.0（fastrand 的元数据顺序相反） |
| linux-raw-sys 0.12.1、rustix 1.1.6 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT |

验证：Rust 六项测试涵盖已知向量、短/空输出、无效借用参数、容量/过期 ID、并发释放与 panic 边界。保留原 Core/JNI/三 Android targets 的严格检查。Kotlin 启动探针在每次冷启动实际完成 2000 个结果的创建/查询/复制/释放，检查重复释放错误及 Rust live count=0，再输出带实测数字的 marker。验证器分别解析两次冷启动的 stderr，只接受各至少 2000、live=0 的唯一证据；拒绝缺失、重复、不完整与未清理结果。保留原画面、前后台、严格终止和新 PID 冷启动断言。

本地结果：Rust C ABI 6/6、原 Core 19 unit + 14 integration（3 个既有在线 ignored 未改）、JNI 5/5、fmt、三 Android target `-D warnings` Clippy 全部 SUCCESS。两 Apple executable 的初次链接/打包 SUCCESS（45s）。新增证据 data class 后因 ktlint 文件命名规则失败（12s），按原规则重命名文件，未抑制 lint；重跑 SUCCESS（21s）。诊断保留与最终安全契约复验亦 SUCCESS。cinterop task 显式声明 archive 内容输入，确保头不变的 Rust 实现变更也触发绑定重建。

最终 verifier 114 tests、完整 Android 315 tasks、原全部 Native/APK 校验与真实四包测试签名 snapshot、Desktop JNI 4,170 项 + 13 样例均 SUCCESS。最终同一 executable `8b85eb438c2b96b93d82c798a72444ce255aa81a715822dfd55ff6c41f83a027` 在 iOS 26.5 / iPhone 17 Pro 与 iOS 27.0 / iPhone 18 Pro 均完整 smoke SUCCESS；每个系统两次冷启动各完成 2000 个结果，live=0，原生命周期/严格终止、新 PID、截图与清理全部通过。报告路径与 PID 见 ledger。均为提交前 dirty source，新 SHA CI 尚未执行。

边界：保留原 Android/JVM JNI 和 staticlib 方式，没有用空接口替代归档能力。此 ABI 仅证明最小真实 Core 调用与端到端所有权；完整 Apple 归档/WebP 原生依赖仍属 M4，其他平台 runtime/发布与 iOS P0 后续单独验收。版本仍冻结 1.15.10 / 180074。
