# Android 能力覆盖索引

此索引防遗漏，不计算覆盖分，不是测试 Oracle。`testTag`、Manifest 组件、Worker 或测试方法存在均不能单独证明用户能力。

| 真实入口 | 用户目的 | 关键状态/分支 | 数据/系统终点 | 最低证据 | Lane/当前状态 | 主要 owner |
| --- | --- | --- | --- | --- | --- | --- |
| App launch | 进入可操作 App | Empty/Content/slow/fatal/migration | App-owned Room、session secure/settings stores、首屏、导航 | Room + UI + Macrobenchmark | P0；empty/content/slow/error 已有真实 App UI；quality Room、Keystore 密文偏好和 settings cache 均按 session 隔离；migration UI/Macrobenchmark 待补 | `MainActivity`、`AppContainer`、DB |
| Messages list | 浏览/分页/刷新消息 | first/page/refresh/slow/error | summary query、列表集合 | DAO + VM + UI | P0；首次 slow/error/retry、准确集合、跨 50 条页界、慢刷新/旧快照、新结果持久化与失败后恢复已有；真实性能待补 | Message repository/VM/UI |
| Message detail | 阅读准确内容 | missing/decrypt/media/read/delete | Room、read state、notification | unit + UI + relaunch | P0；准确字段/详情、唯一详情 owner 下的正文、单条/全部已读、未读筛选、activity relaunch 与删除撤销已有；合法 Key 的原消息恢复、准确明文和 relaunch 已有；media 及错 Key/坏密文待补 | Detail VM/UI/Repository |
| Search/filter | 找到且只找到目标集合 | latest query/unread/index rebuild/error | search index、结果集合 | property + Room + UI | P0；错误词排除、目标集合/准确详情与未读筛选往返 UI 已有；channel/tag 与 index error/rebuild 待补 | DAO/Search UI |
| History cleanup | 按范围清理 | cutoff/DST/cancel/failure | messages/entities/index/stats | DAO boundary + UI | P1；UI 缺口 | Cleanup/Repositories |
| Delete/Undo | 删除或恢复并持久化 | pending/claim/failure/death/reopen | Room、Worker、notification | integration + UI | P0；真实详情删除、行隐藏、Undo、activity relaunch 已有；process death/notification 待物理 lane | Pending deletion |
| Events | 浏览、筛选、关闭事件 | ongoing/closed/slow/error/duplicate | event head/timeline/Thing relation | Room + contract + UI | P0；App-owned 摄入→投影→列表→准确详情→确认关闭→投影更新→仅进行中筛选排除→activity relaunch 后 closed 持久化已实现；slow/error/duplicate close 待补 | Event UI/Repository |
| Things | 浏览对象和三个页签 | active/filter/missing/deep link | head、Events/Messages/Updates | Room + router + UI | P0；App-owned 摄入→投影→准确概览、Events/Messages/Updates 三页签、准确关联详情、返回原 Thing/页签与 relaunch 已实现；筛选/深链/删除待补 | Thing UI/Repository |
| Channels | 创建、订阅、改名、两类退订 | invalid/auth/failure/keep/delete/undo | remote、credentials、history | contract + Room + UI | P0；创建表单本地 invalid 在 token/远端副作用前拒绝，错误只在当前 Sheet；创建→改名→activity relaunch、保留历史退订与删除历史提交→relaunch 已实现；远端拒绝/补偿 UI 与订阅既有频道待补 | Channel repository/UI |
| Gateway settings | 修改真实服务器 | invalid/cancel/register-failure/commit-failure | Room address、Keystore token、candidate device/route、gateway-scoped data | unit + contract + UI + relaunch | P0；真实入口覆盖 invalid、候选注册一次失败时 Sheet 内反馈/宿主旧值/关闭重开仍旧值、同一用户动作重试后才提交、标准化保存、频道数据立即换域及 activity relaunch；候选注册不复用旧 Gateway device key。真实 FCM/private 与本地 commit/rollback 写失败待补 | Settings VM/UI |
| Decryption settings | 配置 Key 并恢复消息 | encoding/invalid/missing/wrong/clear | session-isolated Keystore 密文、Room timestamp、原 ciphertext、同一 canonical message、plaintext | unit + protected Store + Room + UI + relaunch | P0；真实入口已覆盖 invalid、成功状态、不回显、清除及两种 activity relaunch；`messages.encrypted.valid` 已证明 missing-key→详情真实入口→合法 Key→同一消息准确明文→activity relaunch，并保留身份/已读/时间/原密文；错 Key/坏密文 UI 和存储故障注入待补 | Settings/Decryptor |
| Page visibility | 控制主导航入口 | hide/show/relaunch | preferences、Tabs | VM + UI + relaunch | P0；真实 Settings 控件关闭/恢复 Event 入口并分别 activity relaunch 核对已实现 | Settings/Main UI |
| Transport selector | 在 FCM/Private 间真实切换 | unavailable/failure/restart/late old | service、token、connection、Room | integration + UI | P0；integration 强、UI 缺口 | Settings VM/Service manager |
| Notification permission/Doze | 恢复可靠通知条件 | denied/allowed/return/snooze expiry | OS settings、UI card | unit + physical UI | P0/P1；NOT RUN | Settings/system adapters |
| Ingress/ACK | 收到且最终显示一次 | duplicate/order/persist fail/retry/death | ledger、Room、notification、UI | property + device + real FCM | P0；低层强、real FCM NOT RUN | Messaging/ACK workers |
| Private foreground service | 维持独立接收 | start/stop/dismiss/reboot/package replace | service、notification、connection | integration + physical | P1；device 缺口 | Private service/receivers |
| Alert playback | 正确播放并停止严重通知 | priority/preempt/timed/dismiss/read/delete | media service、notification | state tests + physical | P1；缺口 | AlertPlaybackService |
| Background workers | 跨进程恢复 durable work | constraints/retry/death/duplicate | ACK/ingress/post/deletion/service | Worker unit + device | P1；部分已有 | Worker/schedulers |
| Image preview/share/cache | 查看、缩放、分享并释放资源 | load/error/cancel/permission/cleanup | file consumer、cache、Worker | component + UI + device | P1；component 部分有 | Media UI/Image cleanup |
| Update UI/install | 检查并安全安装更新 | stable/beta/later/skip/permission/death | feed、download、signature、installer | JVM + UI + physical | P0/P1 Release；部分脚本、UI 缺口 | Update subsystem |
| Update distribution metadata | 用户获得签名、版本与文案一致的更新 | version/build/channel/signature/notes/URL | signed feed、版本化 notes | semantic contract + Release install | P0 Release；Feed 契约已进 PR，物理安装仍 NOT RUN | release feed/workflow |
| Accessibility/localization | 用 TalkBack/大字体完成核心任务 | focus/actions/font/zh/en | semantics、OS accessibility | component + physical task | P1；component 有、physical NOT RUN | Shared UI/screens |
| Performance | 在预算内得到正确结果 | cold/warm/10k/search/scroll/detail | TTID/TTFD/frame/trace + content | correctness + Macrobenchmark | P1；slow 状态 Oracle 已可证伪，物理设备 Macrobenchmark 模块待建 | App/benchmark module |
| Export candidate | 导出消息文件 | reachable/cancel/failure/large | JSON/URI consumer | product reachability review | 删除候选；不投入本轮预算 | Export helpers |

## 增量规则

新增或改变 Screen、Route、Action、Room 字段/索引、Service、Worker、Receiver、权限或性能敏感路径时更新相应行。`config/quality-impact.json` 只决定最低检查；未映射产品路径阻断，命中后 AI 仍必须继续追 caller、状态、数据和平台消费者。
