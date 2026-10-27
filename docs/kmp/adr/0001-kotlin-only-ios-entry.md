# ADR 0001：Kotlin-only iOS executable 启动 Spike

状态：IMPLEMENTED / LOCAL_VERIFIED / CI_PENDING。前置 M0 提交 `1134f2b1c20740fe0002ee8b48c33482d90f1ad1` 的 push/PR CI 与候选四包预检均 SUCCESS，详见 ledger。

问题：KMP framework 和 ComposeUIViewController 不是完整 iOS 应用。Animeko 本地快照 `app/shared/application/src/iosMain/kotlin/ios/AniIos.kt` 提供 Kotlin controller，但 `app/ios/Animeko/ContentView.swift` 仍承载 SwiftUI 容器。不能照搬其入口，也不采用其 CocoaPods、视频 Native、C++/Swift 补丁。

方案：`app/ios` 只用 Kotlin/Native executable、Kotlin UIKit delegate、UIApplicationMain 与 CMP 启动画面；Gradle Kotlin DSL 打包 `.app`，Simulator 显式 ad-hoc codesign，不使用开发者或生产身份。Info.plist 展示版本从现有 Android 权威版本源生成，不提前升版。没有复制参考项目代码/资源/脚本；借鉴范围仅为平台入口与共享 UI 的边界。

依据：[Native binary declarations](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html) 声明 executable 支持 Native targets；[CMP entry points](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-entry-points.html) 说明 UIViewController 集成。这些资料不代替实测可启动应用。

验证：本机与 macos-latest ARM64 CI 编译真实 Simulator executable，核对 Mach-O arm64、bundle/version/signature，创建独立的临时 Simulator，真实安装/启动，等待 Compose ready 日志并截屏，再验证后台/前台/冷启动。所有失败均使 job/gate 失败，finally 清理仅由本次创建的 Simulator。Android 原回归检查完整保留。验证结果与工具链/精确 SHA 待运行后追加。

边界：这不是 iOS P0 实现，不是 unsigned IPA，不是开发签名真机验收。Rust C ABI、iosArm64 分发、11 包和设备验收仍待后续独立提交。若 executable/UIKit/资源或生命周期失败，保留复现并阻塞该切片，不引入 Swift/ObjC 来绕过。

声明式 MinimumOSVersion=15.0 只是本次编译下限，尚未证明 iOS 15 设备支持。新模块显式使用现有 CMP 1.11.1 的 UI/foundation/runtime，避免 Material 3 1.9.0 的传递依赖落到旧 runtime；不改变原 Android 依赖或全仓版本。Core 四模块仍只有 Android target，原 target 禁止检查保持，报告键改为 shared_core_android_only 以免误称整个产品 Android-only。

本地首次编译（2m25s）失败：UIKit 可选 window 绑定不能 override property，以及 Kotlin class 不能当作 ObjCClass 参数。改为私有强引用持有 window、`ExportObjCClass(name=...)` + UIApplicationMain 显式类名；依据 [ExportObjCClass API](https://kotlinlang.org/api/latest/jvm/stdlib/kotlinx.cinterop/-export-obj-c-class/) 的运行时注册能力。验证器 107 tests 已通过，入口启动仍待真实运行。

后续实测：ObjC UIKit 初始化 delegate 触发 MissingInitImp，已用 SDK 实际提供的 `ObjCObjectBase.OverrideInit` 显式导出 init；参照 [Objective-C initializer overriding](https://kotlinlang.org/docs/native-objc-interop.html)。随后真实安装、启动、Compose 画面与截图成功；但切入 Settings 后后台断言失败。保持该断言，改用显式 Kotlin UIWindowSceneDelegate + plist 场景配置，把窗口与 active/background 事件交给场景；完整生命周期重跑仍待结果。没有使用 Swift 或放宽语言政策。

最终本地结果：显式 SceneDelegate 方案在 iOS 27.0 / iPhone 18 Pro Simulator 通过冷启动、Compose ready、真实画面、background → active、终止后第二次冷启动，日志中的两个 PID 分别为 74764/75004。清理完整成功。iosArm64 与 iosSimulatorArm64 executable 均真实编译；109 verifier tests、Android 原完整回归与四 APK 预检全部成功。新 SHA 的 macos-latest ARM64 CI 仍待执行；真机、最低 OS、P0 与分发仍未验证。当前实现代码不依赖本地 Animeko。
