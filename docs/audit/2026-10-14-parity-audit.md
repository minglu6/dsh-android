# 桌面对齐全量审计（2026-10-14）

对照仓库：`app/`（Android）、`plugin/`（dsh-plugin-pocket）、`plugin/iroh/pocket-host`。  
基线：`origin/main` @ `146b04f`（含 `feat(sync): share completion reminders and harden transcript streaming`）。  
官方桌面：`@deepseek-ai/dsh@0.2.1-alpha.2`（npm `alpha` / CI 固定版本；`latest`/`next` 仍为 `0.2.0-rc.2`）。  
官方源码：Git 标签 `dsh-v0.2.1-alpha.2` → `d743267388`，检出到 `/tmp/dsh-harness`。  
同时从 npm 拉取并核对同版本包：`dsh-client-ui-chat`、`ui-workspace`、`ui-session`、`ui-jobs`、`ui-settings-general`、`ui-plugin-manager`。包内为编译产物；行为以 Git 源码中实际挂载的组件为准。

**本次只出报告，不改代码。**  
本环境无法同时操作官方桌面 GUI 与真机，结论来自源码对照与既有单测。用户要求的「以实际操作验收」必须在后续双端实机上补做；未验证项不得归因 Tailscale。

---

## 原则与判定

| 原则 | 用来判什么 |
| --- | --- |
| 官方一致性 | 功能、文案、图标、颜色、状态、交互以当前挂载的官方 UI 为准 |
| 精简界面 | 不另起模块、不造术语、不加桌面没有的说明/装饰/步骤 |
| 默认自动 | 布局自适应；Host 配置自动同步；正常态不解释同步、不放手动同步；异常才给恢复入口 |
| 紧凑统一 | 操作栏紧凑、图标/背景一致；无额外阴影、冗余标题、无条件常驻「本轮用量」行 |
| 交互完整 | 桌面能点/展开/复制/查看/fork 的，手机也能做 |
| 通用实现 | 模型、供应商、插件走 Host 通用机制，不在 APK 里逐个写死 |
| 不擅加限制 | 不加体验向的安全功能、只读限制或多余确认；诊断用「复制诊断报告」 |
| 已定细节 | 转圈只在会话标题前；工作区保持文件夹图标；完成未查看显示绿点并与桌面同步；不加会话路径、下移、聊天回到底部按钮；终端快捷键固定顶部，Tab 发远端 `\t`；点击定位光标、长按选择；新意见覆盖旧方案 |

工作量：S 局部改动、M 跨几个文件、L 协议/两端联调。

---

## 已对齐（本轮不再当差距）

对照 `146b04f` 与官方 `d743267`，下列已定细节或官方行为已经落地，后续验收即可，不用再当缺口：

| 项 | 手机现状 | 官方出处 |
| --- | --- | --- |
| 运行指示只在会话标题前，工作区用文件夹图标 | `SidebarActivityIndicator` 只画会话 leading slot；`WorkspaceNavigation.kt:94-98` 文件夹 | `ui-workspace` `Rows.tsx:525-635`：状态点在会话行，文件夹在工作区行 |
| 完成未查看绿点（手机侧） | `Screens.kt:559-564` + `CompletionReminders` | `StateDot` `done`（`ui-primitives/src/StateDot.tsx:8-9`） |
| 不加会话路径 | 会话行不再渲染 `s.cwd` | 官方会话行只显示标题 + 相对时间（`Rows.tsx:636-657`）；cwd 在工作区 hover |
| 不下移/上移 | 行菜单只有 pin / rename / fork / archive（`Screens.kt:594-614`） | 官方菜单同四项（`ui-workspace/src/client/index.ts:296-299`）；排序靠拖拽 |
| 不加聊天回到底部按钮 | `Chat.kt` 用 `followBottom` 自动跟随，无 FAB | 官方**有** `chat.toBottom`（`ui-chat/src/client/locale.ts:88`）。按已定细节删除，属明确例外 |
| 会话相对时间 | `Screens.kt:581-587` `formatSessionTime` | `Rows.tsx:649-657` |
| 无独立「凭证」页 | `ScreenshotTest` 断言「凭证」不存在；密钥写在模型提供商编辑器 | 官方也无独立凭证节；密钥在 `ui-settings-models` |
| 终端快捷键顶部、Tab=`\t`、点按光标、长按选择 | `TerminalView.kt:201-225`、`174-178` | 手机适配；桌面无触摸条，语义正确 |
| 诊断主操作 | `L.diagnosticsCopy()`「复制诊断报告」 | 手机连接专有，符合已定文案 |
| 插件管理 / 内置插件目录 | 抽屉「插件」+ 设置「内置插件」 | 桌面：侧栏 `ui-plugin-manager` + 设置 `ui-settings-plugins`（nav=`内置插件`） |
| 本轮用量不常驻成一行 | 仅详细模式消息动作 pill / 弹层（`ComposerStats.kt`） | `TurnUsagePanel` + `message.turnUsage.title` |

---

## 逐屏 / 逐模块差距

### 1. 设置

| # | 现状（文件:行） | 桌面实际行为（源码） | 原则 | 建议 | P | 量 |
| --- | --- | --- | --- | --- | --- | --- |
| S1 | 设置导航含「自动化任务」`Screens.kt:1361` | 自动化是侧栏 `sidebar.panellist`（`ui-schedule`），**不是** `settings.section`。设置节只有账户 / 通用 / 模型 / 内置插件 / Agent 预设 | 精简；官方一致性 | 设置里去掉该行；只保留抽屉入口（与桌面侧栏一致） | P1 | S |
| S2 | 自造分组「移动端」`Screens.kt:1364-1371`：连接、连接诊断、审批通知 | 官方无「移动端」。连接是 pocket 注入的 `settings.section` id=`pocket`（`plugin/src/client/index.ts:39-50`）。诊断在该节内部 | 精简；紧凑统一 | 去掉「移动端」标题。连接进与桌面对齐的「手机连接」节。诊断只留一处（连接页 overflow）。审批通知是 Android 专有，放连接页，不要单独分组 | P1 | S |
| S3 | 设置首页与通用页都有版本脚注 `Screens.kt:1372` 及通用页末 | 官方版本行是通用设置 slot `current-version`（`ui-settings-general` `index.ts:84-85`），文案 `general.currentVersion` | 官方一致性 | 只在通用设置保留一行，文案用官方 key | P2 | S |
| S4 | 通用设置缺「链接打开位置」 | `ui-chat` 注册 `settings.general.item` id=`link-opening`（`apply.ts:150-164`）；有内置浏览器才显示 | 官方一致性；交互完整 | 有浏览器面板时增加该行，写入同一 Host 设置 | P1 | M |
| S5 | 通用「代码自动换行」写本地 `Stores.kt:238-240` | 桌面是代码块工具条逐块切换，**没有**通用开关 | 精简；默认自动 | 删除通用行；保留块级 wrap，与桌面一致 | P1 | S |
| S6 | 「显示代码工作视图」描述含「本轮代码差异」`Screens.kt` 通用行 | **官方原文** `developerTools.description`（`ui-settings-general/locales.ts:33`） | — | 保持。不是自造文案 | — | — |
| S7 | 缺设置「账户」节 | `ui-settings-account` order `-10`，登录走本机浏览器 | 官方一致性 vs hide | 本机 OAuth / 打开浏览器 **hide**。若 Host 有余额/登录态 API，只读镜像；不要做第二套登录 | P2 | M |
| S8 | 缺通用「快捷键」行 | `ui-shortcuts` `settings.general.item` id=`shortcuts` | hide | 继续隐藏（桌面键盘） | — | — |
| S9 | 「打开配置文件」已去掉 | 官方 `openDocument` 开本机编辑器 | hide | 保持去掉 | — | — |
| S10 | Host 镜像 `adopt()` 按 namespace 保留更高 revision `HostSettings.kt:61-67` | 官方 `SettingsDescribeMirror` 在 `settings/document-updated` / `connection/reset` 上整表重载（`ui-settings/src/client/index.ts:36-52`） | 默认自动；双向反映 | `settings.changed` 带 `ns,revision` 时只替换该 ns，或事件 revision ≥ 本地才替换，禁止旧 describe 盖住新事件 | P1 | M |
| S11 | 模型保存成功仍提示「以提供商状态确认是否生效」`ModelsSettingsScreen.kt:139,169` | 官方保存后靠 read-back 更新行状态，不教用户去「确认是否生效」 | 精简；默认自动 | 成功无文案；只展示 Host 行错误 | P2 | S |

### 2. 插件

| # | 现状 | 桌面 | 原则 | 建议 | P | 量 |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | 「内置插件配置」只展示写死列表 `PluginsScreen.kt:54`、`340-346`：`subagent` / `web-search-deepseek` / `agent-loop` / `bash-sandbox` / `pwsh-sandbox` | 官方用 slot `plugins.item`，各包自己注册（`ui-settings-shell`、`ui-settings-web-search`、`ui-settings-subagent`、`ui-settings-agent-loop`） | 通用实现 | 按 Host `plugins.settingsDescribe` / slot 清单渲染。新官方或第三方配置页不得改 APK | P0 | M |
| P2 | 常驻工具条刷新：插件页 `PluginsScreen.kt:320`；库存页 `PluginInventory.kt:133-136` | 插件管理页**有**工具条刷新，同时听 `plugin-manager/changed`（`ui-plugin-manager/index.ts:83-95`、`PluginManagerPage.tsx:1499-1502`）。库存页无独立常驻刷新 | 官方一致性；默认自动 | 插件管理保留刷新（对齐桌面）。库存页只在 error 显示重试 | P1 | S |
| P3 | 配置编辑常态「重新读取」`PluginsScreen.kt:820-827`、`881-888` | 冲突才恢复；日常靠 Host 事件 | 默认自动 | 只在保存失败 / revision 冲突显示 | P1 | M |
| P4 | 「打开插件自定义界面」「与桌面共用」`PluginsScreen.kt:332-335` | 官方无这句副标题。Web 贡献页走各插件自己的入口 | 精简 | 有 `ui.open` 时用桌面同等短标签；删「与桌面共用」 | P2 | S |
| P5 | 设置「内置插件」整页只有只读库存 | 官方该节是 tab 壳（`ui-settings-plugins`），库存是 tab `all`（`ui-settings-plugin-inventory`）；其它插件还可挂 tab | 官方一致性 | 做成「内置插件」节 + tab；库存是其中一页，不要把管理页和库存页的职责混在设置里 | P1 | M |

桌面插件管理页的刷新按钮是官方挂载行为，**不要**按「禁止手动同步」删掉。该原则针对 Host 配置镜像，不是官方插件工具条。

### 3. 工作区 / 历史会话

| # | 现状 | 桌面 | 原则 | 建议 | P | 量 |
| --- | --- | --- | --- | --- | --- | --- |
| W1 | 绿点手机已画，且 Host `CompletionTracker` 持有共享未读 | 官方 `ui-session@0.2.1-alpha.2` 的 `completionUnread` 是**进程内 Set**（`ui-session/src/client/index.ts:285-489`），**没有** `setCompletionUnreadSource`。`/tmp/dsh-harness` 全库搜不到该符号 | 官方一致性；已定「与桌面同步」 | 见下文「绿点同步缺口」。未合并官方 hook 前，不能宣称两端已同步 | P0 | L |
| W2 | 侧栏活动只有 Running / Approval / Question `SidebarActivity.kt:24-39` | 官方还有 `plan-review`（`Rows.tsx:371-376`），优先级高于 running | 官方一致性 | 增加 plan-review；与聊天里已有的 plan-review 卡片共用 Host pending | P1 | M |
| W3 | 归档点按先弹恢复确认 `Screens.kt` 会话行 | 搜索结果可一键恢复；普通行点归档会话不打开（`toast.archivedNotOpenable`） | 不擅加限制 | 对齐：归档行不可打开，恢复走菜单/搜索按钮，不要多一步确认 | P2 | S |
| W4 | 行菜单 fork 文案「分叉会话」`Screens.kt:605` | 官方 `menu.fork` | 官方一致性 | 改用官方 locale，不要自造「分叉」 | P2 | S |

### 4. 聊天 / 消息 / Composer

| # | 现状 | 桌面 | 原则 | 建议 | P | 量 |
| --- | --- | --- | --- | --- | --- | --- |
| C1 | ⋯ 里有重命名 + 统计块 `Sheets.kt:221-234` | 重命名在侧栏行菜单；统计在 composer dock `StatsPills` | 精简 | ⋯ 只留官方 header 动作：下载日志、反馈、（可选）分支。统计走 composer | P1 | S |
| C2 | ⋯ 分支在 `chat.running` 时禁用 `Sheets.kt:246` | 官方 `sessions.fork` 默认对已完成前缀可用；运行中由 Host 拒绝 | 交互完整；不擅加限制 | 有 op 就启用，失败用 Host 错误 | P1 | S |
| C3 | 被关掉的提问留下「问题等待回答 · 重新打开」`Chat.kt:391-393` | 官方提问占 composer / 卡片，无第二条重开条 | 精简 | 删掉。关闭=回到官方同款卡片/占位 | P1 | S |
| C4 | 多处硬编码中文：TopBar「智能体团队」「更多」`Chat.kt:597-603`；Jobs/Goal/More 等 | 全部走各包 `locales.ts` | 官方一致性 | 统一 `L` / Host locale | P1 | M |
| C5 | 任务清单 `TodoPanel` 已按桌面 dock 对齐（`146b04f`） | `ui-chat` TodoPanel | — | 保持；实机对文案/折叠 | — | — |
| C6 | 复制成功后按钮 disable 约 1s `Chat.kt` MessageActions | 官方复制保持可点，只换图标 | 交互完整 | 只换 check 图标，不要 disable | P2 | S |

### 5. 任务面板（后台任务）

| # | 现状 | 桌面 | 原则 | 建议 | P | 量 |
| --- | --- | --- | --- | --- | --- | --- |
| J1 | `jobs.list` 在 abort/超时后返回 `{jobs:[]}` `plugin/src/extra-ops.js:229-244` | 官方 `ctx.jobs.watchRows` 是 Host 流（`ui-jobs/src/client/index.ts:43-46`）。失败不是空花名册 | 双向状态；用户点名的「任务状态不可用」 | abort/超时必须抛错或 `{error}`。手机已有「任务状态不可用」（`JobsAction.kt:63`），不要被空数组吃掉 | P0 | S |
| J2 | Header 每 3s 轮询 `JobsAction.kt:31-48`；打开的 JobsSheet 只在 `attempt` 时拉一次，常态有「刷新」`Sheets.kt:405-411,463` | `watchRows` 挂载即订阅，列表实时变 | 默认自动 | 复用 `jobs.follow` 或持续 list 流；Sheet 打开后与 header 同一条源。刷新只留在 error | P1 | M |
| J3 | 终止用 `AlertDialog` `Sheets.kt:477+` | 官方 kill 是列表内二次按（`JobListAction.tsx` `KILL_ARM_MS`） | 紧凑统一；不擅加限制 | 对齐二次按或 `PocketSheet`，不要系统 Alert | P2 | S |

### 6. 轨迹 / 代码查看 / 右侧面板

| # | 现状 | 桌面 | 原则 | 建议 | P | 量 |
| --- | --- | --- | --- | --- | --- | --- |
| R1 | 文件列表只在 path/`generation` 加载，手动刷新 `FilesPanel.kt:71-114,156-158` | 桌面文件树跟工作区/文件事件 | 默认自动；双向反映 | 订阅 `workspace.changed` / 文件事件后重载当前目录；刷新图标仅作恢复 | P1 | M |
| R2 | 会话诊断在 `ChatState` 对不上时直接 `return` | 官方 inspector 始终绑当前会话 | 交互完整 | 显示加载/错误框，不要空白 | P1 | S |
| R3 | 诊断筛选有额外说明 `SessionInspectorPanel.kt:313-318` | 官方 popover 更短 | 精简 | 说明收到 placeholder / 空候选，不要常驻教程 | P2 | S |
| R4 | 改动 diff 单栏、5000 行上限（`WorkspaceChanges`） | 桌面分栏 + hover | 允许窄屏适配 | 保持；文档已写明。不要用当前文件冒充历史快照 | P2 | — |
| R5 | 轨迹代码 tab 无 token 高亮 | 桌面 Shiki | 官方一致性 | P2；先保证文本/复制/程序输出与 `ui-trajectory` 一致 | P2 | L |

### 7. 终端

已定细节已对齐。剩余：

| # | 现状 | 桌面 | 原则 | 建议 | P | 量 |
| --- | --- | --- | --- | --- | --- | --- |
| T1 | 多行粘贴确认 `TerminalView.kt:181-198` | 桌面直接粘贴 | 不擅加限制 | 默认直接发送；不要常驻说明 | P2 | S |
| T2 | ANSI 调色板写死 Tokyo Night `TerminalEmulator.kt` | 跟 `--dsw-alias-*` | 官方一致性 | 映射到当前 Appearance | P2 | M |

### 8. pocket-host / 传输

| # | 现状 | 说明 | 原则 | 建议 | P | 量 |
| --- | --- | --- | --- | --- | --- | --- |
| H1 | `pocket-host` 仅 `config.iroh=true`，不参与手机配对（`plugin/index.js`、README） | 手机只有 host:port WebSocket | 不要未验证就怪 Tailscale / iroh | 查手机同步问题时不要走 iroh。LAN、`100.x`、`adb reverse` 对照 | — | — |
| H2 | Follow 仍按 iroh 行上限裁切：`DEFAULT_FOLLOW_MAX_MESSAGES=50`、`FRAME_SOFT_LIMIT=900KiB`、`FRAME_HARD_LIMIT=1MiB`（`plugin/src/server.js:27-34`），注释仍写 pocket-iroh | 官方桌面 follow 不经这条桥 | 状态失真 | WS 路径与 iroh 解耦。50 条窗口可保留，但截断必须让 `hasMore` + 连续补页可靠；超大帧要明确错误，不要静默变短 | P0 | M |
| H3 | `completion.view` 失败会 `c.close()` 整条 WebSocket `Pocket.kt:902-907` | 查看确认失败被做成断线 | 默认自动；勿误判网络 | 只报错并重试 view；不要拆连接。否则 LAN 上的应用错误会被看成 Tailscale | P0 | S |

---

## 手机有而桌面没有，应删除

| 项 | 位置 | 理由 |
| --- | --- | --- |
| 设置「自动化任务」行 | `Screens.kt:1361` | 桌面只在侧栏 |
| 「移动端」分组标题 | `Screens.kt:1364` | 自造 |
| 设置首页第二处「连接诊断」 | `Screens.kt:1367` | 与连接页重复 |
| 通用「代码自动换行」 | `Screens.kt` + `Stores.kt:238-240` | 桌面无此通用项 |
| ⋯ 重命名、会话统计块 | `Sheets.kt:221-234` | 桌面分别在侧栏 / composer |
| ⋯ 分支副标题「从当前会话前缀创建分支」 | `Sheets.kt:243-245` | 桌面菜单无长说明 |
| 「问题等待回答 · 重新打开」 | `Chat.kt:391-393` | 桌面无 |
| 「与桌面共用」 | `PluginsScreen.kt:334` | 自造 |
| 插件配置常态「重新读取」 | `PluginsScreen.kt:820+` | 正常态手动同步 |
| 库存页常态刷新 | `PluginInventory.kt:133-136` | 桌面库存无此工具条 |
| JobsSheet 常态「刷新」 | `Sheets.kt:463` | 应自动 |
| 硬编码官方配置 namespace 白名单 | `PluginsScreen.kt:54` | 桌面用 slot，不是 APK 列表 |

**应保留（桌面有或手机必需）：**

- 连接 / 配对 / 附近设备（pocket 设置节 + 手机专有）
- 审批通知（Android 通知，放连接页）
- 抽屉「插件」「自动化任务」（对齐侧栏 panellist）
- 插件管理页刷新（官方有）
- 无「凭证」独立模块
- 绿点、相对时间、无会话路径、无下移、无回到底部按钮

---

## 同步 / 状态失真：根因与验证

不要先归因 Tailscale。先在 **USB `adb reverse` → 127.0.0.1:7690** 复现；三路（loopback / LAN IPv4 / Tailscale）结果相同则是应用，只有远程路径不同才查网络。

### 假设 A — 绿点「与桌面同步」在当前官方桌面不成立（P0）

**证据**

- 官方 `ui-session@0.2.1-alpha.2`：`completionUnread` 是内存 Set，由 `observeRunning` / `isMain` 维护（`index.ts:483-517`）。无 `setCompletionUnreadSource`。
- Pocket 桌面 half：找不到该函数就 `console.warn` 后 return（`plugin/src/client/index.ts:26-28`）。README 已写明「旧桌面不能只靠更新 APK」。
- 手机：`completion.state` / `completion.view` + `ev:completion`（`Pocket.kt:393-399,881-909`；`completion-tracker.js`）。
- Tracker 把「正在看」定义为显式 `view()`，follow 订阅不算（`completion-tracker.js:3`）。

**机制**

官方桌面窗口继续用本地未读。手机与 Host tracker 可以互相同步。当前挂载的桌面包**不会**消费 tracker。手机上看过，官方窗口绿点仍在；反之亦然。

**验证**

1. 官方 `0.2.1-alpha.2` 桌面 + 本桥：完成一个非当前会话，看桌面控制台是否出现 `Pocket completion sync requires a Host client with uiSession.setCompletionUnreadSource`。
2. 手机打开该会话：手机绿点应灭；官方窗口绿点应仍在（未合并 hook 时）。
3. 不要把这当成网络问题。

**改法**

1. 向上游提 `uiSession.setCompletionUnreadSource`（或官方等价注入），进实际挂载的 client。  
2. 未合并前，产品文案不要写「两端已同步」。  
3. 不要用伪造 `mainView` retain 去骗官方点——官方注释写明 pocket 也不该这么做。

### 假设 B — 桌面已结束，手机仍「处理中」/ 流式慢（P0/P1）

**证据**

- 默认 snapshot 50 条，过大就裁窗口并截断 tool 文本（`server.js:27-34,80-175`）。注释仍绑定 iroh `MAX_LINE`。
- `146b04f` 已加顺序帧、缺口修补、`TranscriptPublisher` 50ms 合帧（`TranscriptPublisher.kt:15-23`）。结构状态会 `flush()`。
- `session/status` 把 idle 镜像进打开的聊天（`Pocket.kt` session status 分支），可能早于 follow 的 `turn/end`。
- follow 失败 3 次后 `loaded=true` 并停转（`Pocket.kt` `MAX_FOLLOW_ATTEMPTS`）。

**验证（loopback）**

1. 打第一条 follow：`records.length`、`hasMore`、`cursor`、是否 `truncated`。  
2. 对齐时间：`session/status running=false` vs 最后一条 follow `seq`。  
3. 开关 permessage-deflate，比帧间隔。三路相同 → 应用（窗口/裁切/合帧）；只有远程差 → 再量 RTT/丢包。

**改法**

WS 使用独立、更大的帧预算；截断必须可补页。合帧不得合并会改变 `running` 的结构事件（已 flush 的保持）。

### 假设 C — 「任务状态不可用」或任务数不对（P0）

**证据**

- `jobs.list` 把 abort 映射成 `{jobs:[]}`（`extra-ops.js:242-243`）。超时/取消变成「0 个任务」。  
- Header 异常才显示「任务状态不可用」（`JobsAction.kt:43-46,63`）。  
- 桌面是 `watchRows` 流，不是 3s 快照。

**验证**

1. 让 `jobController.list` 超过 RPC 超时或不吐 `rows`：响应必须是 error，不能是 `ok` + 空数组。  
2. 同一会话桌面 header 有 live count、手机显示 0 或「不可用」。  
3. loopback 复现则是桥，不是 Tailscale。

### 假设 D — 历史 / 诊断读失败（P1）

**证据**

- `history.page` 与 follow epoch 交错会被丢掉（`Pocket.kt` `loadOlderHistory`）。  
- `firstSeq < 0` 时不能翻页（`HistoryPagingPolicy.kt:18-19`）。  
- Inspector 在 ChatState 不匹配时空白返回。  
- 超大 follow 帧 → `frame/too-large`（`server.js:171`）。

**验证**

固定 `throughSeq`/`beforeSeq` 在 loopback 打 `history.page`。失败 → 参数或 Host page API。只在远程失败 → 再比帧大小与超时。

### 假设 E — 设置 / 轨迹开关两端不一致（P1）

**证据**

- 桥已广播 `settings.changed {ns,revision}`（`plugin/index.js:218`）。  
- 手机 `refresh()` + `adopt()` 可能留下更高的旧 revision（`HostSettings.kt:61-67`）。  
- 轨迹 tab 跟 `ui-settings.enabled`（代码工作视图）。

**验证**

桌面关/开「显示代码工作视图」，手机不离开聊天，1s 内 tab 应变。不变则打 `settings.changed` 是否到达、adopt 后该 ns 的 revision。

### 假设 F — 绿点 view 失败被做成「断网」（P0）

**证据**

`syncCompletionView` catch 里 `c.close()`（`Pocket.kt:902-907`）。

**验证**

临时让 `completion.view` 失败：整个会话 WS 不应掉线。若掉线，用户会以为是 Tailscale。只修 view 重试。

---

## 建议修复顺序

1. **P0** `jobs.list` 失败语义；`completion.view` 不再拆连接；follow 帧上限与 iroh 脱钩；绿点：对官方桌面诚实，并推动 `setCompletionUnreadSource`（或官方等价物）进**实际挂载**的 client。  
2. **P1** 设置信息架构（去掉设置里的自动化/「移动端」）；插件配置去白名单；jobs 改为实时流；文件列表自动失效；Host settings adopt；⋯ 去重；link-opening；plan-review 点。  
3. **P2** 文案/locale、终端粘贴、高亮、账户只读镜像。

---

## 实机验收清单（本审计未跑）

必须在同一 Host、官方桌面 `0.2.1-alpha.2`（或已带 hook 的更新版）+ 本 APK + 本桥上做：

1. 设置：桌面改通用/模型/插件开关，手机开着的页自动变；反向同样。不要点刷新。  
2. 绿点：一端完成非当前会话，另一端出现绿点；一端真正看完（对话已加载）后两端都灭。旧桌面应看到 warn，而不是假装成功。  
3. 长回合：桌面已出最终答复时，手机 `running`、过程折叠、工具顺序一致。  
4. 后台任务：桌面 header 有 live 时，手机不是 0、不是假「不可用」。  
5. 历史：向上翻页连续，无丢轮、无重复。  
6. 终端：Tab 补全发生在 Host PTY；点按移光标；长按能选能复制。  
7. 上述每条在 loopback 与 LAN 各做一遍；只有 LAN 失败才查防火墙/隔离；只有 Tailscale 地址失败才查 tailnet。

---

## 参考

- 官方仓：https://github.com/deepseek-ai/deepseek-harness/tree/d743267388  
- npm `alpha`：`0.2.1-alpha.2`（2026-10-09）  
- 本仓既有清单（基线偏旧，以本次源码为准）：`docs/desktop-parity.md`、`docs/feature-parity.md`、`docs/trajectory-parity.md`
