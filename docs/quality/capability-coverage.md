# Android 能力覆盖索引

此索引防遗漏，不计算覆盖分，不是测试 Oracle。`testTag`、Manifest 组件、Worker 或测试方法存在均不能单独证明用户能力。

| 真实入口 | 用户目的 | 关键状态/分支 | 数据/系统终点 | 最低证据 | Lane/当前状态 | 主要 owner |
| --- | --- | --- | --- | --- | --- | --- |
| App launch | 进入可操作 App | Empty/Content/slow/fatal/migration | App-owned Room、首屏、导航 | Room + UI + Macrobenchmark | P0；empty/content/slow/error 已有真实 App UI，migration UI/Macrobenchmark 待补 | `MainActivity`、`AppContainer`、DB |
| Messages list | 浏览/分页/刷新消息 | first/page/refresh/slow/error | summary query、列表集合 | DAO + VM + UI | P0；首次 slow/error/retry、准确集合、跨 50 条页界、慢刷新与旧快照保留已有；refresh 新结果更新/失败恢复待补 | Message repository/VM/UI |
| Message detail | 阅读准确内容 | missing/decrypt/media/read/delete | Room、read state、notification | unit + UI + relaunch | P0；准确字段/详情、单条/全部已读、未读筛选、activity relaunch 与删除撤销已有；media 待补 | Detail VM/UI/Repository |
| Search/filter | 找到且只找到目标集合 | latest query/unread/index rebuild/error | search index、结果集合 | property + Room + UI | P0；错误词排除、目标集合/准确详情与未读筛选往返 UI 已有；channel/tag 与 index error/rebuild 待补 | DAO/Search UI |
| History cleanup | 按范围清理 | cutoff/DST/cancel/failure | messages/entities/index/stats | DAO boundary + UI | P1；UI 缺口 | Cleanup/Repositories |
| Delete/Undo | 删除或恢复并持久化 | pending/claim/failure/death/reopen | Room、Worker、notification | integration + UI | P0；真实详情删除、行隐藏、Undo、activity relaunch 已有；process death/notification 待物理 lane | Pending deletion |
| Events | 浏览、筛选、关闭事件 | ongoing/closed/slow/error/duplicate | event head/timeline/Thing relation | Room + contract + UI | P0；App-owned 摄入→投影→列表→准确详情已实现，筛选/关闭/错误恢复待补 | Event UI/Repository |
| Things | 浏览对象和三个页签 | active/filter/missing/deep link | head、Events/Messages/Updates | Room + router + UI | P0；App-owned 摄入→投影→准确概览与 Events/Messages/Updates 三页签已实现，筛选/关联打开/深链待补 | Thing UI/Repository |
| Channels | 创建、订阅、改名、两类退订 | invalid/auth/failure/keep/delete/undo | remote、credentials、history | contract + Room + UI | P0；低层部分已有、UI 缺口 | Channel repository/UI |
| Gateway settings | 修改真实服务器 | invalid/cancel/failure | preferences/token、后续 endpoint | unit + contract + UI | P0；UI 缺口 | Settings VM/UI |
| Decryption settings | 配置 Key 并恢复消息 | encoding/invalid/missing/wrong | secure storage、plaintext | unit + UI + relaunch | P0；component 有、UI 缺口 | Settings/Decryptor |
| Page visibility | 控制主导航入口 | hide/show/relaunch | preferences、Tabs | VM + UI + relaunch | P0；UI 缺口 | Settings/Main UI |
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
