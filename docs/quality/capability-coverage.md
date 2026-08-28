# Android 能力覆盖索引

此索引防遗漏，不计算覆盖分，不是测试 Oracle。`testTag`、Manifest 组件、Worker 或测试方法存在均不能单独证明用户能力。

| 真实入口 | 用户目的 | 关键状态/分支 | 数据/系统终点 | 最低证据 | Lane/当前状态 | 主要 owner |
| --- | --- | --- | --- | --- | --- | --- |
| App launch | 进入可操作 App | Empty/Content/slow/fatal/migration | App-owned Room、session secure/settings stores、首屏、导航 | Room + UI + Macrobenchmark | P0；empty/content/slow/error 已有真实 App UI；quality Room、Keystore 密文偏好和 settings cache 均按 session 隔离；migration UI/Macrobenchmark 待补 | `MainActivity`、`AppContainer`、DB |
| Messages list | 浏览/分页/刷新消息 | first/page/refresh/slow/error | summary query、列表集合 | DAO + VM + UI | P0；首次 slow/error/retry、准确集合、跨 50 条页界、慢刷新/旧快照、新结果持久化与失败后恢复已有；真实 Room 100k provisional baseline 已有，UI/真机性能待补 | Message repository/VM/UI |
| Message detail | 阅读准确内容 | missing/decrypt/media/read/delete | Room、read state、notification | unit + UI + relaunch | P0；准确字段/详情、唯一详情 owner 下的正文、单条/全部已读、未读筛选、activity relaunch 与删除撤销已有；合法 Key、错误 Key 纠正和坏密文安全失败均以原 canonical 消息及 relaunch 闭环；media 待补 | Detail VM/UI/Repository |
| Search/filter | 找到且只找到目标集合 | latest query/unread/index rebuild/error | search index、结果集合 | property + Room + UI | P0；错误词排除、目标集合/准确详情与未读筛选往返 UI 已有；channel/tag 与 index error/rebuild 待补 | DAO/Search UI |
| History cleanup | 按范围清理 | cutoff/DST/cancel/failure | messages/entities/index/stats | DAO boundary + UI | P1；UI 缺口 | Cleanup/Repositories |
| Delete/Undo | 删除或恢复并持久化 | pending/claim/failure/death/reopen | Room、Worker、notification | integration + UI | P0；真实详情删除、行隐藏、Undo、activity relaunch 已有；process death/notification 待物理 lane | Pending deletion |
| Events | 浏览、筛选、关闭事件 | ongoing/closed/slow/error/duplicate | event head/timeline/Thing relation | Room + contract + UI | P0；App-owned 摄入→投影→列表→准确详情→确认关闭→投影更新→仅进行中筛选排除→activity relaunch 后 closed 持久化已实现；slow/error/duplicate close 待补 | Event UI/Repository |
| Things | 浏览对象和三个页签 | active/filter/missing/deep link | head、Events/Messages/Updates | Room + router + UI | P0；App-owned 摄入→投影→准确概览、Events/Messages/Updates 三页签、准确关联详情、返回原 Thing/页签与 relaunch 已实现；筛选/深链/删除待补 | Thing UI/Repository |
| Channels | 创建、订阅、改名、两类退订 | invalid/auth/failure/keep/delete/undo/password privacy | remote、credentials、history | contract + Room + UI | P0；创建表单本地 invalid 在 token/远端副作用前拒绝，错误只在当前 Sheet；创建/订阅密码均为真实 Password semantics；创建→改名→activity relaunch、两类退订→relaunch 已实现；新增真实错误码的远端密码拒绝→Sheet 保留输入→重试，以及服务明确 `created=true` 后安全凭据已写/Room 未写中点失败→凭据回滚+远端补偿→重载无脏行→重试→relaunch。`created=false` 与订阅既有频道仍需 ownership/idempotency 协议，禁止盲目退订 | Channel repository/UI |
| Gateway settings | 修改真实服务器 | invalid/cancel/register-failure/commit-failure | Room address、Keystore token、candidate device/route、gateway-scoped data | unit + contract + UI + relaunch | P0；真实入口覆盖 invalid、候选注册拒绝不提交、重试、标准化、数据换域及 relaunch；新增候选远端成功但 Room address 写后本地 commit 中点失败，必须回滚、activity 重启仍旧值且重试才提交。候选不复用旧 device key；rollback 再失败会聚合上报，真实 FCM/private 未跑 | Settings VM/UI |
| Decryption settings | 配置 Key 并恢复消息 | encoding/invalid/missing/wrong/corrupt/store-failure/clear | session-isolated protected value、Room timestamp、原 ciphertext、同一 canonical message、plaintext | unit + protected Store + Room + UI + relaunch | P0；真实入口覆盖 lifecycle、错误 Key 纠正、合法恢复、坏密文安全失败；受保护 secret 写后、Room metadata 前一次性失败会补偿，activity 重启仍未配置且同入口重试才配置。生产 Keystore encrypt/SharedPreferences commit 失败不再静默 | Settings/Decryptor |
| Page visibility | 控制主导航入口 | hide/show/relaunch | preferences、Tabs | VM + UI + relaunch | P0；真实 Settings 控件关闭/恢复 Event 入口并分别 activity relaunch 核对已实现 | Settings/Main UI |
| Transport selector | 在 FCM/Private 间真实切换 | unavailable/双向 prepare failure/local commit failure/retry/restart/late old | service、token、connection、Room/secure token | integration + UI + real transport | P0；真实 segmented control 已覆盖双向拒绝保持旧 route、Private 本地提交中点失败后的远端补偿与本地回滚、错误 owner、同入口重试后提交及 activity relaunch；typed boundary 不替代真实 FCM/Private delivery，后者仍 NOT RUN | Settings VM/Service manager |
| Notification permission/Doze | 恢复可靠通知条件 | denied/allowed/return/snooze expiry | OS settings、UI card | unit + physical UI | P0/P1；NOT RUN | Settings/system adapters |
| Ingress/ACK | 收到且最终显示一次 | duplicate/order/persist fail/retry/death | ledger、Room、notification、UI | property + device + real FCM | P0；低层强、real FCM NOT RUN | Messaging/ACK workers |
| Private foreground service | 维持独立接收 | start/stop/dismiss/reboot/package replace | service、notification、connection | integration + physical | P1；device 缺口 | Private service/receivers |
| Alert playback | 正确播放并停止严重通知 | priority/preempt/timed/dismiss/read/delete | media service、notification | state tests + physical | P1；缺口 | AlertPlaybackService |
| Background workers | 跨进程恢复 durable work | constraints/retry/death/duplicate | ACK/ingress/post/deletion/service | Worker unit + device | P1；部分已有 | Worker/schedulers |
| Image preview/share/cache | 查看、缩放、分享并释放资源 | load/error/cancel/permission/cleanup | file consumer、cache、Worker | component + UI + device | P1；component 部分有 | Media UI/Image cleanup |
| Update UI/install | 检查并安全安装更新 | stable/beta/later/skip/permission/death | feed、download、signature、installer | JVM + UI + physical | P0/P1 Release；部分脚本、UI 缺口 | Update subsystem |
| Update distribution metadata | 用户获得签名、版本与文案一致的更新 | version/build/channel/signature/notes/URL | signed feed、版本化 notes | semantic contract + Release install | P0 Release；Feed 契约已进 PR，物理安装仍 NOT RUN | release feed/workflow |
| Accessibility/localization | 用中文/大字体/辅助技术完成核心任务 | focus/actions/font/zh/en | 生产资源、实际 Activity 配置、semantics、Room/UI 终点、OS accessibility | 全资源合同 + component + 代表性 device + physical task | P1；zh-CN fontScale 1.5 已完成准确消息详情和频道创建，zh-CN/zh-TW 全 key/placeholder 合同进入 PR；physical TalkBack 与其他风险代表设备/语言 NOT RUN | Shared UI/screens |
| Performance | 在预算内得到正确结果 | cold/warm/10k/100k/search/scroll/detail | Room correctness、TTID/TTFD/frame/trace + content | correctness + Room regression ceiling + Macrobenchmark | P1；独立 `performance` Lane 已在选定 API 37 emulator 实跑真实 Room 100k 写入、分页、FTS、筛选、投影与重开，search 采用 provisional 2s ceiling；synthetic JVM OOM 不冒充产品失败/通过，物理设备 Macrobenchmark 仍 NOT RUN | Room/App/benchmark module |
| Export candidate | 导出消息文件 | reachable/cancel/failure/large | JSON/URI consumer | product reachability review | 删除候选；不投入本轮预算 | Export helpers |

## 增量规则

新增或改变 Screen、Route、Action、Room 字段/索引、Service、Worker、Receiver、权限或性能敏感路径时更新相应行。`config/quality-impact.json` 只决定最低检查；未映射产品路径阻断，命中后 AI 仍必须继续追 caller、状态、数据和平台消费者。
