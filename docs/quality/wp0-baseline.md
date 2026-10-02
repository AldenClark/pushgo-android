# Android WP0 质量基线

采集时间：2026-08-27。计数仅用于定位候选弱点，不计分、不直接判失败。

## 新鲜执行证据

| 检查 | 结果 | 证据边界 |
| --- | --- | --- |
| API 37 emulator 固定 PR 标准消息刷新主链 | PASSED：`Medium_Phone / emulator-5554` 精确执行 `standardFixtureShowsAccurateContentAndSurvivesActivityRelaunch` 1/1、0 failure/skipped；focused 收据 `build/quality-results/android-focused-summary.json` | 原独立 refresh 方法已删除并入固定 PR standard 主链；同一 fixture、同一次启动和既有 relaunch 内，正式 Provider ingestion 产生精确 canonical 消息，打开前 Repository 正文准确且 unread、badge=`1`，进入准确详情后已读且 badge 消失，relaunch 后详情/已读持续。PR 仍 6 条，positive 由 13 降为 12；不外推真实 Provider 网络、物理设备或 OEM |
| Android doctor 睡眠设备准备归因 | PASSED：controlled emulator wake/dismiss/poll 与 sleeping physical device BLOCKED 两个宿主敏感性测试 2/2 | 首次刷新运行虽 `boot_completed` 但 `mWakefulness=Asleep`，Compose hierarchy 为空；doctor 现在只对 emulator 定向唤醒并要求 `Awake + unlocked`，物理设备保持人工授权边界。该结果证明准备归因与门禁，不替代产品 UI Oracle，也不声称真机通过 |
| API 37 emulator 合并前日常正向 `device` Lane | PASSED：当时 13 条 App-owned UI/业务正向旅程 + 18 条 migration/deletion/ACK/transport 数据边界，31/31、0 failure/skipped；`android-device-summary.json` product/test-system=`PASSED/PASSED`、selected=executed、issue 为空 | 这是 2026-08-30 的历史完整收据；当前集合已通过把独立 refresh 并入固定 PR standard 主链收缩为 12 条，不能用这份 13 条收据冒充当前 12 条聚合已重跑。Android 工作树未提交，因此 schema v2 收据准确为 `source_dirty=true`、`run_identity=null`；14 天观察按用户要求暂缓，也不外推真实 FCM、权限决策、Doze/OEM、物理性能或生产分发 |
| `./gradlew testDebugUnitTest` | PASSED：275 tests，0 failure/error/skipped | JVM/unit；不证明 Room device、真实 App UI 或系统能力 |
| API 37 emulator 核心质量旅程 | PASSED：收缩前完整 `device` 证据为 29 条 App 旅程 + 18 条高风险数据边界，47/47、0 failure/skipped；当前日常 `device` 为 12 条正向代表 + 3 条数据边界 | 历史完整执行证明 App-owned Room→Paging/Compose→消息空态/详情/重启/分页/已读/搜索/删除撤销/首次 slow/error/retry/慢刷新旧快照保留/真实导航，以及 Event 准确详情→确认关闭→slow in-flight→防重复→边界拒绝→详情错误归属→正式解析/持久化重试→closed/relaunch、Thing 三关系页签与关联 Event 同所有权、Channel/Settings 高价值目的链；原独立 refresh 正向方法已并入固定 PR standard 主链，discoverable 完整集合继续由 Nightly/Release 守住，不再消耗重复设备旅程 |
| API 37 emulator 通知权限、Doze、进程恢复、系统通知、Alert playback 与 Private Service | PASSED：当前结构化 system profile 的宿主权限/Doze/进程链全部通过，随后计划内系统方法精确 3/3、零 skipped/failed；最终收据 selected=executed、product/test-system=`PASSED/PASSED` | 权限链完成真实拒绝→App 解释→系统 Settings 开启→返回刷新，并以 permission、NotificationManager 与 App banner 三重收口。Doze 链完成受限风险说明/Settings 卡→真实系统 Allow→OS whitelist/返回刷新→恢复受限→会话级 snooze/relaunch→新会话提醒恢复；进程链证明旧 PID 消失、新 PID 恢复准确详情/read 状态并完成精确 HTTPS 浏览器往返。消息链证明 critical durable ingress→唯一系统通知 + `AlertPlaybackService` + 系统 `USAGE_ALARM` started→PendingIntent→准确详情/read/dedupe→停止/relaunch，并补齐 Event cold、Thing warm 与 Message 竞争详情 owner；Private 链证明 Settings→真实 foreground Service/通知→离开存活→PendingIntent→relaunch→切回 FCM 清理。脚本恢复权限、首次请求偏好与 battery whitelist。真实外部 FCM/private delivery、实际扬声器可听性、Doze snooze 到期、dismiss/reboot/package replace、进程死亡和 OEM/真机继续 `NOT RUN` |
| API 37 emulator 真实进程退出/恢复与 Open URL 系统交接 | PASSED：宿主正向链 1/1；错误正文和错误 URL 敏感性负控均按预期失败 | App-owned 未读 canonical→准确详情/读取动作→旧 PID `8750` 消失→新 PID `8984`→准确 title/body 与已读状态持久→生产 Open URL→唯一 HTTPS handler/Chrome 可见精确地址→返回同一详情；不是 Activity recreation、PID/按钮/浏览器前台或文件检查。复用系统 Lane 已安装 App，零产品重试；网页内容/网络 SLA、浏览器/OEM 矩阵、低内存 OS eviction、reboot、真机启动与物理性能继续 NOT RUN |
| API 37 Event 失败恢复增量旅程 | PASSED：主 Event 与 Thing 关联 Event focused 均 1/1，随后完整 device 47/47；`android-device-summary.json` 为 product/test-system=`PASSED/PASSED` | 证明准确详情、确认后可见 in-flight、防重复、详情错误归属、拒绝后 ongoing、重试经正式通知解析/持久化后 closed 与 activity relaunch；不替代真实 Gateway、FCM 或性能 |
| 测试会话异步隔离负控 | PASSED：主 Event focused 1/1；执行中主动注入外部 token callback | 证明 session 建立前后竞态不会再由真实 Firebase/provider/持久化 Worker 改写 App-owned fixture；不以文件、配置位或 DB 可打开替代业务闭环 |
| 宿主负控与正式设备收据隔离 | PASSED：磁盘不足负控写入临时 `QUALITY_RESULTS_ROOT`；核心 pr-ui 重新 6/6 后运行全量 147 条宿主测试，正式收据 SHA-256 前后同为 `3990bee225c1ada8d985ad24e88c4a1bbd0bb333d4492729ce3f459e112b2ba7` | 修复前宿主负控会把真实 `android-pr-ui-summary.json` 覆盖成 `NOT_RUN/BLOCKED`；现在负控仍生成自己的六态临时收据，但不能污染保留的设备证据。该合同保护证据归因，不替代任何产品 Oracle |
| API 37 emulator 核心数据边界 | PASSED：当前精确选择 20/20、零 skipped/failed，完整调用 53 秒 | Production profile 下证明 v21/v23/v24/v25 与当前业务状态 v27/v28 迁移、删除恢复与 gateway-scoped ACK；与 Quality UI 进程隔离，防止 DB 会话污染 |
| `assembleRelease` | PASSED | 证明当前生产变体可编译、压缩、lintVital 并产出 APK；不等同于安装/升级/签名链已通过 |
| Controlled update-install 正向机制 | PASSED：API 37 emulator、v1.3.0→v1.3.1；本表为持久摘要，本机原始证据 `build/quality-results/android-update-install/20260829-181846/evidence.json` | 1 次产品安装动作、0 次业务重试、设备阶段 34 秒；真实下载、SHA/archive/signer fail-closed 校验、PackageInstaller 替换、更新后重启及精确标准消息数据；两份 Release-like R8 APK 的构建成本决定它只进 `update-install`/Release，不进日常 Lane；不替代生产分发签名/公网 feed 或真机/OEM policy |
| API 37 emulator 性能目的与慢加载敏感性 | PASSED：真实 Room 100k 1/1、正向 Macrobenchmark 2/2、慢加载负控按预期失败、Release/Profile 隔离通过 | 正向要求 1k canonical 启动与详情准确；负控在相同数据注入 3,500ms 首次加载延迟，准确 sentinel 出现后观察 4,187ms，高于 2,000ms 预算。负控独立记 product `NOT_RUN` / test-system `PASSED`，增量约 16 秒；模拟器结果只证明机制和 Oracle 敏感性，物理启动/frame 预算继续 `NOT RUN` |
| 变更影响 Release Lane | PASSED：`android-release-summary.json` 的 product/test-system 均为 `PASSED`、selected claims 无缺项；该历史收据尚未包含上行新增 update-install | 覆盖 29 条 App UI、50 条非跳过 data/Worker 边界、系统通知/PendingIntent、zh-CN 大字体、100k Room、2 条 Macrobenchmark 目的检查及 Release/Profile/R8/lint/package；100k opt-in helper 的明确 skip 不计入通过。real FCM、用户权限选择、Doze/重启、生产分发/OEM 安装、物理无障碍与物理性能仍为 NOT RUN |
| 真实 FCM/Private、用户权限决策、生产分发与真机/OEM 安装 | NOT RUN | 不允许由 JVM、controlled benchmark 机制证据或 synthetic contract 代替 |

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
- WP0 于 2026-08-27 退出；消息核心 device 与 controlled update-install 机制证据已执行，其余真实 FCM、用户权限决策、生产分发/真机 OEM 安装和物理性能结论保持 `NOT RUN`。
