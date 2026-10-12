# Android Hermetic 启动可靠性

`scripts/run_android_startup_reliability.sh` 是 opt-in 的 50 次可靠性入口，不进入每次 PR。它只使用 `quality_doctor` 选中的受控 emulator，构建/安装当前 App 与 test APK 一次，然后每轮重新启动独立 Instrumentation 和新的 App-owned `empty.clean` 会话；最终 Oracle 是用户可见的 Messages 功能空态以及隔离 Room 数据库，不是进程、APK、readiness 或文件存在。

默认 `ITERATIONS=50`，不做失败重试。每轮日志和耗时进入唯一 campaign 目录，`summary.json` 输出成功率、product/test-system 双状态、issue ID 与 scope notice。50 次至少 49 次成功才达到设计的 98% 启动门槛；只有 50/50、无 issue 才是连续稳定证据。准备失败为 `NOT_RUN/BLOCKED`，真实 UI/数据终点错误为 product `FAILED`，已登记 Compose runtime 签名保持 test-system `FLAKY/FAILED`，都不能被其他绿色轮次隐藏。

```bash
scripts/run_android_startup_reliability.sh
```

该 focused 启动旅程不能单独关闭 `android-compose-snapshot-observer-runtime`。后者的 scope 是多旅程 Compose device-class 聚合 drawing；未执行同 scope 的 50 次证据前必须继续保留并按到期日处理。非法 `ITERATIONS=0/101`、Instrumentation 安装合同不匹配和设备不受控均在产品 iteration 前 `BLOCKED`。

Raw `am instrument` 失败由 `classify_android_instrument_log.py` 单独归因：只含精确 SnapshotStateObserver 签名才是 test-system flake，只含精确 `QUALITY_PRECONDITION` 才是 blocked；同一日志只要存在其他 `AssertionError/AssertionFailedError/ComparisonFailure`，产品断言优先，必须 `PRODUCT_FAILED` 且 issue ID 为空。

2026-08-28 当前受控 `emulator-5554` 正式执行 50/50，product/test-system=`PASSED/PASSED`、issue ID 为空；观测耗时 p50=1901.5ms、p95=9073ms、max=15226ms，仅用于发现 Emulator/Instrumentation 异常，不作为物理设备产品 SLO。证据在 `build/quality-results/android-startup-reliability/20260828-205052/summary.json`。

2026-08-31 在 `MainActivity`、composition root、Room 与 `QualityRuntime` 当前字节稳定后，使用同一 `Medium_Phone / emulator-5554`、同一功能 Oracle、零重试重新执行 50/50；product/test-system=`PASSED/PASSED`、issue ID 为空，p50=1351ms、p95=1516ms、max=1597ms。新鲜证据为 `build/quality-results/android-startup-reliability-current-byte/20260831-224148/summary.json`。它取代旧轮作为当前字节启动证据，但仍不关闭多旅程 Compose aggregate flake，也不外推物理设备性能。
