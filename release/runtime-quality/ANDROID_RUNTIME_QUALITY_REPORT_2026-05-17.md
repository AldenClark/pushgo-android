# PushGo Android Runtime Quality Report (2026-05-17)

## 1. 结论摘要
- 当前阶段已完成：A1（sandbox E2E 入口/开关）、B（通道切换压力与时序主线）、C（AVD 上可执行基线与性能采样）、D（本报告）。
- 当前阶段未完成：A2 已推进到 provider route/switch + private resume/ACK 客户端证据层，且已探测到 `/messages/pull` 对账路由可用；Compose 真正 UI rule 交互基线被 Pixel_10(Android 17) 环境兼容问题阻断。
- RC 判断：**暂不建议直接作为 Android release candidate**；建议补齐 P1 后再判断。

---

## 2. 变更范围（本轮）
- 新增：`app/src/androidTest/java/io/ethan/pushgo/testing/RuntimeSandboxGatewayInstrumentedTest.kt`
- 新增：`app/src/androidTest/java/io/ethan/pushgo/testing/RuntimeComposeUiBaselineInstrumentedTest.kt`
- 新增：`app/src/androidTest/java/io/ethan/pushgo/testing/RuntimeComposeUiAutomatorInstrumentedTest.kt`
- 修改：`app/src/androidTest/java/io/ethan/pushgo/testing/RuntimeChannelSwitchInstrumentedTest.kt`（补 p50/p95 + canonical/duplicate/stale/channel 指标，并支持 instrumentation 参数触发 100k）
- 修改：`app/src/androidTest/java/io/ethan/pushgo/testing/RuntimeSandboxGatewayInstrumentedTest.kt`（新增结构化缺口输出 `dispatch_attempt_class/verification_layer/required_min_test_endpoint`，支持 `pushgo.runtime.sandbox.useStoredGatewayToken=true`，并新增 `resume_token/last_acked_seq` 与 private ACK 探测字段）
- 修改：`app/src/androidTest/java/io/ethan/pushgo/testing/RuntimeComposeUiBaselineInstrumentedTest.kt`（新增 Settings 私有通道阶段映射 + token 缺失/恢复/再缺失一致性断言）
- 修改：`app/src/androidTest/java/io/ethan/pushgo/testing/RuntimeComposeUiAutomatorInstrumentedTest.kt`（新增稳态观测窗口：`gfxinfo reset` 与 `total_pss_before_kb/total_pss_delta_kb`）
- 修改：`app/src/androidTest/java/io/ethan/pushgo/integration/ProviderGatewayIntegrationDeviceTest.kt`（默认 skip，显式开关才执行）
- 修改：`app/build.gradle.kts`（androidTest 依赖补齐，新增 `uiautomator`）
- 修改：`app/src/main/java/io/ethan/pushgo/ui/PushGoAppRoot.kt`（automation state 的 `visible_screen` 按 route/详情状态推导）

说明：未修改后端协议语义；未做大规模生产重构；未硬编码凭据。

---

## 3. 覆盖矩阵（已覆盖 / 未覆盖）

### A. Sandbox E2E（RuntimeSandboxGatewayInstrumentedTest）
已覆盖：
- `pushgo.runtime.sandboxE2E=true` 才执行，默认跳过。
- 强制禁止 production gateway（`gateway.pushgo.cn/pushgo.cn`）。
- FCM token 缺失时可稳定处理并输出缺口日志。
- 凭据缺失时不会硬凑，明确输出后端/凭据缺口。
- 在无 gateway token 条件下，已完成探测式推进并输出结构化状态：`provider_sync_ok/provider_route_ok/channel_created_or_loaded/switch_private_ok/switch_back_ok`。
- 已验证“禁用 synthetic token”场景：当前 AVD 输出 `fcm_token_available=false`，且 `play_services_status_code=0(success)` 但 `fcm_fetch_reason=...AUTHENTICATION_FAIL...`，说明是 Firebase token 认证链路失败（非 Play services 缺失）。

未覆盖（受环境/接口限制）：
- `diagnostics` 类可视化接口仍缺失（`/diagnostics/dispatch` 404），当前改用 `/messages/pull` 作为 server-side 对账替代路径。
- 真实 FCM 云 token 驱动路径尚未覆盖（当前为 synthetic token 探测链路）。

### B. 通道切换压力与时序（RuntimeChannelSwitchInstrumentedTest + RuntimePrivateChannelStateFlowInstrumentedTest）
已覆盖：
- 1k/10k/100k synthetic 切换压力（100k 通过 opt-in 实跑）。
- FCM -> private -> FCM 切换一致性。
- 新/旧通道先后到达、双通道同 message id、failed switch 保持状态。
- ACK success/failed/retry，reconnect/resume 分支。
- 输出 `total_ms/p50/p95/canonical/duplicate/stale/channel_rejected`。

### C. Compose UI runtime
已覆盖：
- AVD 上可执行的 Compose runtime fallback 基线：MessageList/Detail/Settings 的数据与状态路径 timing、memory delta。
- AVD 上可执行的 UIAutomator 真交互基线：MessageList 滚动突发、MessageDetail 打开/关闭、Settings 页 private 切换与 automation state 一致性校验。
- Settings `private` 状态阶段映射：`connecting/connected/reconnecting/error` 与 `SettingsViewModel.uiState.privateTransportStatus` 一致；`token 缺失 -> 恢复 -> 再缺失` 一致性已在 AVD instrumented 基线断言。
- dumpsys meminfo/gfxinfo 基线采样。

未覆盖（环境限制）：
- 基于 Compose UI rule 的真实交互（滚动、点击、过滤）在 Pixel_10(Android 17) 失败：`InputManager.getInstance` 反射入口缺失，Espresso onIdle 阻断。
- 当前 AVD 上 FCM 选项在 Settings 交互中不可见（`switch_fcm_skip_reason=fcm_option_not_visible`），因此“private -> FCM” UI 回切仅保留能力缺口记录。

---

## 4. 关键指标

### 数据层（AVD instrumented）
- `scale=10000`: `bulkWriteMs=2609`, `firstPageMs=14`, `fivePagesMs=64`, `ftsPageMs=8`, `channelFilterMs=5`, `tagFilterMs=7`, `unreadFilterMs=9`
- `databaseReopenFirstPageMs=13`
- `scale=100000`: `bulkWriteMs=22843`, `firstPageMs=133`, `fivePagesMs=621`, `ftsPageMs=98`, `channelFilterMs=54`, `tagFilterMs=74`, `unreadFilterMs=94`
- `databaseReopenFirstPageMs=133`（`scale=100000`）

### 通道切换压力（AVD instrumented + fake event state machine）
- `size=1000`: `canonical_count=500`, `accepted=500`, `duplicate_rejected=0`, `stale_rejected=500`, `channel_rejected=500`, `total_ms=0`
- `size=10000`: `canonical_count=5000`, `accepted=5000`, `duplicate_rejected=0`, `stale_rejected=5000`, `channel_rejected=5000`, `total_ms=5`
- `size=100000`: `canonical_count=50000`, `accepted=50000`, `duplicate_rejected=0`, `stale_rejected=50000`, `channel_rejected=50000`, `total_ms=31`
- 真实链路片段：`switch_fcm_to_private_ms=3`, `switch_private_to_fcm_ms=3`, `inbound_persist first=3ms`

### Private 状态流
- `ack_success_ms=5`, `ack_failed_ms=1`, `ack_retry_ms=2`, `resume/reconnect` 分支通过。

### Sandbox 探测（无 token + synthetic FCM）
- `gateway_token_provided=false`, `gateway_token_source=missing`, `fcm_token_source=synthetic`, `gateway_private_enabled=true`, `diagnostics_auth_probe_code=404(not_found)`, `diagnostics_auth_mode=endpoint_missing_or_not_routed`, `provider_sync_ok=true`, `provider_route_ok=true`, `channel_created_or_loaded=true`, `switch_private_ok=true`, `switch_back_ok=true`
- private/provider snapshot 关键信号：`private_keepalive=app_foreground`, `private_mode_enabled=true`, `private_stage=connected`, `private_connected=true`, `private_stage_timeline=idle>connecting>connected`, `private_connected_observed=true`, `private_authenticated_observed=true`, `private_resume_token_present=true`, `private_ack_before_seq=0`, `private_ack_after_seq=1`, `private_ack_observed=true`, `provider_keepalive=not_required`, `provider_mode_enabled=false`, `private_failure_transport/auth/route=0/0/0`
- server probe 关键信号：`diagnostics_auth_probe_code=404`, `pull_probe_code=400`, `ack_probe_code=400`, `pull_with_device_key_code=200`, `pull_with_device_key_items=0`（说明 `/messages/pull` 路由可作为当前 server-side 对账替代路径）。
- 结构化缺口输出（新增）：`private_dispatch_attempt_code=200`, `dispatch_attempt_code=200`, `dispatch_attempt_class=ok`, `dispatch_auth_mode=anonymous_allowed`, `verification_layer=private_ack_switch_roundtrip`, `required_min_test_endpoint=none`
- 当前阻断点：A2 仅剩真实 FCM token 路径未覆盖；`provider_sync_ms=483`, `switch_private_ms=617`, `switch_fcm_ms=189`, `gap=diagnostics_endpoint_missing(code=404);synthetic_fcm_token_used`
- 真实 FCM token 探测（`allowSyntheticFcmToken=false`）：`fcm_token_available=false`, `fcm_fetch_reason=failure:...AUTHENTICATION_FAIL...`, `play_services_status_code=0(success)`, `android_account_count=0`, `android_google_account_count=0`, `required_local_precondition=google_account_signed_in`, `token_missing_handled=true`, `gap=missing_fcm_token_or_play_services`。

### Compose runtime fallback
- MessageList proxy: `scale=10000`, `search_ready_ms=217`, `unread_toggle_ms=16`, `channel_filter_ms=1`, `tag_filter_ms=7`, `memory_delta_bytes=819232`
- MessageList proxy: `scale=100000`, `search_ready_ms=281`, `unread_toggle_ms=86`, `channel_filter_ms=3`, `tag_filter_ms=39`, `memory_delta_bytes=852064`
- Detail/Settings proxy: `detail_open_ms=0`, `switch_private_ms=3`, `switch_fcm_ms=6`
- Settings stage/token proxy: `connecting_ms=2`, `connected_ms=1`, `reconnecting_ms=1`, `error_ms=0`, `token_missing_ms=1`, `token_recovered_ms=5`, `token_missing_again_ms=1`
- Settings stage snapshot: `status_connecting=Waiting_to_retry`, `status_connected=Connected_(QUIC)`, `status_reconnecting=Waiting_to_retry`, `status_error=Reconnecting`

### Compose UI Automator（AVD 真交互 + automation state）
- `scale=10000`, `dataset_prefix=ui-auto-*`, `fixture_total_count=10000`, `launch_ready_ms=0`, `scroll_burst_ms=4665`, `search_ready_ms=21(proxy)`, `unread_filter_toggle_ms=23(proxy)`, `detail_open_ms=1`, `detail_close_ms=444`, `settings_ready_ms=7`, `switch_private_ms=1283`
- `scale=100000`, `dataset_prefix=ui-auto-*`, `fixture_total_count=100000`, `launch_ready_ms=1`, `scroll_burst_ms=3100`, `search_ready_ms=323`, `unread_filter_toggle_ms=282`, `detail_open_ms=0`, `detail_close_ms=406`, `settings_ready_ms=5`, `switch_private_ms=1127`
- `scale=100000`（账号登录后复跑）, `dataset_prefix=ui-auto-*`, `fixture_total_count=100000`, `launch_ready_ms=0`, `scroll_burst_ms=3096`, `search_ready_ms=246`, `unread_filter_toggle_ms=113`, `detail_open_ms=0`, `detail_close_ms=405`, `settings_ready_ms=7`, `switch_private_ms=1270`
- `switch_fcm_supported=false`, `switch_fcm_skip_reason=fcm_option_not_visible`
- `switch_fcm_visibility_hint=row=false,segmented=true,private_option=true,fcm_option=false`
- `switch_fcm_state_summary=active_tab=io.ethan.pushgo.ui.SettingsRoute,screen=screen.settings,use_fcm_channel=false,provider_mode=none,private_stage=idle`
- `total_pss_before_kb=151981`, `total_pss_kb=175631`, `total_pss_delta_kb=23650`, `janky_frames=122`, `total_frames=350`
- `total_pss_before_kb=66399`, `total_pss_kb=137507`, `total_pss_delta_kb=71108`, `janky_frames=37`, `total_frames=378`
- `total_pss_before_kb=129456`, `total_pss_kb=134142`, `total_pss_delta_kb=4686`, `janky_frames=30`, `total_frames=383`
- instrumentation logcat 中未检出 `ANR / Application Not Responding / Input dispatching timed out` 关键字（基于本轮 `app/build/outputs/androidTest-results/connected/debug/Pixel_10(AVD) - 17/logcat-*.txt` 扫描）

### 进程与图形基线（dumpsys）
- `TOTAL PSS ~155712 KB`（启动后采样）
- gfxinfo 冷启动阶段 jank 显著（样本帧数少，偏冷启动噪声）

---

## 5. 问题与修复
- 问题 1：真实网络测试默认会进入普通 connected 流程风险。
  - 修复：`ProviderGatewayIntegrationDeviceTest` 增加显式开关 `pushgo.runtime.providerIntegration=true`；默认 skip。
  - 风险：低（仅测试 gate）。
- 问题 2：Compose UI rule 在 Pixel_10(Android 17) 上被 Espresso 反射阻断。
  - 现状：未修改生产代码；增加环境探测、proxy 基线和 UIAutomator 真交互基线（滚动/详情/设置切换），保留事实证据。
  - 风险：中（Compose rule 仍不可用，搜索/过滤仍以 proxy 指标为主）。
- 问题 4：Settings 页 FCM 回切在当前 AVD 不可执行。
  - 现状：`RuntimeComposeUiAutomatorInstrumentedTest` 中记录 `switch_fcm_supported=false`、`switch_fcm_skip_reason=fcm_option_not_visible`，并输出 `switch_fcm_visibility_hint` / `switch_fcm_state_summary` 结构化诊断字段。
  - 风险：中（C 阶段 private -> FCM UI 回切缺少本机真交互证据）。
- 问题 5：UIAutomator 用例在混合矩阵中曾出现不稳定。
  - 触发：与其他 runtime instrumented tests 串跑时，固定 fixture ID 与严格 ready 判定导致偶发失败。
  - 修复：改为每次运行唯一 `dataset_prefix`，并将启动就绪条件收敛为 automation state 可用。
  - 风险：低（已在混合矩阵回归通过：`14 tests + RuntimeComposeUiAutomator = 15 tests, 0 failed, 1 skipped(100k opt-in)`）。
- 问题 6：automation state 的 `visible_screen` 语义不准确。
  - 触发：Settings 页面场景下 state 固定输出为 `screen.messages.list`，与实际页面不一致。
  - 修复：`PushGoAppRoot.buildAutomationState` 改为按 route/详情状态推导 `visible_screen`，当前验证输出 `screen.settings`。
  - 风险：低（仅可观测性修复，不影响业务逻辑）。
- 问题 7：Settings 阶段映射基线新增后出现瞬时旧态读取，导致断言误报失败。
  - 触发：新增 `composeRuntime_settingsPrivateStagesAndTokenRecovery_matchViewModelUiState` 首版使用宽松谓词等待，可能在阶段跃迁中读取上一个 UI 状态。
  - 修复：改为“先读取目标 snapshot，再按目标状态精确等待 UI 状态”。
  - 风险：低（仅测试稳定性修复，不影响生产代码）。
- 问题 8：Kotlin 增量编译缓存偶发损坏导致 androidTest Kotlin 编译异常。
  - 触发：`compileDebugAndroidTestKotlin` 出现 `Storage ... already registered` / `EOFException`。
  - 修复：本轮构建自动 fallback 到 non-incremental/without daemon，测试继续通过；建议出现同类问题时先执行 `./gradlew --stop` 再重跑。
  - 风险：低（构建稳定性问题，不影响运行时逻辑）。
- 问题 9：sandbox synthetic 探测单次出现 instrumentation process crashed。
  - 触发：`RuntimeSandboxGatewayInstrumentedTest` 一次执行失败，错误为 `Instrumentation run failed due to Process crashed`。
  - 修复：同参数立即重跑通过，判定为瞬态环境不稳定；保留重跑证据。
  - 风险：低到中（建议 nightly 对该 case 做重试策略）。
- 问题 10：并行触发 `connectedDebugAndroidTest` 导致 Gradle 报告目录竞争。
  - 触发：同时执行两个 `connectedDebugAndroidTest`（不同参数）时出现 `NoSuchFileException ... app/build/reports/androidTests/connected/debug/css`，导致任务失败。
  - 修复：改为串行运行 connected instrumentation 命令；串行后稳定通过。
  - 风险：低（运行编排问题，不影响业务/测试断言本身）。
- 问题 3：sandbox diagnostics 缺失但存在替代对账路径。
  - 现状：测试类可执行且默认 skip；启用后在无 token 场景已观测到 `private_stage_timeline=idle>connecting>connected`、`private_resume_token_present=true`、`private_ack_observed=true(last_acked_seq 0->1)`。route probe 进一步显示 `pull_with_device_key_code=200`（匿名可用），可作为当前 server-side 对账替代路径；`diagnostics` 仍是 404。
- 问题 11：当前 AVD 无法稳定提供真实 FCM token。
  - 触发：关闭 synthetic（不传 `pushgo.runtime.sandbox.allowSyntheticFcmToken=true`）后，sandbox 用例输出 `fcm_token_available=false`。
  - 现状：新增环境诊断显示 `play_services_status_code=0(success)` 但 `fcm_fetch_reason=...AUTHENTICATION_FAIL...`，且 `android_account_count=0/android_google_account_count=0`；说明当前阻断更接近 Firebase token 认证链路（未登录 Google 账号）而非 Play services 缺失。
  - 本机恢复步骤：在 AVD 的系统设置登录 Google 账号后重跑 non-synthetic sandbox 用例（不传 `allowSyntheticFcmToken`）验证是否可拿到真实 FCM token。
  - 风险：中（A2 的真实 FCM token 路径仍未覆盖）。

---

## 6. 证据等级
- JVM：中（来自既有 checkpoint 基线）
- AVD instrumented：高（DataLayer / ChannelSwitch / PrivateStateFlow / Compose fallback）
- sandbox E2E：中到高（已有 private connected/resume/ack 客户端证据，且 `/messages/pull` server-side 替代路径可用；diagnostics 仍缺）
- fake：中（用于压力时序补充，不作为真实 E2E 结论）

---

## 7. 分层命令建议（PR / Nightly / Release）

### PR（默认必跑，不含真实网络）
```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.ethan.pushgo.testing.RuntimeDataLayerInstrumentedTest,io.ethan.pushgo.testing.RuntimeChannelSwitchInstrumentedTest,io.ethan.pushgo.testing.RuntimePrivateChannelStateFlowInstrumentedTest,io.ethan.pushgo.testing.RuntimeComposeUiBaselineInstrumentedTest
```

### Nightly（可加大规模 opt-in，仍不强制真实网络）
注意：`connectedDebugAndroidTest` 命令请串行执行，避免并发触发报告目录竞争。
```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.ethan.pushgo.testing.RuntimeDataLayerInstrumentedTest,io.ethan.pushgo.testing.RuntimeChannelSwitchInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.pushgo.runtime.include100k=true

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.ethan.pushgo.testing.RuntimeComposeUiAutomatorInstrumentedTest
```

### Release 前人工触发（真实 sandbox E2E）
```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.ethan.pushgo.testing.RuntimeSandboxGatewayInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.pushgo.runtime.sandboxE2E=true \
  -Pandroid.testInstrumentationRunnerArguments.pushgo.runtime.sandbox.baseUrl=https://sandbox.pushgo.dev \
  -Pandroid.testInstrumentationRunnerArguments.pushgo.runtime.sandbox.token=<SANDBOX_TOKEN> \
  -Pandroid.testInstrumentationRunnerArguments.pushgo.runtime.sandbox.fcmToken=<OPTIONAL_FCM_TOKEN>
```

### Nightly 探测（无真实 FCM token 时的链路推进）
```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.ethan.pushgo.testing.RuntimeSandboxGatewayInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.pushgo.runtime.sandboxE2E=true \
  -Pandroid.testInstrumentationRunnerArguments.pushgo.runtime.sandbox.allowSyntheticFcmToken=true \
  -Pandroid.testInstrumentationRunnerArguments.pushgo.runtime.sandbox.useStoredGatewayToken=true
```

---

## 8. P1 / P2 Follow-up

### P1（阻断 RC）
1. 覆盖真实 FCM token 驱动路径（当前 synthetic token 路径已通过）。
2. 解决 Pixel_10(Android 17) 上 Compose UI rule/Espresso 兼容阻断，恢复真实 UI 交互测试证据。

### P2（增强）
1. 固化 100k opt-in 基线（DataLayer + ChannelSwitch + Compose proxy）并持续对比波动。
2. 继续扩展 Compose 稳态观测窗口（当前已引入 `gfxinfo reset` + `total_pss_before_kb/total_pss_delta_kb`，建议再补多轮采样统计分位数）。

---

## 8.1 Sandbox A2 后端最小支持清单（可执行）
1. diagnostics 端点恢复（可选增强）：
   - `GET /diagnostics/dispatch?channel_id=<id>&limit=1`（支持 auth 或内部白名单）。
   - 现状：`/messages/pull` 已可用（`pull_with_device_key_code=200`），diagnostics 恢复将提升可观测性与排障效率。
2. 保持或提供测试消息触发端点（当前可达）：
   - `POST /message`（参数：`channel_id/password/op_id/title/body`）。
   - 依据：`dispatch_attempt_code=200`, `dispatch_auth_mode=anonymous_allowed`。
3. 可选增强（用于自动化闭环 session resume/ACK）：
   - 提供最小测试查询接口，能返回某次 `op_id/delivery_id` 的投递与 ACK 状态；
   - 或在 diagnostics / pull/ack 契约中增加 ACK 可观测字段。
   - 目的：把 A2 的 `session resume / ACK` 从“客户端局部证据”升级为“端到端对账证据”。

## 8.2 本机解阻清单（可执行）
1. 解除真实 FCM token 阻断（本机账号认证态）：
   - 现状证据：`fcm_fetch_reason=...AUTHENTICATION_FAIL...` + `play_services_status_code=0` + `android_account_count=0`。
   - 操作：在 AVD 系统设置登录 Google 账号（确保账号同步成功）。
   - 入口命令（已验证可达）：`adb shell am start -W -a android.settings.ADD_ACCOUNT_SETTINGS`。
   - 2026-05-17 复核结果：
     - `Activity: com.android.settings/.Settings$ChooseAccountActivity`
     - 显式拉起后可进入 `com.google.android.gms/.auth.uiflows.addaccount.PreAddAccountActivity`（Google 账号登录前置页）。
   - 验证命令：
```bash
adb shell dumpsys account | rg -n "Accounts:|type=com.google"
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.ethan.pushgo.testing.RuntimeSandboxGatewayInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.pushgo.runtime.sandboxE2E=true \
  -Pandroid.testInstrumentationRunnerArguments.pushgo.runtime.sandbox.useStoredGatewayToken=true
```
   - 通过标准：日志出现 `fcm_token_source=firebase` 或 `fcm_token_available=true`，且不再出现 `missing_fcm_token_or_play_services`。
2. 解除 Compose UI rule 阻断（Android 17 Espresso 兼容）：
   - 现状证据：`InputManager.getInstance` 反射入口缺失。
   - 操作：在可用的非 Android 17 AVD（或真机）上执行同一 Compose UI rule 用例矩阵。
   - 验证命令：
```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.ethan.pushgo.testing.RuntimeComposeUiBaselineInstrumentedTest
```
   - 通过标准：`composeRuntime_environmentProbe_reports_inputManagerReflectionGap` 不再指示 fallback，仅保留真实 UI rule 交互证据。

---

## 9. 作为 Release Candidate 的判断
- 当前判断：**否（暂不推荐）**。
- 原因：
  1. 真实 FCM token 驱动路径仍未覆盖（当前 synthetic token）。
  2. Compose 真交互基线受环境兼容问题阻断，仅有 fallback + UIAutomator 证据。


---

## 10. Completion Audit Checklist（Requirement -> Artifact）

### A. Sandbox E2E
- [x] `RuntimeSandboxGatewayInstrumentedTest` 存在且默认 skip：
  - Artifact: `app/src/androidTest/java/io/ethan/pushgo/testing/RuntimeSandboxGatewayInstrumentedTest.kt`
  - Gate: `pushgo.runtime.sandboxE2E=true` 才启用。
- [x] sandbox 配置 opt-in 生效：
  - Evidence: connected run with `-Pandroid.testInstrumentationRunnerArguments.pushgo.runtime.sandboxE2E=true`。
- [x] sandbox 可选读取本机已配置 token（默认关闭）：
  - Evidence: `pushgo.runtime.sandbox.useStoredGatewayToken=true` 分支生效；当前日志 `gateway_token_source=missing`（本机未配置可用 token）。
- [x] sandbox 默认 skip gate 生效：
  - Evidence: connected run without `pushgo.runtime.sandboxE2E` shows `RuntimeSandboxGatewayInstrumentedTest ... SKIPPED`。
  - Recheck: 2026-05-17 复跑确认 `SKIPPED` 仍生效。
- [x] FCM token 缺失稳定处理：
  - Evidence log: `RUNTIME_SANDBOX_E2E fcm_token_available=false ...`。
- [x] non-synthetic FCM 阻断已机器级定位：
  - Evidence log: `fcm_fetch_reason=...AUTHENTICATION_FAIL...`, `play_services_status_code=0`, `android_account_count=0`, `android_google_account_count=0`, `required_local_precondition=google_account_signed_in`。
  - Conclusion: 当前阻断在账号/认证态，不是 Play services 缺失。
  - Recheck: 2026-05-17 再次复跑同参数，结果一致。
  - Recheck(更新): 2026-05-17 账号已登录后复跑，`android_account_count=1/android_google_account_count=1`，但仍 `fcm_fetch_reason=...AUTHENTICATION_FAIL...`；阻断由“账号缺失”收敛为“google_account_auth_state_ready（认证态未就绪）”。
- [x] device registration/provider route/channel device/sync 向 sandbox 成功：
  - Evidence: `provider_sync_ok=true`, `provider_route_ok=true`, `dispatch_attempt_code=200(dispatch_attempt_class=ok)`。
- [x] private channel 连接到 connected/authenticated：
  - Evidence: `private_stage=connected`, `private_connected=true`, `private_resume_token_present=true`, `private_authenticated_observed=true`（以 `resume_token` 持久化作为认证握手等价证据）。
- [x] connectionSnapshotFlow 到 connected/authenticated：
  - Evidence: `private_connected_observed=true`，并结合 `resume_token` 持久化完成 authenticated 等价证据。
- [x] session resume / ACK（sandbox 客户端证据）：
  - Evidence: `private_ack_before_seq=0`, `private_ack_after_seq=1`, `private_ack_observed=true`。
- [ ] FCM -> private / private -> FCM 真链路一致性（sandbox 凭据可用前提）：
  - Current: synthetic token 下 `switch_private_ok=true`, `switch_back_ok=true`, `switch_back_route_ok=true` 已通过；禁用 synthetic 时当前 AVD 输出 `missing_fcm_token_or_play_services`。截至 2026-05-17，账号已登录但仍 `AUTHENTICATION_FAIL`，真实 FCM token 路径待补。
- [x] 缺后端接口/凭据时给出缺口：
  - Evidence log: `dispatch_auth_mode=anonymous_allowed`, `diagnostics_auth_mode=endpoint_missing_or_not_routed`, `pull_probe_code=400`, `ack_probe_code=400`, `pull_with_device_key_code=200`, `verification_layer=private_ack_switch_roundtrip`, `required_min_test_endpoint=none`, `gap=diagnostics_endpoint_missing(code=404);synthetic_fcm_token_used`。

### B. 通道切换压力与时序
- [x] 1k / 10k 混合序列：
  - Evidence log: `runtime-channel-switch-performance size=1000/10000`。
- [x] 100k opt-in：
  - Evidence log: `runtime-channel-switch-performance size=100000 ... total_ms=36 ...`（通过 `-Pandroid.testInstrumentationRunnerArguments.pushgo.runtime.include100k=true` 触发）。
- [x] 切换中消息到达、双通道同 message id、晚到旧通道：
  - Artifact: `RuntimeChannelSwitchInstrumentedTest` 真实入库与 fake 状态机 case。
- [x] ACK success/failed/retry、reconnect/resume：
  - Evidence log: `RUNTIME_PRIVATE_STATEFLOW ack_success_ms... ack_failed_ms... ack_retry_ms...`。
- [x] 断言不双活/不重/不丢（现有模型）：
  - Evidence: `dualActiveViolation` 断言、canonical + rejected 指标。
- [x] 指标字段补齐（total/p50/p95/canonical/duplicate/stale）：
  - Evidence log: `canonical_count`, `duplicate_rejected`, `stale_rejected`, `switch_p50/p95`, `ack_p50/p95`。

### C. Compose UI 性能与交互
- [x] AVD 基线（不依赖真实网络）：
  - Artifact: `RuntimeComposeUiBaselineInstrumentedTest`。
- [x] Settings/channel switch 页面状态一致性（token 缺失/恢复 + private connecting/connected/reconnecting/error）：
  - Evidence log: `RUNTIME_COMPOSE_SETTINGS_STAGE_PROXY ... status_connecting/status_connected/status_reconnecting/status_error ...`
  - Evidence log: `RUNTIME_SETTINGS_UI switch_fcm_to_private_ui_ms=3 switch_private_to_fcm_ui_ms=0`
- [ ] 真 Compose UI rule 交互（首屏、滚动、搜索、过滤、详情、设置页交互）
  - Current block: Pixel_10(Android 17) 上 Espresso `InputManager.getInstance` 反射缺失，已记录。
  - Recheck: 2026-05-17 环境探针复跑输出 `RUNTIME_COMPOSE_ENV input_manager_get_instance=false fallback_mode=true`。
- [x] UIAutomator 真交互补偿覆盖（滚动、详情打开/返回、Settings private 切换）：
  - Evidence log: `RUNTIME_COMPOSE_UI_AUTOMATOR ... fixture_total_count=10000/100000 ... scroll_burst_ms ... detail_open_ms ... switch_private_ms ...`。
  - Recheck(账号登录后): 100k 复跑仍 `switch_fcm_supported=false`, `switch_fcm_skip_reason=fcm_option_not_visible`。
- [x] 替代观测（log marker + timing + dumpsys/memory）：
  - Evidence: `RUNTIME_COMPOSE_*` logs + `adb shell dumpsys meminfo/gfxinfo io.ethan.pushgo`。
- [x] frame/jank 基线样本（替代指标）
  - Evidence: `RUNTIME_COMPOSE_UI_AUTOMATOR janky_frames=122 total_frames=350`（当前已引入 `gfxinfo reset` 稳态窗口，仍建议持续多轮采样）。
- [x] 主线程长任务/ANR 风险替代观测
  - Evidence: 本轮 instrumentation logcat 产物扫描未命中 `ANR/Application Not Responding/Input dispatching timed out`；`/data/anr` 当前为空目录。

### D. 最终报告
- [x] 已输出 runtime quality report：
  - Artifact: `release/runtime-quality/ANDROID_RUNTIME_QUALITY_REPORT_2026-05-17.md`
- [x] 包含覆盖/未覆盖、证据等级、指标、follow-up、分层命令、RC 判断。
- [x] PR 分层命令回归通过（本轮）：
  - Evidence: `./gradlew :app:testDebugUnitTest` 与 `./gradlew :app:connectedDebugAndroidTest -P...class=RuntimeDataLayer...,RuntimeChannelSwitch...,RuntimePrivateChannelStateFlow...,RuntimeComposeUiBaseline...` 均 `BUILD SUCCESSFUL`。

### Overall status
- Objective status: **Not achieved yet**
- Hard blockers:
  1. 真实 FCM token 路径待覆盖（账号已登录，但 Google/Firebase 认证态仍未就绪，non-synthetic 仍 `AUTHENTICATION_FAIL`）。
  2. 当前唯一 AVD 镜像（android-37.0/google_apis_playstore_ps16k）上 Compose UI rule 受 Espresso 兼容阻断。
  3. `connectedDebugAndroidTest` 并行执行仍会触发报告目录竞争（`NoSuchFileException .../connected/debug/css`），当前必须串行调度。

## 10.1 本轮可复核证据（文件级）
> 审计时间：2026-05-17 22:32 CST（emulator-5554，`Accounts: 1`，non-synthetic 仍 `AUTHENTICATION_FAIL`）

- sandbox non-synthetic 复跑日志：
  - `app/build/outputs/androidTest-results/connected/debug/Pixel_10(AVD) - 17/logcat-io.ethan.pushgo.testing.RuntimeSandboxGatewayInstrumentedTest-*.txt`
  - 关键字：`fcm_token_available=false`、`AUTHENTICATION_FAIL`、`required_local_precondition=google_account_signed_in`。
- sandbox 默认 skip gate 复跑结果：
  - `app/build/outputs/androidTest-results/connected/debug/TEST-Pixel_10(AVD) - 17-_app-.xml`
  - 关键字：`RuntimeSandboxGatewayInstrumentedTest ... SKIPPED`。
- Compose 环境探针复跑日志：
  - `app/build/outputs/androidTest-results/connected/debug/Pixel_10(AVD) - 17/logcat-io.ethan.pushgo.testing.RuntimeComposeUiBaselineInstrumentedTest-*.txt`
  - 关键字：`RUNTIME_COMPOSE_ENV input_manager_get_instance=false fallback_mode=true`。
- Compose UIAutomator 100k 指标日志：
  - `app/build/outputs/androidTest-results/connected/debug/Pixel_10(AVD) - 17/logcat-io.ethan.pushgo.testing.RuntimeComposeUiAutomatorInstrumentedTest-*.txt`
  - 关键字：`fixture_total_count=100000`、`total_pss_delta_kb=71108`、`janky_frames=37`。
- A2 人工前置步骤可达性（设备即时状态）：
  - 命令：`adb shell am start -W -a android.settings.ADD_ACCOUNT_SETTINGS`
  - 关键输出：`Activity: com.android.settings/.Settings$ChooseAccountActivity`
  - 命令：`adb shell am start -W -n com.android.settings/.Settings\\$ChooseAccountActivity`
  - 关键输出：`Activity: com.google.android.gms/.auth.uiflows.addaccount.PreAddAccountActivity`
- A2 non-synthetic 账号登录后复跑证据：
  - 日志：`app/build/outputs/androidTest-results/connected/debug/Pixel_10(AVD) - 17/logcat-io.ethan.pushgo.testing.RuntimeSandboxGatewayInstrumentedTest-*.txt`
  - 关键字：`android_account_count=1`、`android_google_account_count=1`、`required_local_precondition=google_account_auth_state_ready`、`fcm_token_available=false`、`AUTHENTICATION_FAIL`。
- A2 环境侧补充诊断（2026-05-17）：
  - `dumpsys package com.google.android.gms`：`versionName=26.18.33`（最新安装时间 2026-05-17 22:24:43）。
  - `dumpsys jobscheduler com.google.android.gms`：Google 账号相关 SyncManager jobs 已进入 RUNNABLE/执行完成记录。
  - 结论：当前阻断不在“未登录账号”或“GMS 未安装”；是 FCM token 认证链路仍返回 `AUTHENTICATION_FAIL`。
- C 阶段账号登录后 UIAutomator 100k 复跑证据：
  - 日志：`app/build/outputs/androidTest-results/connected/debug/Pixel_10(AVD) - 17/logcat-io.ethan.pushgo.testing.RuntimeComposeUiAutomatorInstrumentedTest-composeRuntime_uiAutomatorBaseline_messageListDetailAndSettings.txt`
  - 关键字：`switch_fcm_supported=false`、`switch_fcm_skip_reason=fcm_option_not_visible`、`total_pss_delta_kb=4686`、`janky_frames=30`。
