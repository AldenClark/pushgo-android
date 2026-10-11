# Android 验收执行合同

跨平台必保结果和失败预算由 Apple 仓库 `docs/quality/acceptance-execution.md` 统一定义；本仓库既有 [能力索引](capability-coverage.md) 与 release-smoke 16 个核心 selectors + 独立权限 journey 均保留，完整流程的 Nightly/Release 所有者不删除。

`quality_ci_plan.py` 每轮同时保存累计 `*-obligations.json`、本轮 execution plan 和 `*-selection.json`。仅 draft synchronize 的已验证 previous-tip 祖先可用于本次诊断；force-push/缺历史保守回退，ready_for_review 与非草稿恢复整个 PR 累计范围。草稿结果不关闭合并或发布义务，规划不算执行，无自动证据缓存。

`process-restart` 只选真实 new-PID、准确内容/已读重开、消息和文档精确 HTTPS 往返。共享 Runtime/Store/composition 修改仍命中较宽规则；与其他功能/性能范围混合时提升到能覆盖双方的车道。原 Nightly/Release 仍包含此流程。权限、Doze、进程重启、待删除恢复、通知/Private Service 分别记录结果；host 阶段 JSONL 保留已完成边界，尾部失败仍使完整流程失败。

保留首次失败、零重试、准确原生方法/数量/零 skip、未知 warning 拒绝。两轮仅修 runner/fixture/report 而无新增业务证据时停止，归因 BLOCKED，不机械重复全量。禁止削弱产品断言或靠加大全局 timeout 求绿。>=720p 代表屏幕；Windows source-only、Watch Simulator、Token Service local-only、无正式发布沿用原约定。
