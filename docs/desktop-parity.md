# Desktop parity inventory — DeepSeek Harness → Android

**Desktop source:** `deepseek-ai/deepseek-harness` @ `d743267` (0.2.1-alpha.2)  
**Android baseline:** `main` @ `46f7570` (and later)  
**Locale source of truth:** each `@deepseek-ai/dsh-client-ui-*` package’s `src/client/locales.ts` (zh key-set + en). Common words live in `packages/client/locale` (`copy`=`复制`, `copied`=`复制成功`, …).  
**Trajectory:** skipped except shared primitives (another agent owns the 轨迹 tab audit).

Decision tags used in §9:

| Tag | Meaning |
|---|---|
| **feasible** | Ship on phone with existing or new pocket RPC; adapt to bottom sheets / phone chrome |
| **needs-plugin** | Host has the data/API; pocket must forward it before UI can finish |
| **hide** | Requires local desktop chrome (OS picker, embedded browser, plugin install UI, schedule CRUD, credentials). Do not show a stub |

Phone constraints (always): Tokyo Night tokens where terminal/code surfaces need them, Meslo for mono, themed bottom sheets (no system `AlertDialog`), desktop locale strings only, no `FLAG_SECURE`, no secrets in logs, no GMS.

---

## 1. Chat / transcript

Package: `packages/client/ui-chat` (+ folding helpers in `ui-conversation`).  
Data: `sessionController.follow` / `page` event stream (pocket `follow` / `history.page`).

### 1.1 Message kinds

| Kind | Desktop behaviour | Locale (zh / en) | Data source |
|---|---|---|---|
| User | Right-aligned bubble; optional images; pending echo until durable | — | `user/message` (typed source only) |
| Assistant text | Markdown stream | — | `assistant/message` content blocks `type=text` |
| Reasoning / thinking | Collapsible “思考” strip; live “深度求索中” | `message.think` 思考 / Think; `message.turnProcess.thoughtForAWhile` 已思考 / Thought for a while; `chat.deepDiving` 深度求索中 / Deep diving; `chat.deepDivingFor` 深度求索中，用时 {duration} ··· | reasoning content blocks + timing |
| Tool call | Row with status; opens inspector | tool titles in `conversation.*` | `tool/call` + `tool/result` |
| Tool-process group | Folded summary of a turn’s tools+thinking | `message.stepProcess.*` / `message.turnProcess.*` (see §1.4) | Derived client-side from events |
| Notice / trigger | Non-bubble status lines | `message.trigger.*` | turn triggers (goal/job/schedule/…) |
| Compaction | Compact summary card | `message.compaction*` | compaction events |
| Model retry | Retry chain row | `message.retry.*` | `llm/retry`, `llm/retry-started` |
| Turn error | Failure banner | `message.turnError` 本轮运行失败 / This turn failed; `message.failure.*` | turn failure surfaces |
| Stopped / interrupted | Stop notice | `message.stopped` 已停止 / Stopped; `message.accountStopped` 任务已停止 | `step/end` `interrupted`, cancel |
| Max tokens | Truncation hint | `message.maxTokens` / `message.maxTokens.hint` | max-tokens surface |
| System prompt | Trajectory-only on desktop inspector; chat may show tool-update notices | `message.systemPrompt*` | `system/message` |
| Deliverables | File cards under reply | `deliverables.row.*` 交付文件 / Present files | `deliverables/presented` |
| Changed files | Diff entry under reply | `deliverables.changes.*` | turn file-change projection |

### 1.2 Markdown

Desktop renderer (ui-chat / primitives): headings, lists, tables, fenced code (Meslo), inline code, links, images, footnotes (`markdown.footnotes`), truncation (`markdown.truncatedCharacters`).  
Android: `Markdown.kt` covers the common subset; keep aligning to desktop tokenizer, not a third-party “pretty” theme.

### 1.3 Thinking blocks

- Live: `chat.deepDiving` / `chat.deepDivingFor`  
- Done: `message.turnProcess.thoughtForAWhile` + duration (`duration.*`)  
- Folded into process groups per transcript-view preference (`settings.transcript.*`, `settings.collapse.*`)

### 1.4 Tool-process folding

Labels from `chat` locale, e.g.:

| Key | zh | en |
|---|---|---|
| `message.stepProcess.thinking` | 正在分析请求 | Analyzing the request |
| `message.stepProcess.read` | 正在读取文件 | Reading files |
| `message.stepProcess.commands` | 正在运行命令 | Running commands |
| `message.stepProcess.done.edit` | 修改了文件 | Edited files |
| `message.turnProcess.took` | 已完成，用时  | Completed in  |
| `message.turnProcess.failed` | 处理失败 | Failed |
| `message.turnProcess.worked` | 已完成 | Completed |

Grouping policy keys: `settings.transcript.{compact,standard,detailed,verbose}`, `settings.collapse.{completion,nextInput}`.

### 1.5 Approvals & permission prompts

Package: `ui-approval`.  
Data: Cordis waterfall `approval/request` → pocket push `{ev:"approval"}` / `approval.answer`.

| Key | zh | en |
|---|---|---|
| (card title, app-local official copy) | 等待审批 | — |
| | 工具 {name} 请求越权执行 | — |
| | 拒绝 / 允许一次 | rejected / allowed-once |

Ask-user tool (`ui-user-questions`): separate waterfall `user-questions/request` — **needs-plugin** on phone.

### 1.6 Attachments

Composer images/files: `conversation.attachment.*`, `conversation.image.*`, `conversation.file.*`.  
Data: `prompt` content parts (`text` / `image`); desktop also stages files via Host attachment APIs.  
Phone: image picker → `prompt` images (done). Folder attach = **hide** (desktop-only). Non-image file stage = **needs-plugin** (`session.attachment` / upload).

### 1.7 @-mentions & /commands

| Surface | Locale | Data |
|---|---|---|
| Placeholder | `placeholder.default` 发消息或创建任务, / 调用指令, @ 文件或对话 | — |
| Hero placeholder | `placeholder.hero` 描述你想要构建的内容, / 调用指令, @ 文件或对话 | — |
| Slash menu sections | `command.section.add` 添加; `command.section.commands` 指令 | Host `commands` catalog |
| Mentions | `reference.*` | `@file` / `@session` via ui-reference |
| Built-ins | goal/plan/feedback/compact/permission/export labels in `ui-commands` | `commands.execute` |

Phone today: placeholders + Plus sheet for built-ins. Live `/` popup and `@` mention search = **feasible** (commands) / **needs-plugin** (file mention index).

### 1.8 Message actions (copy / like / retry / fork)

| Action | Locale | Data |
|---|---|---|
| Copy | common `copy` 复制 / `copied` 复制成功 | clipboard |
| Branch / fork | `message.branch` 在新对话中分支; `message.branchUnavailable` | `sessionController.fork({sessionId, atSeq?})` |
| Like / dislike | `feedback.action.like` 好的回答; `action.dislike` 有问题的回答; dialog `feedback.dialog.*` | message-feedback Host service + `/feedback` |
| Turn usage pill | `message.turnUsage.*` | projections / step usage |
| Clock | `clock.md` / `clock.ymd` | event `time` |

Retry is **display** of Host `llm/retry` chains (`message.retry.*`), not a user button.

### 1.9 Timestamps

IconActions clock beside copy/branch (`formatMessageClock` + `clock.*`). Shown on hover/focus on desktop; on phone show on long-press / always-on action row.

### 1.10 Error & abort states

| State | Locale | Source |
|---|---|---|
| Interrupted | `message.stopped` / notice 已中断 | `step/end.interrupted` |
| Turn failed | `message.turnError`, `message.failure.*` | failure surfaces |
| Load history fail | `chat.loadError` | follow/page error |
| Session in use | `error.sessionInUse` | session lock |

### 1.11 Streaming

Live attempt baseline + stream records in follow frames; Android `TranscriptReducer` already mirrors this. Keep stop button wired to `cancel` (`input.stop` 停止生成).

---

## 2. Session header

Packages: `ui-conversation` (header shell), `ui-agent-preset`, experimental `client-ui-agent-team`, `ui-jobs`, `session-log-export`.  
(`ui-session` is the SessionProvider binding only — not chrome.)

| Control | Locale | Behaviour / data |
|---|---|---|
| Title / lineage | workspace `session.untitled` 未命名; aria `session.hierarchy` 会话层级 | `displayTitle` + subagent ancestry; open parent via workspace |
| Tabs | `view.chat` 对话 (+ trajectory — out of scope) | Conversation Views |
| 智能体团队 | agent-team `trigger` 智能体团队 | header action order −20; projection `agentTeam` |
| Agent 预设 chip | agent-preset names + `headerHint` | read-only mid-session (`agentPreset` projection) |
| 后台任务 chip | job `count.live.*` / `list.aria` 后台任务 | **header** action (not right-panel guide); `ctx.jobs` |
| 提醒 | schedule catalog `trigger.label` 提醒 | header utility; opens schedule tab |
| ⋯ menu | `更多操作`; **下载 Session 日志**; **反馈** | session-log-export ZIP + `feedbackUi.openSession` — **rename is not here** (sidebar row menu) |

**会话统计** lives in the **composer dock** (`ui-chat` StatsPills: `stats.dialog.title` 会话统计, `stats.counts`, `stats.cacheHit`, …), not the header ⋯.

Phone note: Android currently also puts rename + stats into the ⋯ sheet for reachability; desktop keeps rename on the sidebar row.

---

## 3. Composer

Package: `ui-conversation` input bar (+ `ui-commands`, `ui-model-selection`, `ui-permission-presets`, `ui-plan`, `ui-goal`, `ui-chat` stats dock).

| Control | Locale | Data |
|---|---|---|
| Model + reasoning effort | `menu.aria` 模型与推理等级; `menu.effort` 推理等级 | `models.catalog` / `models.select` (+ `reasoningEffort`) |
| Permission mode | permission-presets「访问模式」 | `permissions.get/set` (desktop also `/permission`) |
| Plan chip | `chip.label` 计划 | projection `plan`; `/plan off` |
| Goal bar | goal locales（进行中的目标…） | `conversation.input.dock` id `goal` |
| Workspace chip (empty home) | `hero.chooseWorkspace` 选择工作区 | known cwds / Host picker (**hide** OS picker) |
| Mode / preset chip | agent-preset `seatHint` | create-time only |
| + / `/` menu | `command.section.add` 添加; `section.commands` 指令 | see §1.7 |
| Stats pills | `stats.*` | composer dock (activity + usage) |
| Send / queue / steer | `input.send`, `input.send.queue`, `input.send.steer` | `prompt` mode |
| Stop | `input.stop` 停止生成 | `cancel` |
| Busy send pref | `settings.enter.*` | phone-local prefs |

---

## 4. Sidebar (left)

Packages: `ui-sidebar`, `ui-workspace`, `ui-plugin-manager`, `ui-schedule`.

| Feature | Locale | Data | Phone decision |
|---|---|---|---|
| Brand / connection | — | pocket profiles | keep |
| 新会话 | `session.new` | `sessions.create` | keep |
| 工作区 section | `section.workspaces` 工作区 | session cwd groups | keep |
| Group / order / filter | `groupBy.*`, `orderBy.*`, `viewOptions.*` | workspace registry | feasible (subset) / archive filter **needs-plugin** |
| Search | `search.placeholder` 搜索会话名称 | `sessionController.search` | **needs-plugin** |
| Rename / delete workspace | `rename.*`, `delete.workspace` | workspace controller | delete workspace **hide**/limited; rename session **feasible** |
| Session row menu: fork / archive / pin | `menu.fork`, `menu.archiveSession`, `menu.pinSession` | `fork`; `workspace/{un,}archiveSession`; `workspace/{un,}pinSession` | **needs-plugin** |
| 插件 | plugin-manager locales | local plugin UI | **hide** |
| 自动化任务 | schedule locales | schedule CRUD | **hide** |
| 设置 | settings `trigger` 设置 | — | keep |

Empty: `empty.none` 暂无会话 / `empty.noMatches` 无匹配结果.

---

## 5. Right panel

Packages: `ui-sidebar-right` + files / terminal / browser / documentpreview / deliverables.  
**Desktop guide launchers are only three:** Workspace files, New terminal, Browser (orders 10/20/30).  
Phone adaptation: themed bottom sheets.

| Entry | Locale | Data | Decision |
|---|---|---|---|
| 工作区文件 | `guide.title` 工作区文件 | `files.list` / `files.read` | feasible (done) |
| 终端 | `title` 终端 / guide `新建终端` | `terminal.*` | feasible (done) |
| 浏览器 | `guide.title` 浏览器 | client iframe only | **hide** |
| 文档预览 | documentpreview locales | Host file bytes + preview (opened via `openResource`, not a guide capsule) | **needs-plugin** / heavy; text subset via `files.read` |
| Dock split / float | `dock.*` | desktop layout | **hide** (phone sheets) |
| Deliverable / changes-review tabs | `deliverables.*` | events / turn payloads | feasible once events parsed |

**Not right-panel guide entries (desktop placement):**

| Surface | Desktop home | Locale | Phone |
|---|---|---|---|
| 后台任务 | **Session header** job list action | `job.list.aria` 后台任务 | sheet from panel launcher (done) — acceptable phone regroup |
| 任务 / 清单 (Todo) | **Conversation dock** `TodoPanel` (+ `todo_write` tool row) | `todo.title` 任务 | sheet from panel when todos present (done) — not a desktop right-rail launcher |

---

## 6. Settings

| Page | Locale | Decision |
|---|---|---|
| 通用设置 | `general.nav` 通用设置; language / appearance / font / transcript / collapse / busy-send / performance | feasible (mostly done, phone-local) |
| 模型 | settings-models | **hide** credentials UI |
| 内置插件 / 插件 | plugin inventory | **hide** |
| Agent 预设 | agent-preset | keep roster info |
| 打开配置文件 | `openDocument` | **hide** |
| Shell / 终端 limits | settings-shell | optional **needs-plugin** |
| Web search / subagent / agent-loop / account / session-log | respective locales | hide or limited |
| 连接 / 审批通知 | phone Connections + notify pref | keep |

Appearance values from `ui-theme` locales. Performance: `settings.performance.{compact,detailed}`.

---

## 7. Empty states, toasts, dialogs

| Kind | Examples (zh) | Notes |
|---|---|---|
| Empty hero | `hero.headline` 探索未至之境; `hero.preview` 预览版 | done |
| Empty transcript | 开始对话 (phone) | align if desktop adds official string |
| Empty sessions | `empty.none` 暂无会话 | done |
| Toasts | `toast.archived*`, `toast.recorded` 感谢你的反馈, connection toasts | Snackbar OK; no AlertDialog |
| Dialogs | archive confirm, full-access confirm, feedback dialog, stats | bottom sheets |
| Full access | 「启用完全权限？」 ack | done |

---

## 8. Shared chrome constraints (phone)

- Sheets: `PocketSheet` / `SecureFlagPolicy.SecureOff`; never `AlertDialog`.
- Mono: Meslo Nerd Font (`T.Mono`); terminal Tokyo Night.
- Labels: only desktop locale strings (zh primary; en when locale pref = en).
- No GMS; no `FLAG_SECURE`.

---

## 9. Gap matrix (Android @ 46f7570+)

### Chat

| Item | Status | Tag |
|---|---|---|
| User / assistant / reasoning / tool / process fold | present | — |
| Markdown core | present (#34) | keep aligning |
| Approvals card | present | — |
| Image attach (send) | present | — |
| Image bubbles in transcript | **done** (`session.attachment` + ImageRef) | was feasible |
| Non-image file attach | **done** (`attachments.upload` + `prompt` files) | was needs-plugin; folders still hide |
| `/` command popup | **done** (CommandPopupSheet when composer starts with `/`) | was feasible |
| `@` file/session mentions | **done** (`references.files` / `references.sessions`) | was needs-plugin |
| Copy on messages | **done** (this PR) | feasible |
| Branch / fork from message | **done** (`sessions.fork` + actions) | was needs-plugin |
| Like / dislike | **done** (`feedback.get` / `feedback.set` + category sheet) | was needs-plugin |
| Message clock | **done** (this PR) | feasible |
| Retry / turn-error / maxTokens rows | **done** (this PR) | feasible |
| Stopped / interrupted copy | **done** (`message.stopped` 已停止) | feasible |
| Deliverable / changed-file cards | **done** (parse `deliverables/presented` / `workspace/changes`) | Changes count enrichment still Host-summary limited |
| Streaming + stop | present | — |
| Todo panel (清单) | **done** (parse `todo/write` + projections; sheet) | was needs-plugin |
| User-questions prompts | **done** (push `user-question` + `userQuestions.answer`) | was needs-plugin |

### Header / composer / sidebar / panels / settings

| Item | Status | Tag |
|---|---|---|
| Title, team, preset, ⋯ (export/feedback), rename/stats on phone ⋯ | present (phone regroup vs desktop) | — |
| Header jobs chip | jobs via panel sheet | phone regroup |
| Composer stats pills | in More 会话统计 | phone regroup |
| Model + effort + permission | present | — |
| Workspace / preset chips | present (cwd list only) | hide OS picker |
| Plus menu built-ins | present | — |
| Queue/steer busy behaviour | **done** (发送消息 / 排队发送 / 插话发送; Steer label fixed) | was feasible |
| Drawer width / workspaces / new session | present | — |
| Session search | **done** (`sessions.search`) | was needs-plugin |
| Archive / pin / fork from sidebar | **done** (workspace archive/pin + row ⋯ / long-press) | was needs-plugin |
| 插件 / 自动化任务 | removed | hide |
| Files / terminal / jobs / todo sheets | present | — |
| Browser | hidden | hide |
| Settings general rows | present | — |
| Language / appearance prefs | **done** (labels wired via L; en for new surfaces) | was feasible (partial) |
| Models / plugins / open config | removed | hide |

---

## 10. Plugin RPC map (current → needed)

**Present:** `sessions.list/create/rename`, `prompt` (text/images/**files**), `cancel`, `follow`/`unfollow`, `history.page`, `models.*`, `permissions.*`, `presets.list`, `approval.*`, `devices.*`, `pairing.*`, `commands.execute`, `session.export`, `files.list/read`, `jobs.list/kill`, `terminal.*`, `sessions.fork`.

**Added in this pass (parity-ops):** `sessions.search`, `workspace.state` / `archiveSession` / `unarchiveSession` / `pinSession` / `unpinSession`, `feedback.get` / `feedback.set`, `references.files` / `references.sessions`, `session.attachment`, `attachments.upload`, `userQuestions.answer` (+ push `user-question` / `user-question.closed`).

**Still limited / unfinished:**

| Gap | Notes |
|---|---|
| `workspace/changes` count/name | Event carries turn only; Host `workspaceChanges.summary` not yet a pocket op — card shows when count/name present |
| Folder attach | desktop-only (**hide**) |
| Appearance theme tokens | prefs + labels done; UI chrome still light `T.*` |
| Mention insert into composer | popup lists refs; picking dismisses (insert-into-text polish left) |

---

## 11. Locale appendix index

Full zh/en dictionaries were extracted from harness @ `d743267` into agent workspace `/tmp/dsh-locales-all.json` (≈50 namespaces, ≈2276 keys). Primary namespaces for phone work: `chat`, `conversation`, `ui_workspace`, `ui_commands`, `ui_message_feedback`, `ui_sidebar_*`, `job`, `deliverables`, `ui_approval`, `ui_user_questions`, `ui_model_selection`, `ui_permission_presets`, `ui_theme`, `ui_settings_general`, common `locale`.

When adding UI copy on Android, **paste the zh (and en) string from those dictionaries** — do not invent synonyms.
