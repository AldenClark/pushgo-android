# Android 当前 device 测试处置清单

基线日期：2026-08-27。当前 `androidTest` 共 75 个 `@Test`。此文件是 WP0 迁移清单，不是产品通过 Manifest。

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

### `RuntimeComposeUiAutomatorInstrumentedTest` — rewrite

`composeRuntime_uiAutomatorBaseline_messageListDetailAndSettings` 保留真实启动/点击的部分，拆成 Message List/Detail/Settings 旅程；删除 Automation State/路径最终 Oracle，改查可见对象、动作和重启终点。

### `RuntimeComposeUiBaselineInstrumentedTest` — move / diagnostic

- `composeRuntime_environmentProbe_reports_inputManagerReflectionGap`：`diagnostic`，只报告测试环境能力；
- `composeRuntime_messageListBaseline_proxyThroughViewModelAndRepository`：`move` 到 component/integration，不能称 UI；
- `composeRuntime_detailAndSettingsBaseline_proxyThroughViewModelState`：`move` 到 component；
- `composeRuntime_settingsPrivateStagesAndTokenRecovery_matchViewModelUiState`：`move` 到 transport/ViewModel integration。

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

### `MarkdownPlayableDrawableDeviceTest` — keep in media component lane

保留 `animatedGifWrapsAndStartsOnApi28`；后续补离屏/退出停止和资源回收，不声称 Message Detail 全旅程。

## 当前首要缺口

1. Messages、Event/Thing、Channel 本地 invalid 的 Sheet-owned 反馈与 accepted mutation、Settings 页面可见性、server 候选注册失败不提交→重试成功→数据换域/持久化、decryption key 生命周期和合法 Key 恢复原加密消息已按真实目的拆成 App-owned 纵向旅程；Channel remote rejection/compensation、Gateway 本地 commit/rollback 写失败、错 Key/坏密文与 transport 切换仍是当前 P0 缺口。
2. Compose “UI baseline” 多数直接构造 ViewModel/Repository，是 component/integration，不是真实 App UI。
3. Runtime/Automation 仍交换内部状态和路径，容易把准备失败拖成 UI timeout。
4. 强 Room/ACK/迁移测试很多，但没有映射到页面内容、系统入口和 Release 门禁。
5. 真实 FCM、权限、Doze、通知动作、安装流程和性能设备证据仍需独立 Lane。

## 本轮新增与 Lane 调整

- `QualityMessageJourneyInstrumentedTest`：真实启动 App，覆盖 App-owned 纯空态、准确列表/详情/activity relaunch、52 条数据跨 page size 50、单条/全部已读与未读筛选往返、搜索排除/目标集合/详情、删除→隐藏→Undo→relaunch、首次 slow→真实空态、错误→点击 Retry→真实空态、慢刷新旧快照、新 Provider 拉取页经解析/持久化进入列表和详情、首次刷新失败保留旧快照并由 Retry 恢复，以及真实底部导航。详情正文 Oracle 限定在真实详情字段 owner 内核对准确内容，避免列表行与详情同文案造成双节点误归因；仍不以任意可见同文案、直接修改 Compose 集合或 DB 文件存在作为 Oracle。
- `QualityEntityJourneyInstrumentedTest`：复用同一个 App-owned session 生命周期，但按 Entity 能力独立覆盖 Event 摄入→投影→准确详情→确认关闭→正式解析/持久化→仅进行中筛选排除→activity relaunch 后 closed 保留，以及 Thing 准确概览、Events/Messages/Updates 三个真实页签、三类准确关联详情、返回原 Thing/页签和 activity relaunch 后关系仍可达；不再把 Entity 覆盖塞进聚合 Message 类，也不以 Room 行数、Sheet 壳或 test tag 存在作为最终 Oracle。该旅程还要求真实系统 Back 只关闭顶层关系详情，并通过生产标题/正文语义判断内容。
- `QualitySettingsJourneyInstrumentedTest`：覆盖 Server、visibility、decryption lifecycle 与真实密文结果。Server 同时覆盖候选拒绝，以及候选远端成功后 Room address 已写的 commit 中点失败；后者必须 rollback、activity 重启仍旧值且重试才提交。Decryption 从正式 ingress 证明错误 Key/坏密文结果，并覆盖受保护 secret 写后、Room metadata 前失败的补偿、重启未配置与重试配置；不直接写 preferences/Room/受保护存储，也不把 dismiss/configured 单独当结果。
- `QualityChannelJourneyInstrumentedTest`：先用缺失密码的本地 invalid 证明错误只在 entry Sheet，且 validator 在任何 token/远端动作之前执行；修正输入后错误消失并继续真实创建、改名、双退订与 relaunch。仅外部 Gateway accepted mutation 使用 typed boundary，Compose、Repository、Room、延迟删除事务与重启均走生产路径；不把 accepted 场景冒充远端拒绝/补偿证据。
- Quality device 进程显式建立唯一 `pushgo-quality-<session>.db`、session 专属 Keystore-encrypted preference 与 settings cache；结束时先释放 Container/Room，再准确删除 DB、两类 preferences 与 session artifacts。Production 数据/受保护偏好不读不写，避免迁移测试、Key 或 Gateway 状态跨用例污染。
- `device` lane 仅执行上述纵向旅程及迁移、删除、ACK 三类高风险数据边界；`nightly` 增加 data/transport/work 边界；`release` 复用显式高价值 UI 与 Nightly 风险边界并构建 Release，不再默认执行全部遗留 androidTest。诊断、极端规模和低后果组合不进入常规反馈链。
- 设备选择是 Lane 合同的一部分：未显式指定时 doctor 稳定优先 emulator；需要真机时必须传 `ANDROID_SERIAL`。每次 device invocation 都把 doctor 选出的唯一 serial 交给 Gradle，禁止已连接个人设备静默扩大执行范围。双设备在线负控已证明最小测试与最终 Release 都只运行在 API 37 emulator；物理系统证据继续由显式 Lane 触发。
- `config/quality-impact.json`、`scripts/quality_impact.py` 与 `scripts/quality_changed.sh` 已把产品变更映射到具名能力和最低 Lane。PR 的 host/device 阶段共享同一计划：普通 JVM 变化不启动模拟器，Compose/Room/Service 等风险升级时才运行对应代表设备 Lane；修改 `androidTest` 最低升级到 `pr-ui`，避免只编译不执行；未映射产品路径直接 `BLOCKED`，文档变更写结构化 `NOT_RUN`。
- 计划中的 `required_checks` 必须进入 selected/executed 收据：更新 Feed/notes 在快速 PR Lane 做签名与语义契约，不无差别启动设备；Gradle/native/release 边界则执行 JNI、schema 和发布静态契约并保持 Release Lane。最近 120 次历史变更回放已修复 Room schema export、update feed 与旧 Connection Diagnosis 漏选；无效计划直接 `BLOCKED`。
- 路径计划只是不可低于的下限。AI/开发者仍需追 ViewModel、Room、错误分支、Service/Worker/Receiver 和 OS 消费者；Macrobenchmark/物理设备性能在结果中明确 `not_run`，不得由 slow-state UI 用例替代。
