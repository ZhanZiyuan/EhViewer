# P1–P4 迁移记录

2026-10-10，在 `feature/rusty` 上基于 main/HEAD `c5156a2de34e08fe9d11acd0f7552f6b8189a5db` 实施。按用户最新授权完成 P1–P4，保留 KMP/CMP 且仅有 Android target。附件中的“禁止 KMP”和“每次只做一个阶段”不覆盖这次明确授权。P0 的原始证据保留在 ../baseline.md，没有改写历史基线。

## 最终配置

- Gradle 9.7.1 / AGP 9.3.1 / JDK 25.0.3；Android Java/Kotlin target 17。保留 Gradle Kotlin DSL、现有 Gradle JVM 配置与内置 Kotlin，没有采用替代构建系统。
- minSdk 26 / compileSdk 37 / targetSdk 37；删除 api/default/marshmallow Flavor，保留 debug/release/benchmarkRelease 及插件生成的 nonMinifiedRelease。
- 默认 applicationId `moe.tarsin.ehviewer`、versionCode `180064`、Release keystore/alias 和证书不变。Debug `.debug`、benchmark `.benchmark` 不变。
- Release 输出 `app/build/outputs/apk/release/app-{arm64-v8a,armeabi-v7a,universal,x86_64}-release.apk`。ABI 配置与调用任务无关，单独 Debug 也生成三 splits + universal，代价是 Debug 构建量增加。
- 保留 CMake、libarchive、liblzma、Nettle、WebP、Corrosion 及原有 C/Rust JNI 实现；P5–P8 未执行。

## 分阶段报告

- [P1 工具链](P1.md)
- [P2 SDK/Flavor](P2.md)
- [P3 Android 行为与设备测试](P3.md)
- [P4 CI/供应链](P4.md)
- [实际变更文件清单](files.txt)
- [执行证据](evidence/)

## 验证与边界

最终标准构建通过（2m10s）；五API各8项Android回归通过（40次），Rust3项通过/3项联网测试过滤，三ABI Clippy通过，Baseline Profile生成1项通过/2项性能benchmark跳过。Lint刷新后47项warning、0 error。结果见四份报告及 evidence/execution-summary.json、原始日志和APK校验JSON。任何 `NO-SOURCE` 任务均不计为单元测试用例。API37 使用 Android 17 Canary 20261007（设备报告 SDK37、16KB，镜像元数据 API37.2/CANARY），不是正式版认证。没有执行远程 GitHub Actions、push、tag、commit、merge 或发布。

用户数据验证限于专用 API26 AVD 的旧签名 APK → 新 APK `install -r`、合成 files 标记保留和启动；没有接触真实用户数据，未声称验证历史数据库全版本迁移或 `.m` 包跨包迁移。现有 `.m` 安装不能被默认包覆盖；用户需先备份/导出后另行迁移。

P0 的七字节 GIF 越界和非 ASCII 密码 JNI 缺陷仍存在，普通回归通过不代表这些问题已修复。RAR5、加密7z、巨型归档、并发、安全资源边界仍属于后续 Native 阶段。真实账号登录、生产 TLS/CT/ECH、长时间下载/通知、折叠屏/平板仍需完整功能验收。

## 复跑

```sh
# 使用仓库 Wrapper；JAVA_HOME 指向 JDK25，ANDROID_HOME 指向 SDK。
./gradlew :build-logic:convention:check spotlessCheck \
  :app:testDebugUnitTest \
  :core:common:testAndroidHostTest :core:data:testAndroidHostTest \
  :core:i18n:testAndroidHostTest :core:ui:testAndroidHostTest \
  :app:lintRelease :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:assembleRelease :benchmark:assembleNonMinifiedRelease \
  -Prelease --console=plain --no-daemon
python3 -m unittest discover -s scripts -p 'test_*.py' -v
python3 scripts/verify_bytecode.py
python3 scripts/verify_apks.py app/build/outputs/apk/release --sdk "$ANDROID_HOME"
python3 scripts/verify_apks.py app/build/outputs/apk/debug --sdk "$ANDROID_HOME" \
  --debug --application-id moe.tarsin.ehviewer.debug
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  moe.tarsin.ehviewer.debug.test/androidx.test.runner.AndroidJUnitRunner
```

首次构建需要 autoconf/autoheader、make、CMake、SDK37/BuildTools37、NDK29、指定 nightly 和三个 Rust targets。`.java-version` 为25；不要以 Android target17 推断运行 JDK17。本机 Gradle 缓存为 `/private/tmp/ehviewer-p0-c5156a2/gradle-home`；本轮临时输出 `/private/tmp/ehviewer-p1-p4`。

## 回滚

没有提交，HEAD 仍是原提交。`rollback-production.patch` 保存本轮生产配置、源码、资源移动和新增测试/脚本的完整差异，不包含 P0/P1–P4 报告。回滚前先保留后续编辑和审计证据，然后执行只读检查：

```sh
git apply --reverse --check docs/modernization/p1-p4/rollback-production.patch
# 仅在决定回滚、且检查通过后：
git apply --reverse docs/modernization/p1-p4/rollback-production.patch
```

若之后单独提交这些阶段，使用普通 `git revert` 回退对应提交。不要 reset --hard、clean、强推或覆盖恢复归档。环境安装和 ignored 构建缓存不随源码回滚；专用 AVD 均命名 `ehviewer-p*`、存放于临时目录，没有修改用户已有 AVD。

环境副作用：补装autoconf2.73、actionlint1.7.12及其ShellCheck0.11.0依赖；下载API26/29/33/35与Android17 Canary16KB镜像，创建5个专用AVD，全部已关闭。AVD29默认12GB分区首次因空间不足启动失败，改为专用2GB分区后通过；原有用户AVD未改动。旧API缺少getconf时，CI从/proc实际映射读取页大小，API26回退实测4096。SDK和ignored构建缓存占用磁盘，不随源码回滚。
