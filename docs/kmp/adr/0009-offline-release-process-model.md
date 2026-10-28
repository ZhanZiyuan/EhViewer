# ADR 0009：保持原限时的离线发布流程进程模型

状态：仅修复真实CI失败，最终ARM/Intel本地完整测试通过、待独立提交及CI；M1整体IN_PROGRESS，版本仍1.15.10 / Android180074。

## 真实失败与边界

7f1afefe7的PR Intel/Rosetta job122902183904在既有`ReleaseWorkflowTest[cyclic-tag]`原60秒上限失败，121项中1项失败。生产DMG已构建，但验证步骤未执行、原生Intel消费被跳过，总gate失败。日志与实际DMG保留；不重跑旧CI，不加大超时、改断言或用包构建成功替代验收。

原夹具每次模拟`gh`和校验和调用都启动一个JVM，循环标签场景实际有19次API调用。本机相同Intel JDK25.0.5/Rosetta的定向基线通过，单用例18.62s；不能据本机成功改写CI超时，也没有证据断言更深层调度原因。选择消除重复JVM启动负担的独立测试修复，生产发布脚本保持原样。

## 独立实现

| 落点 | 行为 | 验证与限制 |
|---|---|---|
| `tools/verification/src/test/rust/offline_process_client.rs` | 仅Rust标准库的测试客户端，每个CLI调用仍是真实子进程，传递参数/输入并返回实际stdout、stderr和退出码 | 只允许loopback地址，按长度分帧且限制大小；5秒IPC失败界限不放宽外层60秒；错误非零。无新增crate或锁文件 |
| `OfflineProcessServer.kt` | 每个测试在同一测试JVM中启动临时loopback服务、随机token、顺序处理请求；结束关闭socket/线程 | 严格UTF-8/长度/参数数量验证，异常作为非零失败；不连接真实GitHub、不持有生产token |
| `ReleaseWorkflowTest.kt` | 原Kotlin状态模型、全部29种场景/断言与真实workflow Bash脚本保持；仅把模型IO改为显式参数/结果 | 校验和相对路径显式基于原Bash临时目录。生成的两行薄启动器只exec Rust客户端，业务逻辑仍全在Kotlin/Rust；没有维护新Shell业务代码 |
| `tools/verification/build.gradle.kts` | 原固定Rust工具链执行fmt和严格Clippy并编译测试客户端；Test显式跟踪该二进制及JVM架构 | test-only工具，不编译产品Native库、不加入产品包。Windows现有installDist任务不因此执行Unix测试 |
| `ci.yml` Intel producer artifact | 保留原DMG/报告路径，增加JUnit XML/HTML诊断 | 原job/依赖/gate/超时不变，原消费工件根目录不变；失败不能伪装SUCCESS |

原生Intel消费runner仅可编译这个无产品依赖的测试客户端；App/JVM/Skiko/Rust产品库和DMG仍只由原ARM producer构建。同一DMG及生产报告SHA的消费验证保持。

所有API状态分支、幂等/冲突/权限/资产篡改/循环标签拒绝断言、16层标签解析上限及原60秒用例限制均不变。`.github/workflows/releases.yml`无修改；prepare→build(matrix)→package→publish、生产签名、四APK与发布保护不在本修复中改变。

## 本地证据与许可

首次新模型真实失败25项：相对文件名错误地由测试JVM工作目录解析。失败XML已保留；改为显式原临时目录后完整121项通过，没有删改断言。ARM/JBR25.0.3和强制重新执行的Intel25.0.5均121项、0失败/错误/跳过。Intel的cyclic-tag实际0.958s、19次API调用、原60秒上限；这只是本机测量，不替代新SHA CI。最初一次架构切换复用了缓存，未当作Intel实测；之后cleanTest强制运行，并加入架构/二进制输入防止误用缓存。

最终接入严格Clippy和输入跟踪后，ARM全套再次通过（1m23s）；再切回Intel无需clean，`:test`按新架构输入真实重跑并通过（1m32s），121项、0失败/错误/跳过，cyclic-tag1.003s/19次API调用。原Android完整319任务、原四包/Native预检通过。新SHA全部适用CI仍须独立成功。

自主源码只有Kotlin/Rust，继承项目许可；标准库测试二进制不进入交付资产。复用的是EhViewer自己既有的Kotlin状态模型，无新Animeko代码/资源/脚本或依赖树采用；参考审计的AGPL边界不变。完整逐SHA结果、原失败及后续修复门禁见ci-ledger。
