# Android WP0 质量基线

采集时间：2026-08-27。计数仅用于定位候选弱点，不计分、不直接判失败。

## 新鲜执行证据

| 检查 | 结果 | 证据边界 |
| --- | --- | --- |
| `./gradlew testDebugUnitTest` | PASSED：275 tests，0 failure/error/skipped | JVM/unit；不证明 Room device、真实 App UI 或系统能力 |
| API 37 emulator 核心质量旅程 | PASSED：当前 `device` Lane 29 条 App 旅程 + 18 条高风险数据边界，47/47、0 failure/skipped | 证明 App-owned Room→Paging/Compose→消息空态/详情/重启/分页/已读/搜索/删除撤销/首次 slow/error/retry/慢刷新旧快照保留/真实导航，以及 Event 准确详情→确认关闭→slow in-flight→防重复→边界拒绝→详情错误归属→正式解析/持久化重试→closed/relaunch、Thing 三关系页签与关联 Event 同所有权、Channel/Settings 高价值目的链；另有 zh-CN + fontScale 1.5 + unread=1 的导航 label/badge 可读与分离证据，以及真实数据驱动的 39→38→消失/relaunch；不证明真实 Gateway/FCM、权限拒绝或物理性能 |
| API 37 Event 失败恢复增量旅程 | PASSED：主 Event 与 Thing 关联 Event focused 均 1/1，随后完整 device 47/47；`android-device-summary.json` 为 product/test-system=`PASSED/PASSED` | 证明准确详情、确认后可见 in-flight、防重复、详情错误归属、拒绝后 ongoing、重试经正式通知解析/持久化后 closed 与 activity relaunch；不替代真实 Gateway、FCM 或性能 |
| 测试会话异步隔离负控 | PASSED：主 Event focused 1/1；执行中主动注入外部 token callback | 证明 session 建立前后竞态不会再由真实 Firebase/provider/持久化 Worker 改写 App-owned fixture；不以文件、配置位或 DB 可打开替代业务闭环 |
| API 37 emulator 核心数据边界 | PASSED：18/18 | Production profile 下证明迁移、删除恢复与 ACK；与 Quality UI 进程隔离，防止 DB 会话污染 |
| `assembleRelease` | PASSED | 证明当前生产变体可编译、压缩、lintVital 并产出 APK；不等同于安装/升级/签名链已通过 |
| 变更影响 Release Lane | PASSED：`android-release-summary.json` 的 product/test-system 均为 `PASSED`、selected claims 无缺项 | 覆盖 29 条 App UI、50 条非跳过 data/Worker 边界、系统通知/PendingIntent、zh-CN 大字体、100k Room、2 条 Macrobenchmark 目的检查及 Release/Profile/R8/lint/package；100k opt-in helper 的明确 skip 不计入通过。real FCM、用户权限选择、Doze/重启/安装、物理无障碍与物理性能仍为 NOT RUN |
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

Android 的 JVM 层保持全绿，真实 App UI 旅程已扩到消息、Event、Thing、Channel accepted mutation 与 Settings 页面可见性目的；远端拒绝/补偿、其余 Settings 和真实系统能力仍未运行。因此“页面可达”“accepted 业务旅程已证明”和“外部/系统能力已证明”继续分栏报告。

## 当前执行阻力

- 当前临时 emulator 已完成代表性执行；CI 用一个 API 35 代表设备，避免矩阵爆炸。首个 emulator 在开测前消失，按环境故障记录并仅恢复一次。
- 测试准备、Runtime path 和内部 state 仍混在 `testing` 包；准备失败可能延迟到 UI timeout。
- WP1 先提供 doctor、App-owned session DB 和可重复主设备，再扩展 UI；不在无设备时反复修 runner 参数。

## WP0 退出裁决

- 导出 helper 未发现真实入口，列为删除候选，不为其接通入口或新增 UI 测试。
- instrumentation 默认会话已改用唯一 `pushgo-quality-<session>.db`；旧 device tests 不再以生产名数据库作为准备区。
- WP0 于 2026-08-27 退出；消息核心 device 证据已执行，其余真实 FCM/权限/安装/物理性能结论保持 `NOT RUN`。
