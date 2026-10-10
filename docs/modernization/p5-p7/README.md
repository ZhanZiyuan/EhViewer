# P5–P7 实施与验收记录

2026-10-10，分支 `feature/rusty`，HEAD 和本地 main 均为 `c5156a2de34e08fe9d11acd0f7552f6b8189a5db`。先读取 Git 状态、真实配置和用户两份参考文档，再保留进入本阶段的 P0–P4 未提交改动进行迁移。本次完成 P5–P7 实现；未实施 P8/P9，未提交、推送、合并、创建 Tag 或发布 APK。PRD 的真实性能和全部格式覆盖要求仍有下述未验证项，不将实现完成等同于所有产品验收通过。

## 修改与兼容性

- [P5](P5.md)：SHA-1、自然排序、GIF 改为 Rust；删除应用自行维护的 C 算法及旧 JNI wrapper。
- [P6](P6.md)：libarchive 安全 FFI、RAII、ArchiveSession、密码、上下文池、归档读写及缓冲生命周期。
- [P7](P7.md)：`ehviewer_core` 与 `ehviewer_rust` 分层；Core 没有 JNI/NDK 依赖；Android 适配集中在 JNI；CMake 链接 Rust staticlib 和保留的第三方 C 库。
- [files.txt](files.txt) 列出本阶段相对进入阶段时的 **80 项文件操作**（迁移按删除和新增分别计数，不包含本报告目录）；[changes.json](changes.json) 保存前后 SHA-256；本目录报告、fixture和证据的完整文件摘要见 [artifacts.sha256.json](artifacts.sha256.json)。主要修改 app Gradle 的测试资产目录、CMake、Cargo workspace/lock、JNI、Android 回归、6 个验证脚本和 CI 的必要 Cargo/host 依赖。parser 与原 parser fixtures 移动保持内容；图像边界 API 改为检查过的 slice，原指针适配移入 JNI。
- 新增本目录的 Java 宿主客户端、固定 fixture/许可证/摘要、分阶段说明、运行证据和回滚补丁。fixture 只打入 androidTest APK。

KMP/CMP 仍只配置 Android。实际运行 JDK25.0.3 / Gradle9.7.1；Android Java/Kotlin 字节码仍是17（class major61），扫描3,945个 class。min26/compile37/target37、默认 `moe.tarsin.ehviewer`、Debug `.debug`、版本180064/1.15.0、数据库23/search2与 `archive_passwds` 偏好未改变。Release 四 APK 的证书均为 `5d91dff13b6489a9cfadbb197b3e795eefc8c39f849c4543f32c160720fb40a6`，与基线一致；未更换密钥。数据模型和迁移代码未调整；没有做真实用户数据的升级演练。

CMake、libarchive3.8.7、xz5.8.3、Nettle4.0、WebP1.6.0、Corrosion 及固定 commit/原补丁均保留。六个 Debug/Release × ABI 实际 CMake 数据库各375编译输入，应用自维护 C 输入为0；生成的空汇编文件仅作为最终共享库链接锚点。最终仍是 `libehviewer.so`，保留原14个 C JNI 方法及既有 parser/image JNI，无重复/额外导出，栈不可执行。

## 实际执行结果

| 检查 | 结果与证据 |
|---|---|
| 原 C 宿主基线 | 210 JNI 检查、484排序对通过；另确认旧7字节GIF ASan越界及Unicode密码异常（预期缺陷），见 [c-baseline.log](evidence/c-baseline.log) |
| 最终 Rust 原 JNI 客户端 | 同一210检查通过，见 [日志](evidence/rust-jni-baseline-final.log) |
| P5 差分 | signed/unsigned char 各26,896排序对、2,004 GIF输入全部与旧C一致，见 [日志](evidence/differential-final.log) |
| 归档差分 | 13包 C/Rust 条目顺序、扩展名、长度、SHA-1/SHA-256一致；ZIP/store/deflate、AES/ZipCrypto、TAR/gzip/xz、7z、RAR5 stored/compressed/solid/multiple、4MiB页，见 [日志](evidence/archive-differential-final.log)、[摘要](evidence/archive-differential.json) |
| Core 测试 | 15通过（7 unit +8 integration）；3网络 parser用例过滤；包括错误密码/Unicode/emoji、随机/反向跳页、并发、fd、生命周期、ZIP写入、限制及512个损坏样本，见 [日志](evidence/core-tests-final.log) |
| 宿主 ASan | 同15通过；Rust/std插桩，外部Homebrew C未插桩，detect_leaks=0，见 [日志](evidence/core-asan-final.log) |
| Release JNI guard | panic/error/success 1测试通过，见 [日志](evidence/jni-guard-tests.log) |
| Rust 静态检查 | fmt、三ABI workspace/all-features Clippy `-D warnings`通过，见 evidence 对应日志 |
| Gradle 最终验收 | Spotless、Debug、androidTest、Release、lintRelease均通过（Lint 0 errors、47 warnings，详见保留报告），43秒；见 [最终构建日志](evidence/build-acceptance.log) |
| APK与字节码 | Debug4 + Release4 APK校验；三ABI ELF、64位16KB/zipalign、applicationId/SDK/签名/版本、JNI符号通过；见 [Debug](evidence/debug-apks.json)、[Release](evidence/release-apks.json)、[symbols](evidence/native-symbols.json)、[bytecode](evidence/bytecode.json) |
| 工具/工作流 | 既有ELF校验器8测试、Python语法检查、actionlint、git diff --check通过；远程Actions未触发 |

最终 Debug universal SHA-256：`c5c1fa31323bc405adb9facfae96316d3980047dbf7078e60095ed5f58d5e97d`。测试 APK：`0c2d22946c5c21985f71a8ea8f46bd4647cf069f756deeec23b6a8cc2a09140e`。下面五台专用 arm64 模拟器都安装相同最终 APK，最后生产代码修正后重新运行，共 **80次用例执行**（每台13个Native +3个Platform）。没有将80描述为80个独立用例。

| Android API | 页尺寸 | 实际结果 |
|---|---|---|
| 26 | 4096 | 16/16通过，[证据](evidence/android-api26/result.json) |
| 29 | 4096 | 16/16通过，[证据](evidence/android-api29/result.json) |
| 33 | 4096 | 16/16通过，[证据](evidence/android-api33/result.json) |
| 35 | 4096 | 16/16通过，[证据](evidence/android-api35/result.json) |
| 37 Canary 20261007 | 16384 | 16/16通过，[证据](evidence/android-api37/result.json) |

设备测试实际调用 SHA/GIF、归档读写/密码/并发/坏包，以及 Bitmap、HardwareBuffer（API29+回拷验证像素）。API26页尺寸通过 `/proc/self/smaps` 回退取得。测试模拟器已关闭，不涉及用户已有设备的数据。机器可读汇总见 [execution-summary.json](evidence/execution-summary.json)。

## 性能基线与风险

macOS ARM64、JDK25，同一 JNI 客户端与 libarchive3.8.9；原 C 使用 `-O3`，Rust最终Release/LTO/unwind。每种实现各3次独立JVM试验，每次300次固定随机跳页、每4KiB触碰数据并释放；包内3页、每页4MiB。表格取3次结果的中位数（包括各次p95的中位数）。[原始12次结果](evidence/benchmark.json) 保留离群值，所有校验和一致。

| 包/实现 | 打开ms | 读页中位ms | 读页p95 ms | 整个JVM峰值RSS MiB |
|---|---:|---:|---:|---:|
| stored / C | 3.8252 | 0.0064 | 0.0334 | 54.5 |
| stored / Rust | 3.5621 | 0.1072 | 0.1415 | 47.1 |
| deflate / C | 3.5548 | 0.7708 | 0.8655 | 57.5 |
| deflate / Rust | 3.6129 | 0.7868 | 0.8790 | 58.6 |

stored 读页增加约0.10ms（约17倍相对开销），来自独立私有映射及注册所有权，换取关闭/重开后缓冲安全存活、可写页互不污染。deflate 中位增加约2%，本样本整进程RSS增加约1.1MiB。RSS包含JVM及动态库，不是Rust堆测量；此合成宿主样本不能证明Android真实大图集无性能回退。P6 的全量性能验收仍待实机/真实大包测量。

资源限制和行为变化详见 P6：8GiB归档/isize限制、256MiB页、64GiB声明展开、100k headers、4并行/20池上下文、128个/512MiB已发布ByteBuffer。预算不覆盖正在分配的页及codec窗口，不是硬RSS上限。源映射存活时文件不得被修改/截断。释放必须传原返回ByteBuffer对象；释放后不可访问其视图。JNI仍单活跃归档；Core支持独立会话。排序相等项改为稳定原归档顺序，ABI char有符号差异保留。保留ZIP ignorecrc32旧设置，不宣称拒绝全部CRC损坏。

未验证：ARM32/x86_64 Android运行时（已编译、Clippy、链接和APK检查）；真实数GiB图集、长时峰值RSS和全部codec bomb；分卷/加密RAR5、header-encrypted7z；Android全量native sanitizer；3个网络parser用例；实际账号/联网/UI业务及用户数据升级；远程GitHub Actions。某些压缩RAR5 fixture用本机7zz26.04提示Unsupported Method，libarchive C/Rust和Android解码实际通过，不计作7zz交叉验证通过。

## 失败、修复及复测

1. 旧 C 的短GIF越界、Unicode密码长度缺陷由基线主动复现；Rust边界/UTF-8转换修复，差分和Android回归通过。
2. Rust迁移初版GIF marker测试失败，校正为旧匹配字节后扩展差分全部通过。分层后Clippy报告不再需要的never_type feature，移除后3 ABI复测通过。
3. 初次Gradle/Cargo受缓存写权限和网络限制失败，获执行权限后重试。曾并发启动两次Gradle，出现Dex缓存缺失与Kotlin incremental/IR失败；改为串行、一次关闭增量/构建缓存恢复后，再用标准参数最终通过。保留 [失败日志](evidence/build-offline.log)。离线构建亦曾缺少reorderable依赖，联网补齐后最终离线验收通过。
4. Android资源打包将 `.tar.gz` 与基线 `.tar` 视作重复资产，见 [失败日志](evidence/build-verified.log)；改名 `.tgz/.txz`，[重试通过](evidence/build-verified-retry.log)，随后最终APK全矩阵重跑。
5. Unicode ZIP fixture用7zz生成失败，采用标准ZipCrypto合成并由Python zipfile/Core/Android实际验证；RAR重命名需同步header CRC，校正后解码摘要回归通过。
6. 最终复核把密码验证限定为首个完整加密页面及认证尾，避免输入密码时遍历整本图集；此修正后Core/ASan/三ABI Clippy、Host Release JNI/13包差分、Gradle最终构建和5个Android版本均重新通过。

部分早期失败日志被重试覆盖；以上明确区分可留存原始日志与执行过程记录。没有将失败尝试报告为通过。

## 重复执行

在仓库根目录运行。需已安装JDK25、Rust固定nightly及Android targets、SDK/NDK/CMake；宿主差分脚本当前面向macOS/Homebrew（host工具不代表新增产品平台）。CI Ubuntu安装libarchive-dev/libwebp-dev。首次依赖下载需网络；`--offline`仅在缓存齐全后使用。

```sh
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME='/Users/zhanziyuan/Library/Android/sdk'
export LIBRARY_PATH='/opt/homebrew/opt/libarchive/lib:/opt/homebrew/opt/webp/lib'
./gradlew --version
./gradlew spotlessCheck :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintRelease -Prelease --offline --console=plain --no-daemon
cargo fmt --manifest-path app/src/main/rust/Cargo.toml --all -- --check
cargo test --manifest-path app/src/main/rust/Cargo.toml --locked -p ehviewer_core -- --skip test_parse_info --skip test_parse_profile --skip test_parse_gallery_detail
cargo test --manifest-path app/src/main/rust/Cargo.toml --locked --release --features jvm --lib
for target in aarch64-linux-android thumbv7neon-linux-androideabi x86_64-linux-android; do
  cargo clippy --manifest-path app/src/main/rust/Cargo.toml --locked --workspace --all-features --target "$target" -- -D warnings
done
python3 scripts/verify_native_sources.py
python3 scripts/native_differential.py --output /private/tmp/ehviewer-repeat/differential
# 先自行启动专用API26/29/33/35/37 arm64 AVD；API37选16KB镜像，然后对各serial重复：
python3 scripts/run_native_device_tests.py --serial emulator-5560 --sdk "$ANDROID_HOME" --output /private/tmp/ehviewer-repeat/android26
```

重新物化固定旧C、生成宿主JNI客户端和原始基线，再构建最终Rust动态库，可独立复跑13包差分：

```sh
python3 scripts/materialize_native_reference.py --output /private/tmp/ehviewer-repeat/reference
python3 docs/modernization/baseline/native_baseline.py --repo /private/tmp/ehviewer-repeat/reference --output /private/tmp/ehviewer-repeat/c-baseline --jni-header "$ANDROID_HOME/ndk/29.0.14206865/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/include/jni.h" --fixture-seed docs/modernization/baseline/fixtures
cargo rustc --manifest-path app/src/main/rust/Cargo.toml --locked --release --features jvm --lib --crate-type cdylib
python3 scripts/native_archive_differential.py --c-library /private/tmp/ehviewer-repeat/c-baseline/libp0.dylib --rust-library app/src/main/rust/target/release/libehviewer_rust.dylib --output /private/tmp/ehviewer-repeat/archive-differential
```

上面的原始基线库是O0，只用于正确性；重复性能测量必须先按 baseline脚本打印的clang命令把 `-O0` 换为 `-O3`、输出 `libp0-optimized.dylib`，不要拿O0与RustRelease比较。Java类由归档差分脚本编译。

```sh
python3 scripts/native_benchmark.py --c-library /private/tmp/ehviewer-repeat/c-baseline/libp0-optimized.dylib --rust-library app/src/main/rust/target/release/libehviewer_rust.dylib --classes /private/tmp/ehviewer-repeat/archive-differential/classes --output /private/tmp/ehviewer-repeat/benchmark
# 单独target目录，Rust/std ASan；本机不启用LeakSanitizer：
ASAN_OPTIONS=detect_leaks=0 RUSTFLAGS=-Zsanitizer=address CARGO_TARGET_DIR=/private/tmp/ehviewer-repeat/asan cargo test --manifest-path app/src/main/rust/Cargo.toml --locked -Zbuild-std --target aarch64-apple-darwin -p ehviewer_core --lib --tests -- --skip test_parse_info --skip test_parse_profile --skip test_parse_gallery_detail
```

## 回滚

[补丁](p5-p7-forward.patch) 是**进入P5前P4工作树 → 最终P5–P7生产/测试/脚本文件**的二进制可逆diff，包含新增/删除/移动，保留此前P0–P4的全部未提交改动。没有使用 `git reset`/clean，也不依赖后来可能消失的临时目录。此报告和fixture目录不在补丁内，回滚后作为审计资料保留，app资产配置会恢复为P4。

已在当前工作树执行 `git apply --reverse --check` 成功，并在独立临时树实际反向应用、逐文件比较进入阶段的SHA-256（见 evidence/rollback-check.log）；**未在用户工作树实际回滚**。先另行备份后来新增的本地改动，再按需运行：

```sh
git apply --reverse --check docs/modernization/p5-p7/p5-p7-forward.patch
git apply --reverse docs/modernization/p5-p7/p5-p7-forward.patch
```

不要仅还原archive.c或单个Cargo文件，以免重复JNI符号、构建布局不匹配。整体回滚会恢复旧C的已知短GIF和Unicode密码缺陷。恢复后需要重新构建验证；本次未对回滚后的树再次执行Android构建。
