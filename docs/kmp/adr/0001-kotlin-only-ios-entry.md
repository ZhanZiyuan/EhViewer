# ADR 0001：Kotlin-only iOS executable 启动 Spike

状态：IMPLEMENTED / LOCAL_VERIFIED / CI_FAILED（8388fb366、5b6745f43，修复中）。前置 M0 提交 `1134f2b1c20740fe0002ee8b48c33482d90f1ad1` 的 push/PR CI 与候选四包预检均 SUCCESS，详见 ledger。

问题：KMP framework 和 ComposeUIViewController 不是完整 iOS 应用。Animeko 本地快照 `app/shared/application/src/iosMain/kotlin/ios/AniIos.kt` 提供 Kotlin controller，但 `app/ios/Animeko/ContentView.swift` 仍承载 SwiftUI 容器。不能照搬其入口，也不采用其 CocoaPods、视频 Native、C++/Swift 补丁。

方案：`app/ios` 只用 Kotlin/Native executable、Kotlin UIKit delegate、UIApplicationMain 与 CMP 启动画面；Gradle Kotlin DSL 打包 `.app`，Simulator 显式 ad-hoc codesign，不使用开发者或生产身份。Info.plist 展示版本从现有 Android 权威版本源生成，不提前升版。没有复制参考项目代码/资源/脚本；借鉴范围仅为平台入口与共享 UI 的边界。

依据：[Native binary declarations](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html) 声明 executable 支持 Native targets；[CMP entry points](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-entry-points.html) 说明 UIViewController 集成。这些资料不代替实测可启动应用。

验证：本机与 macos-latest ARM64 CI 编译真实 Simulator executable，核对 Mach-O arm64、bundle/version/signature，创建独立的临时 Simulator，真实安装/启动，等待 Compose ready 日志并截屏，再验证后台/前台/冷启动。所有失败均使 job/gate 失败，finally 清理仅由本次创建的 Simulator。Android 原回归检查完整保留。验证结果与工具链/精确 SHA 待运行后追加。

边界：这不是 iOS P0 实现，不是 unsigned IPA，不是开发签名真机验收。Rust C ABI、iosArm64 分发、11 包和设备验收仍待后续独立提交。若 executable/UIKit/资源或生命周期失败，保留复现并阻塞该切片，不引入 Swift/ObjC 来绕过。

声明式 MinimumOSVersion=15.0 只是本次编译下限，尚未证明 iOS 15 设备支持。新模块显式使用现有 CMP 1.11.1 的 UI/foundation/runtime，避免 Material 3 1.9.0 的传递依赖落到旧 runtime；不改变原 Android 依赖或全仓版本。Core 四模块仍只有 Android target，原 target 禁止检查保持，报告键改为 shared_core_android_only 以免误称整个产品 Android-only。

本地首次编译（2m25s）失败：UIKit 可选 window 绑定不能 override property，以及 Kotlin class 不能当作 ObjCClass 参数。改为私有强引用持有 window、`ExportObjCClass(name=...)` + UIApplicationMain 显式类名；依据 [ExportObjCClass API](https://kotlinlang.org/api/latest/jvm/stdlib/kotlinx.cinterop/-export-obj-c-class/) 的运行时注册能力。验证器 107 tests 已通过，入口启动仍待真实运行。

后续实测：ObjC UIKit 初始化 delegate 触发 MissingInitImp，已用 SDK 实际提供的 `ObjCObjectBase.OverrideInit` 显式导出 init；参照 [Objective-C initializer overriding](https://kotlinlang.org/docs/native-objc-interop.html)。随后真实安装、启动、Compose 画面与截图成功；但切入 Settings 后后台断言失败。保持该断言，改用显式 Kotlin UIWindowSceneDelegate + plist 场景配置，把窗口与 active/background 事件交给场景；完整生命周期重跑仍待结果。没有使用 Swift 或放宽语言政策。

最终本地结果：显式 SceneDelegate 方案在 iOS 27.0 / iPhone 18 Pro Simulator 通过冷启动、Compose ready、真实画面、background → active、终止后第二次冷启动，日志中的两个 PID 分别为 74764/75004。清理完整成功。iosArm64 与 iosSimulatorArm64 executable 均真实编译；109 verifier tests、Android 原完整回归与四 APK 预检全部成功。新 SHA 的 macos-latest ARM64 CI 仍待执行；真机、最低 OS、P0 与分发仍未验证。当前实现代码不依赖本地 Animeko。

远程结果修正：8388fb366 的 push/PR 均在 Simulator smoke 失败；Android 与两个 Native executable 编译通过。PR 在 terminate 时已无进程，push 后台标记缺失且截图显示主屏幕。原 5 分钟滚动日志和累计标记不足以识别每个进程的退出原因，因此不能据此认定 UIKit 或 Simulator 的根因。

修复切片：按 [Apple UIApplicationMain 参数约定](https://developer.apple.com/documentation/uikit/uiapplicationmain(_:_:_:_:)-1yub7)，由 NSProcessInfo 提取包含 executable 的真实参数，在 memScoped 内创建带尾部 null 的 argv，并覆盖整个 UIApplicationMain 生命周期。验证器捕获每次冷启动独立的 stdout/stderr，解析 simctl 返回的 PID，等待每个进程的 Compose/active 标记；后台/恢复必须保持同一存活 PID，严格 terminate 必须成功且进程退出，第二次启动必须是不同 PID。统一日志仅用于诊断，不再用跨进程累计计数证明生命周期成功。失败保留阶段、环境、输出和系统日志。未复制 Animeko 实现，未更改依赖或 runner，未减少任何生命周期或 Android 断言；修复效果必须重新在本机和两条 CI 验证。

修复后本地验证：两 ARM64 executable 编译成功、110 verifier tests 成功、Android 原完整回归与四包预检成功。首次日志重定向到 Documents 触发 launchd_sim 创建进程前 EPERM，已保留失败并改用应用自身沙箱 tmp，清理前复制输出。随后完整 smoke 在 iOS 27.0 成功，前后台 PID=28131、严格终止后第二冷启动 PID=28247，截图已检查，测试设备完整清理。远程修复效果仍待新 SHA CI；不将本地通过解释为原远程故障根因已确认。

5b6745f43 的新 stderr 确认一个实际启动缺陷：iOS 26.5 / iPhone 17 Pro / Xcode 26.6 上，Compose PlistSanityCheck 报缺少 `CADisableMinimumFrameDurationOnPhone=true`，系统随后记录 SIGABRT。按 [Compose UIKit integration](https://kotlinlang.org/docs/multiplatform/compose-uikit-integration.html) 和 [Apple plist 键定义](https://developer.apple.com/documentation/bundleresources/information-property-list/cadisableminimumframedurationonphone)补充声明式 plist，不设置 enforceStrictPlistSanityCheck=false。验证器启动前预检该字段，并允许通过显式 `--runtime` 复验 runner 系统；默认仍选最新可用系统，指定不可用系统不回退。此为独立 M1-003 修复；本地分别复验 26.5 与 27.0，远程需新 SHA 再全部通过。参考项目的源码、资源、plist 与脚本均未复制。

M1-003 本地结果：111 verifier tests、两 Native target 打包/检查、原完整 Android 回归与四包 dry-run 全部成功。缺失键和 false 两种真实应用副本均在创建 Simulator 前被拒绝。26.5 的完整生命周期 PID=37219 → 37340，27.0 为 38277 → 38370；两者恢复均沿用第一 PID，严格终止后新进程完成 Compose/active，截图已检查、清理成功。新提交的远程全量结果仍待执行，真机和 P0 尚未验证。
