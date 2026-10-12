# Android 性能与 Baseline Profile 执行手册

## 1. 这套能力保护什么

性能测试必须同时满足“用户任务正确”和“耗时/帧预算满足”。当前保护两条 P1 旅程：

1. 冷启动后，真实 Room 中 1,000 条消息的精确 sentinel `Quality message 999` 已显示；`ReportDrawnWhen` 只在 Paging 初次加载进入终态且内容可见时报告 TTFD。
2. 从 sentinel 列表行打开 Message Detail Sheet，并显示同一持久化记录的精确正文；真机结果还要求每一轮都产生帧，且 `frameDurationCpuMs.P95` 不超过显式预算。

仅存在 APK、Profile 文件、测试类或版本号不算产品 Oracle。模拟器数值不算用户设备性能结论。

## 2. 环境与结论边界

| 环境 | 执行内容 | 可支持的结论 | 不可支持的结论 |
| --- | --- | --- | --- |
| PR/JVM | 选择器、JSON 判定器、runner 负控 | 测试契约与退出码 | App UI/设备性能 |
| 受控 emulator | 真实 `.benchmark` APK、App-owned 1k Room、启动/详情 dry-run | 打包、准备、定位、真实数据和用户任务 Oracle 可执行 | 真机时延、帧预算 |
| 受控 profile emulator | 真实 production applicationId、两条 CUJ | Baseline/Startup Profile 可生成且仅包含 PushGo 规则 | 真机性能 |
| 显式非个人真机 | Release-like `.benchmark` APK、10 次启动和 10 次详情交互 | 在该硬件/系统/预算下的启动、任务和帧证据 | 其他机型、系统或未运行场景 |

本地已观察到：API 37 emulator 可执行产品 Oracle，但标准 `FrameTimingMetric` 的 Perfetto `expect/actual` 切片解析被工具链阻塞；API 28 旧 AVD 的 Room/R8 fixture 初始化不稳定。因此两者都不能替代真机帧结论，状态必须分别保留为 `BLOCKED`，而不是通过放宽断言变绿。

## 3. App-owned 准备协议

Macrobenchmark 不直接读取测试进程外的数据库，也不复制共享 DB 文件：

1. `BenchmarkUnstopActivity` 仅在 benchmark/profile 变体存在，用于解除 Android stopped-state，不进入产品 UI。
2. 性能 `.benchmark` applicationId 在一次性准备前执行精确包级 `pm clear`；不会清生产 App。
3. Baseline Profile 使用 production applicationId，但代码和脚本双重要求 `ro.kernel.qemu=1`，禁止在物理设备清生产数据。
4. `BenchmarkFixtureProvider` 在 App 沙箱内配置唯一 session、建立真实 `AppContainer`/Room、显式初始化 fixture，并核验总数、标题、正文。
5. 每个测试方法只准备一次只读数据；每轮仅重置进程和页面起点，结束后清理一次。
6. Release 中 Provider 与 NoDisplay 控制 Activity 都是 `enabled=false/exported=false`，其实现也不得进入 Release dex。

任何准备、删除、初始化、权限读取或清理失败都必须失败/阻塞，不能被重试或空数据页面掩盖。

## 4. 日常 Lane

```bash
./scripts/quality_test.sh performance
```

该 Lane 依次执行：

- 真实 Room 100k 写入、分页、FTS、筛选、投影、关闭重开；
- 受控 emulator 上 Release-like Macrobenchmark dry-run，校验精确 1k 启动/详情目的；
- 在上述正向 Macrobenchmark 通过后，以同一 App-owned `messages.large` 数据注入 3,500ms 首次加载延迟；只有准确 sentinel 标题最终可见且 2,000ms 预算断言如期失败，才证明性能门禁会对真实慢加载变红；
- Release APK 构建与 Profile/测试控制隔离契约；
- 若没有完整真机参数，结果明确记录真机性能 `NOT RUN`。

慢加载负控不把“预期超时”写成产品通过：独立收据固定为 product `NOT_RUN` / test-system `PASSED`，记录注入值、实测值、预算和准确内容终点。若 App 接受超预算，或失败并非准确的预算断言，Lane 以测试系统 `FAILED` 退出。它复用刚构建的 benchmark APK，不重复 R8；当前增量执行约 16 秒，因此只随 `performance`/`release` 运行，不进入日常 PR/device 正向集。

`release` Lane 是功能、设备、可访问性、性能与 Release 隔离的并集。Macrobenchmark/Profile 代码变化至少选择 `performance`；若同时影响消息功能、Room 或 UI，则选择器提升到 `release`。

## 5. 显式真机执行

必须由设备所有者提供非个人测试设备和三个版本化预算；脚本没有默认设备，也没有默认预算：

```bash
ANDROID_PERFORMANCE_DEVICE_SERIAL='<lab-device-serial>' \
PUSHGO_ANDROID_PHYSICAL_MAX_STARTUP_MS='<owner-budget>' \
PUSHGO_ANDROID_PHYSICAL_MAX_DETAIL_MS='<owner-budget>' \
PUSHGO_ANDROID_PHYSICAL_MAX_FRAME_MS='<owner-budget>' \
./scripts/run_android_physical_macrobenchmark.sh
```

执行前会验证：序列号精确匹配、不是 emulator/qemu、API ≥ 28、电量 ≥ 30%、热状态低于 moderate。测试框架或 trace 无法产出可信指标返回 `BLOCKED`；真实内容、交互或预算失败返回 `FAILED`。产物只写入被 `.gitignore` 排除的 `build/quality-results/android-physical-macrobenchmark/<run-id>/`。

JSON 后置判定要求：唯一详情 benchmark、`repeatIterations` 有效、每轮 `frameDurationCpuMs.runs` 非空、每轮 `frameCount > 0`、P95 在预算内。旧版“后续轮次停在详情页但仍绿色”的 JSON 是该判定器的负控。

## 6. 重新生成 Baseline Profile

这会清受控 emulator 中 production applicationId 的数据，所以必须显式指定 qemu：

```bash
ANDROID_BASELINE_PROFILE_DEVICE_SERIAL='<controlled-emulator-serial>' \
./scripts/regenerate_android_baseline_profile.sh
```

脚本会删除旧生成文件、clean 生成两条真实 CUJ Profile、构建 Release APK，再验证：

- 所有规则都属于 `io.ethan.pushgo`，并排除 `testing`/`automation` 测试控制路径；
- 忽略 ART 的 H/S/P 标志后，Startup 规则身份是 Baseline 的真子集；
- 启动、Room、列表、详情关键路径存在；
- APK 内 `baseline.prof`/`baseline.profm` 存在且 `baseline.prof < 1.5 MB`；
- Release 控制组件不可达且实现未入 dex。

生成文件是构建输入，应与生成器/关键 CUJ 的变化一起提交；手工编辑不能替代重新生成。

## 7. 归因顺序

失败时保留第一次证据并按以下最早边界归因：

1. 设备选择/电量/热状态/权限：`BLOCKED`；
2. stopped-state、Provider、session、Room 初始化或清理：测试系统 `BLOCKED/FAILED`，不得算产品失败；
3. 1k 总数、sentinel 标题/正文不符：数据/产品 `FAILED`；
4. 行、Sheet、正文不可达：UI/交互 `FAILED`；
5. trace 无帧或解析失败：测试系统 `BLOCKED`；
6. 指标有效但超过预算：性能 `FAILED`。

慢加载敏感性负控是例外的“预期失败实验”：只有先到达准确内容终点、再触发精确预算失败才记 test-system `PASSED`；App 未被压慢、错误内容、准备失败、错误断言或测试被跳过均不得冒充敏感性证据。该实验不产生产品性能通过结论。

修复后从最窄失败用例开始，再跑 `performance` Lane；改变 App、fixture、判定器、Profile 或 Release APK 后，旧 PASS 自动失效。

## 8. 官方依据

- [Write a Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
- [Capture Macrobenchmark metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)
- [Control your app from Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-control-app)
- [Generate Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile)
- [Benchmark release notes](https://developer.android.com/jetpack/androidx/releases/benchmark)

当前使用 Benchmark/Baseline Profile `1.5.0-rc02`，原因是仓库采用 AGP 9.2.1，而稳定版 1.4.1 不支持 AGP 9 新 DSL。升级或回退必须重新生成 Profile、构建 Release、运行隔离契约和代表性设备流程。
