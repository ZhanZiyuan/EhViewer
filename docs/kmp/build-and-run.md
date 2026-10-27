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

`ios-startup` 核对 Mach-O arm64 / IOSSIMULATOR、bundle、签名和 source 状态；只创建并使用本次 UUID 命名的独立 Simulator，不复用、擦除或删除用户已有设备。机型来自 runtime 的 supportedDeviceTypes。实际安装、启动、等待 Compose ready、截图、切入 Settings、回前台、终止与第二次冷启动后，保存 JSON / 生命周期日志 / PNG，再 shutdown/delete 本次设备。任何断言或清理失败均使检查失败。输出目录必须不存在；重复运行请选择新的报告目录，工具不会覆盖旧证据。

本地实测为 Xcode 27.1 + iOS 27.0 / iPhone 18 Pro；Info.plist 的 15.0 是声明的编译下限，没有验收 iOS 15 设备。CI 使用 macos-latest ARM64，限制并发和编译内存；逐 SHA 的远程结论见 ledger。

此切片只有启动探针画面。图库、登录、阅读、下载、Rust C ABI、开发签名真机与 11 包分发仍需后续工作，不能把探针当作 iOS 完整产品。
