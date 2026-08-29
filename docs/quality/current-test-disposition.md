# Android 当前 device 测试处置清单

基线日期：2026-08-27；2026-08-28 退役并完成本轮 Event 失败恢复增量后，当前 `androidTest` 共 101 个 `@Test`。此文件是 WP0 迁移清单，不是产品通过 Manifest。

## 按文件逐测试处置

### `PendingLocalDeletionRoomDeviceTest` — keep / move to deletion integration lane

保留：`pendingAndCommittingRowsSurviveCloseAndRecover`、`cancelIsDurableAcrossCloseAndReopen`、`retryPendingCannotBeCancelledAfterFirstClaim`、`permanentFailureIsNotActiveAfterReopenAndDoesNotBlockNextIntent`、`channelNotificationRetryUsesPersistedOperationAfterDatabaseReopen`、`entityNotificationReplayUsesActiveGroupMetadataAfterRoomRowsAreGone`。这些用例证明 Room/reopen/notification reconciliation，不单独声称 UI Undo 已可用。

### `ProviderAckScopeDeviceTest` — keep

保留：`sameDeliveryIdFromTwoGatewaysPersistsAndAcksInIndependentScopes`、`definitiveLegacyFailureClearsThePreSendCrashMarker`、`legacyDestructivePullIsDurableBeforeCanonicalPersistence`、`completedLegacyPullAtomicallyRemovesStagingAndMarksLedgerTerminal`、`ackedTombstonePrune_outlivesGatewayReplayWindowAndRemainsBatchedAndReferenceSafe`、`ackDrainKickSignal_onlyFiresOnEmptyToNonEmptyTransition`、`fairAckLoad_reservesCapacityForASecondGatewayBehindLargeBacklog`。归属 ACK durability/concurrency，不替代通知/UI。

### `PushGoDatabaseMigrationDeviceTest` — keep

保留：`appContainer_bootstrapsFromLegacyV21AndPreservesBusinessData`、`appContainer_prefersDataRichV21WhenEmptyV22Exists`、`appContainer_migratesCurrentV23InPlaceAndBuildsPerformanceState`、`appContainer_migratesV24AckOutboxWithoutGuessingGatewayOwnership`、`appContainer_migratesV25LedgerAndAckRetryStateWithoutCrossGatewayGuessing`。后续补 reopen 后真实页面 smoke。

### `ProviderGatewayIntegrationDeviceTest` — keep in contract lane

保留：`pull_with_and_without_deliveryId_matches_gateway_contract`、`pull_persists_message_event_and_thing_projections`。它们证明 sandbox contract/projection，不得汇总成真实 FCM 或 UI 通过。

### `WarpLinkNativeBridgeInstrumentedTest` — keep in platform lane

保留 `realJni_abiStartPollAndStop_areConsistent`；继续隔离真实 native runtime 资源和 ABI 失败，不能代替 Private Channel 用户旅程。

### `AccessibilityAcceptanceFixtureInstrumentedTest` — diagnostic

`prepareAccessibilityAcceptanceFixture_persistsStateForManualValidation` 只准备人工验收数据；文件存在或 seed 完成不计产品通过。后续改为 App-owned Scenario 并从产品结果中分离。

### `FcmTokenDiagnosticsInstrumentedTest` — diagnostic

`fcmTokenDiagnostics_dumpRuntimeConfigAndFetchResult` 只报告环境/账号/Token 获取状态；不得降级 synthetic 后声称真实 FCM 通过，日志继续掩码。

### `RuntimeChannelSwitchInstrumentedTest` — keep / move to transport integration

保留：`defaultStart_isFcmActive_andPersistenceAndTransportAreConsistent`、`switchFcmPrivateFcm_andRestart_keepsStateStorageAndTransportConsistent`、`failedSwitchRequest_doesNotChangeActiveChannel`、`inboundAcrossSwitch_keepsCanonicalSingleMessage_andLateOldDoesNotOverride`、`fakeEventStateMachine_coversSessionResumeReconnectAckAndPerformance`。最后一项的 performance 仅作受控 transport 指标，不作为 UI 性能结论。

### `RuntimeComposeUiAutomatorInstrumentedTest` — deleted after replacement

原用例虽部分启动/点击真实 UI，最终却读取 automation state、直接查询 Repository，并把搜索、筛选、transport 和性能混成一个基线。上述目的已分别由 `QualityMessageJourneyInstrumentedTest`、`QualitySettingsJourneyInstrumentedTest`、真实 Room Performance/Macrobenchmark 接管，文件已删除，不再参与发现或汇总。

### `RuntimeComposeUiBaselineInstrumentedTest` — deleted after replacement

环境反射探针没有产品目的，其余用例直接构造 ViewModel/Repository 并打印 timing/state，不能称为 UI。被真实 App-owned UI 与 transport integration 覆盖的部分已接管，文件已删除；未被接管的环境诊断不进入阻断 Lane。

### Synthetic JVM Runtime Store/transport cluster — deleted after replacement

`RuntimeFixtureGenerator*`、`RuntimeLocalStore*` 与 `RuntimeChannelSwitchCorrectnessTest` 复制了消息 Store、去重、投影与 transport 状态机，并用内存实现/自生成 snapshot 给自身判定。生产 Room/ACK/transport integration、真实 device journeys 和 Performance Lane 已提供更强终点，因此五个孤立文件整体删除；不以扩大 JVM 堆或保留 synthetic 100k 输出维持绿色。

### `RuntimeDataLayerInstrumentedTest` — keep / move to Store lane

保留 Store 风险边界，包括新增的 `encryptedRecoveryReparsesOriginalPayloadAndPreservesCanonicalIdentity`，它用正式 parser 建立 missing-key canonical row，再经生产 recovery service 验证准确明文以及 local/stable identity、已读、接收时间、通知 identity 和原密文不变。其余既有 18 项继续覆盖搜索/分页/去重/顺序/投影/删除/规模；100k 保持显式 opt-in/性能 Lane。Store 测试不单独声称 UI 正确，解密目的由 `QualitySettingsJourneyInstrumentedTest` 的真实入口旅程闭环。

### `RuntimePrivateChannelStateFlowInstrumentedTest` — keep / move to integration

保留：`privateChannelClient_stateFlow_covers_session_ack_reconnect_and_fcm_switch`、`privateDeliveryIdentity_isIsolatedByGatewayAndDeviceScope`、`settingsViewModel_uiState_stays_consistent_with_repository_and_transport`、`settingsViewModel_saveDecryptionConfig_preservesUntouchedExistingKey`、`channelRemovalTransactionRollsBackHistoryWhenSubscriptionUpdateFails`、`channelRemovalTransactionCommitsSubscriptionAndHistoryTogether`、`channelRemovalTransactionRejectsStaleSubscriptionVersion`。后续 UI 只补用户可见终点，不复制全部状态组合。

### `RuntimeSandboxGatewayInstrumentedTest` — keep in contract lane

保留 `sandboxE2E_providerPrivateSwitchAndStateConvergesWithoutDualActive`；凭据/路由不可用时为 `BLOCKED`，不允许提前 return 或 synthetic PASSED。

### `PendingLocalDeletionWorkBoundaryDeviceTest` — keep

保留 `durableRoomRow_isRecoveredThroughTheRealWorkerAfterStorageReopen`、`schedulerRegistersIndependentDelayedWakeupWithoutReplacingRunningWork`；验证 Worker 最终终点，不能只断言 enqueue。

### Accessibility component tests — keep, do not overclaim task completion

- `ListAccessibilitySemanticsTest`：`messageRow_exposesSummaryStateAndPrimaryActions`、`eventRow_exposesSummaryAndLifecycleState`、`thingRow_exposesSummaryAndOpenAction`；
- `MediaAccessibilityTest`：`imagePreviewDialog_exposesPaneAndActionLabels`、`playableImage_exposesExplicitPlayAction`；
- `SettingsAccessibilitySemanticsTest`：`settingsToggleRow_exposesStateAndTogglesThroughMergedSemantics`、`channelRow_exposesCopyActionThroughRowSemantics`、`productionDocumentationRows_useLocalizedSemanticsAndRouteEveryPage`、`documentationRowsHaveCompleteEnglishSimplifiedAndTraditionalChineseResources`；
- `SharedAccessibilitySemanticsTest`：`searchBar_exposesLabelAndEmptyState`、`circularActionButton_exposesAccessibleLabel`、`modalBottomSheet_exposesPaneTitle`、`pendingDeletionBar_announcesUndoWindowAsLiveRegion`。

这些用例保留为 component/a11y contract；VoiceOver/TalkBack 关键任务仍需物理/系统 Lane。

### `QualityAccessibilityLocalizationJourneyInstrumentedTest` — keep as representative device task

在 API 33+ 捕获并恢复 app locale/font scale，要求平台和实际 Activity 均为 zh-CN、`fontScale >= 1.49`，再从真实消息列表打开准确详情并完成 accepted 频道创建、核对最终 canonical 行；提交前核对字段值、按钮 enabled 和密码控件的 Password semantics，提交后区分 Sheet-owned 错误与准确成功行。它与全生产资源合同互补，但不冒充物理 TalkBack、焦点顺序或完整设备×语言矩阵。

### `MarkdownPlayableDrawableDeviceTest` — keep in media component lane

保留 `animatedGifWrapsAndStartsOnApi28`；后续补离屏/退出停止和资源回收，不声称 Message Detail 全旅程。

## 当前首要缺口

1. Messages、Event/Thing、Channel 本地 invalid/远端密码拒绝的 Sheet-owned 反馈、accepted mutation 与创建本地 commit 失败→本地回滚+远端补偿→正式重载无脏行→重试/relaunch、Settings 页面可见性、server 候选注册/本地 commit 失败不提交→回滚→重试成功→数据换域/持久化、decryption lifecycle、受保护写失败补偿、错误 Key 纠正和坏密文安全失败均已按真实目的拆成 App-owned 纵向旅程；FCM/Private selector 也已覆盖双向远端拒绝保持旧 route、Private 本地 mode/secret 提交中点失败后的远端补偿与本地回滚、同入口重试后提交与 relaunch。Channel 订阅既有频道及其安全补偿协议、rollback 自身再次失败的 UI、真实外部 FCM/Private delivery 仍是当前缺口。
2. 已删除的 Compose “UI baseline” 与 synthetic JVM Store 不再计入任何 Lane；剩余 `Runtime*` 名称文件只按真实 Room/ACK/transport 边界归属，不得从命名外推 UI 覆盖。
3. App-owned `QualityRuntime` 仍是 DEBUG 测试接入点，但只负责 typed session、fixture/fault/readiness；其状态或 artifact 不得作为产品终点。
4. 强 Room/ACK/迁移测试很多；消息系统通知的代表性纵向链路已映射到准确页面/数据终点与 Nightly/Release，Event/Thing 动作、系统 mark-read/delete/copy 仍需同样迁移。
5. 真实 FCM、用户权限拒绝/再次授权、Doze、进程死亡冷启动、OEM/真机、安装流程和性能设备证据仍需独立 Lane。

## 启动可靠性入口

`scripts/run_android_startup_reliability.sh` 用受控 emulator 连续执行 App-owned `empty.clean` 功能启动，默认 50 次且零重试；详细 Oracle、双状态和 scope 边界见 `docs/quality/startup-reliability.md`。它是 Runtime/启动改动后的 opt-in 证据，不进入每次 PR，也不因 focused 50/50 自动关闭多旅程 Compose aggregate flake。

## Test-system/flake 处置

`config/quality-test-system-issues.json` 取代分类器内无 owner/到期的字符串白名单。`android-quality-precondition` 是 0 重试的准备边界；`android-compose-snapshot-observer-runtime` 是有 owner 且 2026-09-11 到期的 active flake，同样不允许自动重试。分类器只看本轮 XML，且每一个 failure 都必须精确命中 active 登记；混入一个产品断言就按产品失败。收据必须记录 issue ID，未知/过期 ID、无 ID 的 `FLAKY`、无替代证据 quarantine 都被脚本拒绝。跨平台操作规则见兄弟 Apple 仓库的 `docs/quality/test-system-issue-governance.md`。

## 本轮新增与 Lane 调整

- `QualityMessageJourneyInstrumentedTest`：真实启动 App，覆盖 App-owned 纯空态、准确列表/详情/activity relaunch、52 条数据跨 page size 50、单条/全部已读与未读筛选往返、搜索排除/目标集合/详情、删除→隐藏→Undo→relaunch，以及删除后不撤销→真实 deadline 提交→仅目标永久消失→控制消息准确保留→AppContainer/Room 重建后不复活；`messages.filters` 用 5 条可手算 canonical 数据，但 UI 最低充分链只保留频道+标签 AND、未分组、当前未分组作用域全部已读、全局 badge 4→3 和 relaunch，标签格式/集合组合与详情分别下沉到低层和独立 P0 旅程。恢复旧的空频道 early-return 时，该旅程在未分组精确集合处稳定失败，还原后 1/1 通过。`messages.markdown` 另用一条代表消息，经真实列表进入详情，直接读取生产 Markwon 输出及实际 span 类型，证明 heading、任务列表、引用、表格、inline/code block 与 link，并从正式可访问正文对账表格单元；Debug 证据只在 App-owned quality session 暴露，不进入 Release。该 focused 旅程当前 1/1、约 20 秒、零重试；不做 Markdown 语法×设备全排列，也不冒充 media 或物理 TalkBack。其余仍覆盖首次 slow→真实空态、错误→点击 Retry→真实空态、慢刷新旧快照、新 Provider 拉取页经解析/持久化进入列表和详情、首次刷新失败保留旧快照并由 Retry 恢复，以及真实底部导航。详情正文 Oracle 限定在真实详情字段 owner 内核对准确内容，避免列表行与详情同文案造成双节点误归因；仍不以任意可见同文案、直接修改 Compose 集合或 DB 文件存在作为 Oracle。永久删除 Oracle 的 Undo 扰动负控会在目标仍存在处精确失败。
- `QualityEntityJourneyInstrumentedTest`：复用同一个 App-owned session 生命周期，但按 Entity 能力独立覆盖 Event 摄入→投影→准确详情→确认关闭→正式解析/持久化→仅进行中筛选排除→activity relaunch 后 closed 保留，以及 Thing 准确概览、Events/Messages/Updates 三个真实页签、三类准确关联详情、返回原 Thing/页签和 activity relaunch 后关系仍可达；新增失败恢复状态机要求 `ongoing → closing → rejected/ongoing → closing → canonical closed → relaunch closed`，确认层退出后必须出现用户可见进度、关闭入口不可重复、错误只留在 Event 详情，重试只能通过正式通知解析和持久化改变 canonical；Thing 关联 Event 入口同步使用相同所有权。首次关联入口实跑发现 `thing.standard` 虽有 channel ID 却未建立订阅密码，只能得到“Channel password missing”；现由 Event/Thing fixture 共用合法订阅准备函数后 focused 1/1 通过，说明 fixture 必须支撑真实动作而非只让页面有数据。该旅程不以 Room 行数、Sheet 壳或 test tag 存在作为最终 Oracle，2.5 秒 Debug 故障注入也不冒充性能证据。
- `QualitySettingsJourneyInstrumentedTest`：覆盖 Server、visibility、decryption lifecycle、真实密文结果与通知 transport selector。Server 同时覆盖候选拒绝，以及候选远端成功后 Room address 已写的 commit 中点失败；后者必须 rollback、activity 重启仍旧值且重试才提交。Decryption 从正式 ingress 证明错误 Key/坏密文结果，并覆盖受保护 secret 写后、Room metadata 前失败的补偿、重启未配置与重试配置。Transport 从真实 segmented control 证明 Private/FCM 双向准备失败时旧选择、token/service 后续动作和提示归属不变，并覆盖 Private 远端成功后本地 mode 已写、secure token 未清的提交中点失败→重新准备 FCM→本地回滚→activity 重启仍为 FCM→重试才提交；只有 token/网关 transport 边界为 typed quality replacement，不直接写 preferences/Room，也不冒充真实公网 delivery。
- `QualityChannelJourneyInstrumentedTest`：本地 invalid 证明错误只在 entry Sheet 且 validator 先于 token/远端动作；远端 `password_mismatch` 证明输入保留与同入口恢复；创建远端成功后在安全凭据已写/Room 未写中点失败，要求凭据回滚、远端 unsubscribe、关闭 Sheet 后正式重载无脏行、重试与 relaunch。正常旅程还要求密码控件具备真实 Password semantics，并继续创建、改名、双退订与 relaunch。只有外部 Gateway mutation 使用 typed boundary，Compose、Repository、Room、延迟删除事务与重启均走生产路径。
- `QualitySystemNotificationJourneyInstrumentedTest`：替换 legacy `EXTRA_MESSAGE_ID` 直塞 Activity 的形式覆盖，从两个独立 `InboundMessageWorker` delivery 进入正式 parser/persistence，证明相同业务 message 去重为一个 canonical row 和一条系统通知；通过 UIAutomator 的真实 notification shade 点击生产 PendingIntent，核对详情 owner 内精确 title/body、自动已读、通知消失以及无 shortcut 的 activity relaunch 后同一 row 仍只存在一次并保持已读。该用例进入 Nightly/Release，不进入日常 PR；它明确不声称真实 FCM、进程死亡冷启动、OEM 或真机通过。
- 通知权限准备不再使用 `executeShellCommand(...).close()` 的竞态写法；共享 helper 通过 `UiAutomation.grantRuntimePermission` 同步授予并轮询验证。失败使用 `QUALITY_PRECONDITION` 归因到 test-system，混有任何产品断言失败时分类器不会遮蔽产品失败。正文故意偏移负控已证明目的 Oracle 会失败，恢复后 API 37 系统纵向旅程通过。
- Quality device 进程显式建立唯一 `pushgo-quality-<session>.db`、session 专属 Keystore-encrypted preference 与 settings cache；结束时先释放 Container/Room，再准确删除 DB、两类 preferences 与 session artifacts。Production 数据/受保护偏好不读不写，避免迁移测试、Key 或 Gateway 状态跨用例污染。
- `device` lane 仅执行上述日常纵向旅程及迁移、删除、ACK 三类高风险数据边界；`nightly` 增加 data/transport/work 边界和真实系统通知/PendingIntent 旅程；`release` 复用这些显式高价值 UI 与 Nightly 风险边界并构建 Release，不再默认执行全部遗留 androidTest。诊断、极端规模和低后果组合不进入常规反馈链。
- 显式 device filter 现在必须生成本轮 fresh XML 且非 skipped 实际执行数大于 0 后才写入 `executed_claims`。错误方法名负控曾让 Gradle 报 `Starting 0 tests` 却退出成功；修复后同一输入为产品 `NOT_RUN` / test-system `FAILED`、`executed_claims=[]`，全 skipped XML 单元负控同样计为 0，真实方法则报告 `executed_test_count=1` 后才允许 PASSED。
- 本轮 `accessibility` Lane 在 API 37 `Medium_Phone` 的真实 zh-CN + fontScale 1.5 + unread=1 状态，分别核对“消息”label 与 badge“1”的可见几何、不重叠和真实点击终点，并继续完成准确消息详情及频道创建；临时把生产 badge 改成 9 时在 exact value Oracle 精确失败。最终 `device` Lane 已刷新，29 条 App 旅程与 18 条高风险数据边界全部通过且无跳过，收据为 `build/quality-results/android-device-summary.json`。
- “消息 + 未读徽标”事故归因为历史套件只验证节点存在/可点击，未验证动态装饰与基础标签共存时的可读性、几何分离、准确值、导航终点和状态转换；因此它是覆盖模型缺陷，不记成偶发漏例。今后 navigation owner 的 badge/loading/error/disabled 变更至少选择一个风险最高代表态进入 affected lane，不做低价值全排列。当前 Android 证据包括 zh-CN + fontScale 1.5 下 unread=1 的 label/badge 分离与真实详情终点，以及 39→38→消失→activity relaunch 不恢复；错误方法 selector 的 0-test 负控同时证明这些断言未执行时不能生成通过收据。
- Event 首次失败恢复实跑还暴露出测试会话建立前排队的 Firebase token coroutine 会在建立后继续执行，触发真实 subscription sync 并软删除 fixture 密钥；入口 guard 无法阻止这种 TOCTOU。现由 callback/调度入口与 coroutine/Worker 实际执行边界双重隔离，主 Event 用例在首次关闭 in-flight 时主动注入外部 token callback，并仍须证明 Sheet 退出后的详情 owner 错误、ongoing 不变、重试 closed 与 relaunch 持久，防止环境隔离再次退化成仅检查配置开关。
- 设备选择是 Lane 合同的一部分：未显式指定时 doctor 稳定优先 emulator；需要真机时必须传 `ANDROID_SERIAL`。每次 device invocation 都把 doctor 选出的唯一 serial 交给 Gradle，禁止已连接个人设备静默扩大执行范围。双设备在线负控已证明最小测试与最终 Release 都只运行在 API 37 emulator；物理系统证据继续由显式 Lane 触发。
- `config/quality-impact.json`、`scripts/quality_impact.py` 与 `scripts/quality_changed.sh` 已把产品变更映射到具名能力和最低 Lane。PR 的 host/device 阶段共享同一计划：普通 JVM 变化不启动模拟器，Compose/Room/Service 等风险升级时才运行对应代表设备 Lane；普通 `androidTest` 修改最低升级到 `pr-ui`，系统通知纵向测试/helper 的修改则专门升级到会执行自身系统面的 `nightly`；未映射产品路径直接 `BLOCKED`，文档变更写结构化 `NOT_RUN`。
- 计划中的 `required_checks` 必须进入 selected/executed 收据：更新 Feed/notes 在快速 PR Lane 做签名与语义契约，不无差别启动设备；Gradle/native/release 边界则执行 JNI、schema 和发布静态契约并保持 Release Lane。最近 120 次历史变更回放已修复 Room schema export、update feed 与旧 Connection Diagnosis 漏选；无效计划直接 `BLOCKED`。
- 路径计划只是不可低于的下限。AI/开发者仍需追 ViewModel、Room、错误分支、Service/Worker/Receiver 和 OS 消费者；Macrobenchmark 已建立精确 1k 数据与启动/详情目的 Oracle、Profile 和 Release 隔离契约，模拟器仅证明机制；物理设备性能在未显式提供设备/预算时明确 `not_run`，不得由 slow-state UI 或模拟器数字替代。
- 两个 JVM 100k helper 原先打印 `skipped=true` 后直接返回，JUnit 会错误计为通过；现已改为 `Assume.assumeTrue`，常规测试报告明确为 skipped。首次把这些 synthetic helper 纳入性能 Lane 时，内存假 Store 在搜索阶段 OOM；这不代表生产 Room 的用户性能，故不靠增大测试堆制造绿色，也不再作为 Lane claim。`scripts/quality_test.sh performance` 执行真实 Room 100k、受控 emulator 的 Macrobenchmark 目的 dry-run、Release/Profile 隔离；真机启动/详情/frame P95 只有显式非个人设备和 owner 预算齐全时才运行，否则单列 `not_run`。详见 `docs/quality/android-performance-runbook.md`。
