# Android WP0 质量基线

采集时间：2026-08-27。计数仅用于定位候选弱点，不计分、不直接判失败。

## 新鲜执行证据

| 检查 | 结果 | 证据边界 |
| --- | --- | --- |
| `./gradlew testDebugUnitTest` | PASSED：275 tests，0 failure/error/skipped | JVM/unit；不证明 Room device、真实 App UI 或系统能力 |
| API 37 emulator 核心质量旅程 | PASSED：既有核心集；Event close 与 Thing relation focused 各 1/1 | 证明 App-owned Room→Paging/Compose→消息空态/详情/重启/分页/已读/搜索/删除撤销/首次 slow/error/retry/慢刷新旧快照保留/真实导航，以及 Event 准确详情→确认关闭→canonical projection→筛选排除→activity relaunch 持久化与 Thing 三关系页签→准确关联详情→返回/重启；不证明 Event slow/error/duplicate close、Channel/Settings 完整旅程或真实 FCM/权限/物理性能 |
| API 37 emulator 核心数据边界 | PASSED：18/18 | Production profile 下证明迁移、删除恢复与 ACK；与 Quality UI 进程隔离，防止 DB 会话污染 |
| `assembleRelease` | PASSED | 证明当前生产变体可编译、压缩、lintVital 并产出 APK；不等同于安装/升级/签名链已通过 |
| 真实 FCM/Private/权限/安装 | NOT RUN | 不允许由 JVM 或 synthetic contract 代替 |

## 现有 device 测试形态

| 信号 | 当前值 | 解释 |
| --- | ---: | --- |
| `androidTest` tests | 75 | 强 Store/迁移与弱 UI/诊断混合 |
| `testing` 包 tests | 38 | Runtime 职责集中，需要按产品层级拆分 |
| path/fixture/state 协议候选引用 | 27 | 逐项迁移到 target App Context/App-owned Scenario |
| Runtime/testing `runCatching` 候选 | 34 | 不是全部有问题；控制面写入和准备失败不得被吞掉 |
| device 固定 sleep | 11 | 逐项改为 idling/condition/milestone 或保留必要系统等待并限界 |
| `assume/ignore` 候选 | 10 | 外部 Lane 可 BLOCKED；核心 hermetic 不能静默跳过 |
| 明示 proxy/ViewModel-state 用例 | 4 | 移到 component/integration，不能汇总成 App UI |

## 首个可证伪基线结论

Android 的 JVM 层保持全绿，真实 App UI 旅程已扩到消息目的和主导航可达性；Events/Things/Channels 当前只证明页面可达，尚未证明其完整业务动作，真实系统能力亦未运行。因此“页面可达”“业务旅程已证明”和“系统能力已证明”继续分栏报告。

## 当前执行阻力

- 当前临时 emulator 已完成代表性执行；CI 用一个 API 35 代表设备，避免矩阵爆炸。首个 emulator 在开测前消失，按环境故障记录并仅恢复一次。
- 测试准备、Runtime path 和内部 state 仍混在 `testing` 包；准备失败可能延迟到 UI timeout。
- WP1 先提供 doctor、App-owned session DB 和可重复主设备，再扩展 UI；不在无设备时反复修 runner 参数。

## WP0 退出裁决

- 导出 helper 未发现真实入口，列为删除候选，不为其接通入口或新增 UI 测试。
- instrumentation 默认会话已改用唯一 `pushgo-quality-<session>.db`；旧 device tests 不再以生产名数据库作为准备区。
- WP0 于 2026-08-27 退出；消息核心 device 证据已执行，其余真实 FCM/权限/安装/物理性能结论保持 `NOT RUN`。
