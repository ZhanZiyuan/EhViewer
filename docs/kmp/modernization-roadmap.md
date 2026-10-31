维护者加速指令已覆盖此前逐提交双事件全量流程：feature/kmp仅PR CI；Fast/Platform/Full按声明式计划执行，Android关键回归始终必需，阶段完成/发布/冻结/合并前全量。CI期间可准备下一本地功能，上一个必需检查成功前不推送下一提交；Actions Summary自动记账，不再为补历史链接单独提交。实现与验收见[ci-plan](ci-plan.md)。原45提交及已通过结果保持不变；Ktor会话→图库搜索→详情→图片→阅读器闭环已经交付，M3阶段门禁已通过，当前推进M4下载/离线与Rust归档。

# 现代化路线与阶段门禁

M3 已在 `74a8e933d51de52309c6e38390e89b90abf6321b` 完成阶段门禁：[Full CI41212001274](https://github.com/ZhanZiyuan/EhViewer/actions/runs/41212001274) 全部12项成功，包括原 Android 回归、KMP 编译/契约、实际 iOS 启动和存储迁移、Windows ARM64 SQLite/JNI、桌面包原生消费及无密钥 dry-run。此前源码声明、SQLite 架构/连接所有权、独立构建类路径和浏览器关闭竞态的失败均保留，未跳检查。共享 Ktor/会话/Repository、历史/原迁移/便携配置及参考决策已验收，见 [shared-history-and-data.md](shared-history-and-data.md)。

当前进入 M4 / IN_PROGRESS：交付同一 CMP 下载列表、真实文件/Room、暂停/恢复/删除/重启和离线阅读，随后推进 Rust 归档、部分图片恢复与高级媒体；不把本切片等同完整 M4 或五平台 P0。搜索、详情、阅读器、账户、筛选、外观与预览已逐提交通过 CI。PR #15 始终 Draft，版本到 M8 之前保持 `1.15.10 / 180074`。后续每次改动遵守加速计划，不恢复历史双事件矩阵或额外记账提交。

以下保留各历史时点的范围、结果和未通过记录；其“当前/待验”描述不覆盖以上进度，也不改变维护者的加速计划。

本轮先交付 iOS 原生浏览器登录入口：共享 CMP 导航和确认，Kotlin/Native 嵌入独立 WKWebView，无 Swift/自维护 JavaScript。真实 HttpOnly Cookie 交接在公共 KMP 检查身份、范围、期限及会话代次后才原子写入 Keychain；取消不写入。网络 Android/JVM/Native 各 46、阅读器各 20、真实 WebKit/Keychain 双冷启动及原 Android 验收任务通过，iPhone/iPad 官方验证页、窄屏/横屏和取消已测。托管 CI 仍需独立验证；网站验证码/真实账号、桌面与 Android 浏览器接入、配置与数据迁移仍待完成，M3 不标记完成。参见[浏览器切片的参考、验证与限制](browser-sign-in.md)。

当前账户切片已接入同一 CMP 登录/退出表单和真实 Ktor 事务：成功后单次写入 OS vault，失败保留旧会话，退出使在途登录失效，取消后重读真实存储；Android/JVM/Native 各 39 项网络测试、原 20 项阅读器测试和 Android 完整验收任务通过，iPad 输入/遮罩/返回清空及用户名 IME 修复已实测。上一阅读器 SHA `4e791db93` 已通过全部 12 个必需 Job、gate 与 dry-run，本切片仍须自己的独立 CI；认证浏览器跳转、Android 共享接入与数据库/配置迁移仍待完成，M3 不标记完成。范围与许可见[共享账户与认证](gallery-authentication.md)。

阅读进度恢复、RTL 与连续阅读已完成本地产品验收：20 项阅读器测试在 Android/JVM/Native 均通过，实际 macOS 重启恢复第 5 页，iPad 容器迁移后恢复第 102 页/连续模式/RTL；快速拖动限速和横屏保留第 3 页也已运行。修复了真实重装发现的 OS vault 绝对目录标识问题，保留密钥和密文。前一修复 `12820976e` 已通过全部 12 个适用 Job、gate 与 dry-run，本功能仍需独立 SHA 的必需 CI。设计、参考差异与额外库 lint 的未通过记录见[阅读进度与连续阅读](reader-progress-and-continuous.md)。完整 M3 的认证、Android 接入、数据库/共享配置等仍未完成。

当前推进共享详情与在线分页阅读：实际 macOS 与 iPad Simulator 已完成真实列表→详情→图片 1/2→返回流程，平板横屏与旋转后保留详情也已运行；Android 原有完整回归、四 ABI 构建和共享代码检查通过。继续在同一产品切片完成重试/平台检查与精确 SHA 的 PR CI，随后推进阅读进度、连续/RTL 阅读及 M3 存储/认证和 Android 接入；不把本切片标为 M3 完成。设计与真实范围见[详情与阅读](gallery-details-and-reader.md)，此前时点记录保留如下。

当前 M3 产品切片：共享 Ktor 网络、加密会话与同一 CMP 搜索界面已接入 Desktop/iOS；真实搜索、翻页和 iOS 空结果已运行。Android/JVM/Native 各 19 项共享测试、真实 macOS/iOS Keychain 测试及 Android 原有构建回归已本地通过，当前提交仍需完整 PR CI。详情、图片和阅读器为紧接的功能闭环；认证 UI、Android 导航接入、持久化等 M3 未完成项继续推进，不将本切片视为阶段完成。设计差异、许可与验收范围见 [搜索与会话](gallery-search-and-session.md)。

M3-004当前迁移common的JVM真实文件流，为后续持久化提供平台基础；Android文档URI实现字节不变，暂不启用common Native目标。原三标题+五真实文件/失败/协程取消测试及打包JAR独立八场景本机通过，CI在五个已接入桌面JVM环境消费同一实际JAR、聚合逐源/字节/真实回执验证，仍待完整新SHA门禁。见[ADR0027](adr/0027-common-jvm-filesystem.md)；M4下载/归档没有提前修改。

M3-003已于d3d2e47d1 GATE_PASSED：双完整12job/gate、各23实际工件验收完成，46官方归档/846911161字节及1844原payload/1327691738字节全量重哈希。九自有模型字节不变、原CBOR金值、Android/JVM/Native各八领域测试、两29文件Native KLIB和schema4实际聚合均通过；同一英文Draft PR第42行已独立回读，之后才开始common。见[ADR0026](adr/0026-portable-gallery-models.md)。完整M3和P0仍未完成。

M3-002已于4cdb47f76 GATE_PASSED：Push40862905445及PR40862912967全部12job/gate实际SUCCESS，各23实物独立验收；46官方ZIP/845637022字节、1836原始payload/1323643940字节全部复核。两真实Native流程强制16任务/93秒与186秒，两个20文件KLIB/API及三Native测试、编译执行二进制一致、新鲜度及自有清理通过。双schema3聚合22原输入、独立Native消费者、原JVM探针及六真实候选一致；同一英文Draft PR第41行已验收并回读，之后才进入本轮模型。完整M3/产品集成/P0/最终11包仍未完成。以下保留已验收M3-001与M2证据。

M3-001已GATE_PASSED：e5ed5350b47ad753dffd95ba72d46059a8d73751的Push40851262709/PR40851270786双完整十二job/gate及各22真实工件全部独立验收。原已用GalleryTitles/ArchiveCatalog原样迁入domain、common重导出，其他旧模块保持Android-only；各五AAR/401Java17 class、真实两class JVM JAR、domain Android/JVM各3及原common3测试、聚合再次七标题/四接口探针全部通过。共享123任务全执行、原Android353任务/347执行6缓存、双producer196测试、同包Intel及原平台证明保持；44官方ZIP/844214195字节、1810原payload/1318582654字节全量重新核验。M3仍IN_PROGRESS，下一步独立接入/测试iOS领域目标，再迁移实际模型/会话/持久化；不以本步代替完整数据/共享UI/最终11包P0验收。参考路径/差异/AGPL隔离见ADR0024，本地Animeko仍只读无复制构建；稳定1.15.10/180074、生产Release和同一英文Draft PR保持，原39行保留及第40行GATE_PASSED已独立GET读回。

M2已完成当前阶段门禁：17a17e282beaadeb21687a12aaeeb4314c25e255的Push40840065517/PR40840070582双完整十二job/gate及各22真实工件全部验收。SDK显式路径在真实Ubuntu成功，21原输入与六包聚合均真实执行；独立44归档/844178444字节、1788原payload/1318424670字节、每组六实际候选与原APK/DMG/ZIP逐SHA匹配，五最终目标及P0/11包/publish=false保持。所有原平台证明、逐PID iOS、同DMG原生Intel消费及双DMG只读挂载核验/卸载齐备，189真实测试与原期限保持。640ffe双失败及本地截断下载/过早审计失败保留，重取成功后才接纳。main仍423b8af、稳定1.15.10/180074、单英文Draft PR不变；先关闭同PR与文档并读回，再进入M3首个小步领域核心迁移，M3–M8和最终11成品未完成。以下是历史时点记录。

M2-013修复准备：640ffe455的双事件原十个上游job全部SUCCESS，但新dry-run裸sdkmanager命令不在PATH，exit127，聚合未执行/无第22份工件，双gate真实FAILURE，M2-012明确NOT_PASSED。当前只独立修复为SDK根目录下的显式工具路径及--sdk_root；本地真实SDK listing/189工具测试40秒/原Android319任务1m42秒均通过。runner清单ref404不声称已阅，实际托管路径与完整聚合必须由修复SHA新CI证明。单英文Draft PR第38行已如实记录失败并GET读回，原37行保留；不重跑/取消CI或进入M3，原发布/版本/期限/验收不变。以下保留各时点证据。

M2-012：已实现必选ci/release-dry-run及ci/gate接入，聚合四实际APK、同包原生Intel消费的DMG和Windows ARM64 ZIP/解压启动证明；最终五目标、全部P0仍未验。本地最终189工具测试/46秒、原Android319任务与全部适用预检通过。完整认证网络获取→聚合已实际exit0，旧已验收145eb的21官方ZIP/281809427字节、839实际payload/500063010字节与六候选158744617字节均独立逐SHA核验；仍是本地dirty旧输入证据，不能替代新SHA验收。早期JAR替换失败、五分钟下载超时及旧本地副本exit143保留，新下载器十分钟/三次/四线程及新job45分钟有界，原平台期限/检查不变。详见ADR0023/staged-package-dry-run及ledger；完整新SHA双事件各十二job/gate/各22真实工件结束前不进入M3。

M2-011：`145eb1f49087175d3e9690d6854501b35eb62ae4` 的官方工件ZIP传输核验组件已双事件各十一job/gate及各21实物 GATE_PASSED（Push40820871137 / PR40820876949）。本提交同一CLI对42新实际归档的官方身份/大小SHA/CRC/路径/全部1678文件1000160088字节解压读回通过，独立字节与全部原生平台证明亦齐备；两个实际DMG只读挂载核验并卸载、同包原生Intel消费及180测试保持。只承诺传输scope，执行权限不保留、产品包/原生执行NOT_EXECUTED，原CI图不改；M2真实无密钥产品包聚合仍待下一独立改动，不能用本组件冒充最终11包/P0。稳定1.15.10/180074保持；格式失败和额外稳定生产命令在SNAPSHOT上的错误调用如实保留，原发布校验不变。详见ADR0022/artifact-archive-intake及ledger；以下历史条目保留各时点边界。

M2-010：`50d1e12690aaeccdad16cedf072fa80b78347d0b` 的Windows ARM64实际ZIP解压后原生双启动已双事件各十一job/gate及各21实际工件 GATE_PASSED（Push40816308738 / PR40816312011）。每次原镜像2次+新ZIP2次共四次真实启动，177完整文件/35 ARM64 PE/内嵌source及ZIP/package/runtime哈希链、解压Unicode目录内JVM/Rust真实路径、UTF8/GetACP65001/2000/live0/所有权/绘制均通过。完整171工具测试、原Android及全部Native/共享/模拟器/同包Intel消费保障保持；M2只剩已接入目标实际无密钥打包聚合待完成，最终11包/P0/签名真机/最低系统/硬件加速/完整SBOM未验。详见ADR0021/windows-zip-launch及ledger；以下历史条目保留各时点边界。

M2-009：`3e75b63a89680e134197fb22cedc3d75c3827d33` 的Windows ARM64真实staging ZIP已双事件各十一job/gate及各20实际工件 GATE_PASSED（Push40811237292 / PR40811242170）。各新ZIP177完整payload文件/35 ARM64 PE/内嵌source及runtime-proof/全部大小SHA与CRC独立核验，实际保留原目录工件遗漏的隐藏app/.jpackage.xml；原Core39/独立JVM/两次窗口及全部原生验收保持。完整168工具测试和原Android回归通过，两次本地test-first大小写/真实路径别名失败及修复保留。Windows ZIP解压后真实启动和跨平台已接入目标无生产密钥聚合仍待独立完成，不能将本字节组件或旧目录镜像当作M2/最终11包/P0验收；稳定1.15.10/180074保持。详见ADR0020/windows-stage-zip及ledger；以下条目保留各时点边界。

M2-008：`b6e4d3ec687d91531c866b6e67abe4bb4cc5094e` 的独立 `ci/shared-kmp` 已双事件各十一job/gate及各19份实际工件 GATE_PASSED（Push40805794104 / PR40805800099）。各97任务全部执行，四AAR/401个Java17 class/12资源XML逐字节绑定真实编译输出，common真实三个测试，其余模块明确NO_TEST_SOURCES；完整161工具测试与原Android回归通过，原架构/Native/生产Release/版本冻结不变。生成MR contentHash跨运行存在差异，实物按各自报告验哈希，不承诺bit-reproducible。Desktop/iOS共享目标仍由M3独立迁移；M2只剩已接入目标实际无生产密钥打包/聚合门禁待接入，不能将当前合同或目录镜像作为最终11包/P0验收。完整证据见ADR0019/shared-core-check及ledger；以下早期进展条目保留各时点真实范围。

状态：M0 / GATE_PASSED，M1 / GATE_PASSED，M2 / IN_PROGRESS，2026-10-29。2f00e595c双事件各九job/gate与全部实际工件核验齐备；M2首项为独立Kotlin 11目标交付合同与无密钥CI检查，尚未实现M3–M8。版本到 M8 冻结提交之前维持 `1.15.10 / 180074`。

M2进展：3d204d349容量调度修复已双事件九job及全部实物通过；28ab24263独立累计source-policy已双事件十job/gate及全部实物通过，实际清单/索引blob/未跟踪与三直接许可origin摘要已核验。23df原PR iOS日志超时失败保留；后继独立修复eab4c8b53已双事件各十job/gate及全部实物通过，真实逐PID完整日志与原30秒上限验证。随后main Ruleset24795497实际追加可信ci/gate/app15368/严格基线规则，保留原禁止删除/强推/范围/空bypass，Ruleset及effective-main双读回确认REMOTE_ENFORCED。此成功HEAD允许继续下一独立M2改动；阶段check/shared-kmp/已接入无密钥dry-run聚合仍未完成。完整复制/许可/资源/SBOM与后续产品能力仍不能由本轮源码边界检查代替。 76a8ac951显式M2阶段契约亦已双事件各十job及所有实际工件GATE_PASSED，source/phase实际JSON与独立657文件/52源码变更本地报告匹配；两个待接入类别继续明确，未把声明校验当作构建/P0/最终11包验收。 后继2e3c533a1独立枚举器边界修复已双事件各十job及全部实物GATE_PASSED；五份真实合法YAML反例/修复前失败保留，完整155测试与原Android回归通过。阶段依赖类别仍待实际接入，不将本修复等同M2完成。

当前技术证明：Kotlin-only iOS/实际C ABI、Linux x64/macOS ARM64 host JNI、ARM→Intel DMG/Rosetta及原生Intel同包消费、Linux aarch64包内JVM/Skiko/Rust实际软件绘制均通过2f00e595c双事件门禁。Windows原Core39/独立MicrosoftARM JVM/Unicode路径/C ABI 2000/live0与真实包内JVM/Skiko/C ABI双窗口也均通过：自有启动器副本只读属性真实修复、SDK语义读回、GetACP65001/UTF-8、SOFTWARE_COMPAT/绘制帧与全部所有权错误释放关闭证据齐备。两事件全部实际Native/许可/八APK/模拟器与同一Intel DMG生产消费证据均已核验，历史失败保留。此为M1技术spike，不等于五平台P0、最终11包、iPad、签名真机、完整SBOM或最低系统已验收。Linux/Windows硬件加速另列M5风险，不以CPU软件绘制替代该结论。

| 顺序 | 现有问题与独立工作包 | 参考 / EhViewer 落点 | 收益与退出准则 | 风险、回滚 |
|---|---|---|---|---|
| M0 / P0 | 本地参考缺 Git；原 feature push 触及生产签名 | reference audit；`ci.yml`、`releases.yml`、verification trust tests | 来源/许可映射可审阅；生产材料 main-only；保留 Android tests/build；单 Draft PR 与逐 SHA ledger | 初始 SHA CI 未成功即阻塞；修复正常新提交，不改历史 |
| M1 / P0 | Kotlin-only iOS 可启动性未证明 | Animeko AniIos/ContentView 仅识别差异；新 Kotlin UIKit entry + plist | Simulator `.app` 真实启动、生命周期；不是 framework-only；无 Swift/ObjC 实现 | 工具链/UIApplicationMain/资源失败记录完整日志；不放宽语言限制 |
| M1 / P0 | Desktop/iOS Rust 未形成产品 FFI | 复用目标 `native/rust/core`、JNI；独立 C ABI ADR | host JNI 与 simulator C ABI 调用/释放/错误用例通过 | 第三方链接、panic、所有权；保留现有 JNI ABI，禁止空桥接 |
| M1 / P0 | macOS Intel 在 ARM runner 打 DMG；ARM64 桌面运行时未知 | 参考 desktop build 组织；独立打包 spike | Rosetta + x86_64 JDK/Skiko/Rust/launcher；真实架构与启动证据；Linux/Windows ARM64 native 检查 | 不可行时 BLOCKED，先请求允许 Intel runner；不擅换 runner |
| M2 / P0 | 单 job 状态不足以证明分平台门禁；生产 dry-run 不可用于 feature | 原 workflow/verification，参考测试分层 | `ci/android-regression/shared-kmp/rust-native/desktop/ios/release-dry-run/source-policy/gate` 按已接入阶段真实 success；显式尚未接入清单 | 保留每个 Android 检查；Ruleset 变更单独核验现有规则；不能以 neutral/skipped 通过 |
| M3 / P0 | common 声明与 Android actual 混合；数据库/会话单例 | Animeko builder/平台层；现有 `core/common`、`core/data` | 逐个接口/源集，Cookie/网络错误/存储迁移成功/失败/取消自动测试 | 先契约再独立实现；不得清库或迁入 OAuth 业务 |
| M3 / P1 | 依赖兼容与资源不明 | 两项目版本对照、现有 convention | 仅必要升级独立提交，已有 target 全编译；依赖来源/许可清单 | 不捆绑 CMP/AGP/Ktor 大升级；回滚单个变更 |
| M4 / P0 | SpiderQueen/下载状态与 Android 后台/文件耦合；Native 目标不足 | EhViewer 原调度与归档核心；Animeko 只参考状态分层 | 下载暂停/恢复/取消/重试/重启；恶意归档/lease/多线程/预算测试 | RSS 与缓存预算分别测量；保留 16 KiB、JNI 签名与超时限制 |
| M5 / P0 | 阅读器仍在 Android app；桌面输入/窗口缺失 | 参考 ui-foundation/PlatformWindow；原 ReaderScreen/PageLoader | 共享画面与阅读状态；Android 体验回归；三桌面真实窗口/键鼠/离线导入 | 分页/RTL/长图/缩放和 Coil 解码性能有基线再迁移 |
| M6 / P0 | 原 Release 仅四 APK | 在原 releases 四段结构扩展；Kotlin/Rust verifier/packaging | 精确 11 包、同版本/SHA、CPU/格式/入口/runtime/哈希；失败全或无 | AppImage 与 ZIP 独立构建，不改扩展名；不泄漏诊断/签名材料 |
| M7 / P0 | iOS P0/Keychain/沙盒/后台恢复未实现 | 参考平台边界，独立 UIKit/Native 适配 | F01–F10 成功/失败/取消；Simulator + 开发签名真机 | unsigned IPA 不声称可直接安装；后台受限应可恢复 |
| M8 / P0 | 全平台验收、版本/来源/发布合同最终冻结 | ledger、功能/性能/许可、单一版本权威 | 前一 HEAD 全绿且 11 目标已验；独立 `chore(release): prepare v1.16.0 across five platforms`；再全量 dry-run | 检查 tag/Release/versionCode 冲突；仍 Draft，不创建正式 tag/Release，不自行合并 |

每个工作包：一个可验证改动 → 本地 tests/lint/build → 一次 commit → 单独 push → 等待精确 SHA 的适用 push/PR/dry-run 全部 success → 写 ledger 与同 PR → 下一项。失败、取消、缺失、未知和未完成均不允许继续功能开发；只能修复门禁并保留失败历史。

每次采用参考设计在 reference audit/ADR 记录实际参考路径、目标路径、差异、许可证处理与验证结果。所有新/重写自主实现只使用 Kotlin/Rust；声明式配置和第三方/生成产物单独登记。CI 不依赖本地 Animeko。

## 官方资料核对与当前限制

- [GitHub runner labels](https://docs.github.com/en/actions/how-tos/write-workflows/choose-where-workflows-run/choose-the-runner-for-a-job)：实际 runner 的 OS/CPU 必须在 job 开始核验；按 PRD 固定 `ubuntu-latest`、`ubuntu-24.04-arm`、`macos-latest`、`windows-latest`、`windows-11-arm`，不以名字替代运行证据。
- [Compose native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)：官方包型没有 AppImage/portable ZIP；需 Kotlin/Rust 独立封装与 smoke，jlink runtime 不能遗漏模块。
- [Kotlin C interop](https://kotlinlang.org/docs/native-c-interop.html)：可生成 C 绑定；这不证明 Rust ABI/所有权已正确。
- [CMP entry points](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-entry-points.html)：提供 UIViewController 集成示例；不证明 Kotlin-only iOS executable 已构建、签名和启动。
- [GitHub required checks](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks)：平台保护本身可能接受 skipped/neutral；本项目 gate 只接受真实 success，并分别核对 PR head 与 test-merge SHA。新 gate 的本地用例已经验证 failure/cancelled/skipped/neutral/pending/空状态均失败。
- Apple code-signature 文档在线入口可访问，但本次文本读取只返回 JavaScript 提示，Markdown 链接读取失败；尚未据此声称完成 Apple 签名规范核验，M1/M7 需补充可读官方证据和实测。

M0 未建立 Release 性能对照；任何“性能提升”或“无回退”均不得由代码审阅代替。设备、签名和非宿主平台测试条件逐项列为阻塞，不能用 Android 编译通过代替五平台验收。

2026-10-28 本机只读条件盘点：沙箱内 `xcrun devicectl list devices --timeout 10` 超时，不能据此认定无设备；在可访问 CoreDevice 服务的权限环境以同一命令成功列出设备，所有记录的 Reality 均为 simulated，没有真实设备。`security find-identity -v -p codesigning` 在两种环境均返回 0 valid identities。未导出密钥、签名或安装应用，未把设备标识写入文档。维护者随后明确没有 Apple 开发者账号或签名，要求继续构建原合同中的 unsigned IPA。缺签名不阻止 unsigned 构建与交付；开发签名真机验收保留 NOT_VERIFIED，不能用模拟器结果冒充。iPhone 与 iPad 必须分别记录模拟器验收。

## 2026-10-28 维护者补充要求

- 四份语言 README（根英文、简体中文、繁体中文、日文）的 Thanks/感谢/感謝列表末尾添加 `[Animeko](https://github.com/open-ani/animeko/)`。独立提交47ebcc959已通过双事件全部九job/gate与全部实物核验，GATE_PASSED。
- 当前及后续 commit summary/body、PR title/description 全部使用英文。最新复核 origin/main..75b36b2ef 的22个摘要/正文均为英文；早期正文为空，无需改写已推送历史。同一 Draft PR 标题/正文已改为英文并读回核对，历史失败及 11 包合同保留。
- 四个 Android ABI 安装包均须覆盖手机与 Android 平板，iOS unsigned IPA 须覆盖 iPhone/iPad。M5/M7 的自适应布局、窗口/方向、资源和交互矩阵分别验收，暂不增加折叠屏专项适配。
- 五平台保持同一 EhViewer 图标视觉身份，在 Android 自适应/密度资源、Apple 平台图标资源、macOS ICNS、Windows 多尺寸 ICO、Linux 桌面图标集成中遵循各平台规范；以项目自身资源为来源，不复制 Animeko 图标。M5/M6 分别验证显示与安装包资源。

M3-005优先完成common真实Native文件系统基础，再进入会话/网络/持久化；不扩大到UI/发布或升级版本。98c1f12双完整门禁/实物/英文Draft PR回读已验收。新层次隔离Android文档URI与JVM日志，仅复用自己的真实磁盘actual/测试，原领域Native八项与原应用启动均保留，增加独立common Native八项和严格字节证据。首次缺IO扩展导入编译失败已保留并修复，不切换调度器规避问题。精确参考路径/落点/许可证及待验风险见ADR0028。数据库风险仍是EhDB v23两个打开路径的同17→18迁移及独立SearchDB v2，禁止破坏式fallback；全M3/后续M4–M8和11成品尚未完成。

M3-005实际生产common Native在PR b7ad24/iOS26.5通过八项/23强制任务及完整KLIB/执行binary/API/清理，Push becdcd4因ARM托管容量未分配runner而取消，严格gate失败，故整体NOT_PASSED。M3-006仅独立修复ios对rust_host与desktop_intel的调度屏障及守卫，全部原测试/runner/期限保留，211工具及371 Android任务本机通过，等待独立提交及双完整门禁。持久化/会话等新增功能暂停到修复验收后。数据后续真实官方sqlite-bundled-jvm2.6.2审计确认仅Linux/Mac双架构和Windows x64，WARM原生资源缺失；不能以声明代验。固定Intel JDK libjvm LC_BUILD_VERSION声明minos11.0，只作结构下限，不是最低系统实测。未改依赖/数据库/runner或版本。

- 维护者补充进度要求：M3-006只是M3第六个独立片段，不代表阶段完成。每个M0–M8阶段真实完成且所需双CI/实际工件/同一Draft PR证据闭合时，明确打印【阶段完成】Mx并附SHA/Actions证据，再提示进入下一阶段；未完成不得以小提交号冒充阶段完成。当前M3仍IN_PROGRESS，网络/会话/Repository/持久化/共享配置及M4–M8待完成。整体剩余70–85%仅按功能与验收范围粗估，150–300有效小时为当前不确定估计，M3完成后重估；不可据此承诺缺条件的真机验收或提前改1.16.0。

M3-006最终为NOT_PASSED：Push40903137090成功，PR40903149475因common Native链接编译PT10M超时失败，严格gate失败且dry-run跳过。依维护者加速指令，将有针对性的Native --rerun并入尚未首次推送的CI加速提交，不改原45个远程提交、不强推。仅测试模块的两次编译/链接/真实测试强制重新执行，保留所有新鲜度、真实二进制/XML、原8项domain+8项common、300秒/600秒期限和自有清理；本机两组实际通过84秒/72秒，222工具回归再次成功。原M3仍未完成，完成加速门禁后继续已经保留并初测的网络/会话/图库产品工作。
