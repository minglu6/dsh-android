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

Ask-user tool (`ui-user-questions`): waterfall `user-questions/request` — **done** (push `user-question` + `userQuestions.answer`).

### 1.6 Attachments

Composer images/files: `conversation.attachment.*`, `conversation.image.*`, `conversation.file.*`.  
Data: `prompt` content parts (`text` / `image`); desktop also stages files via Host attachment APIs.  
Phone: image picker → `prompt` images (done). Non-image files via `attachments.upload` + `prompt` files (done). Folder attach = **hide** (desktop-only).

### 1.7 @-mentions & /commands

| Surface | Locale | Data |
|---|---|---|
| Placeholder | `placeholder.default` 发消息或创建任务, / 调用指令, @ 文件或对话 | — |
| Hero placeholder | `placeholder.hero` 描述你想要构建的内容, / 调用指令, @ 文件或对话 | — |
| Slash menu sections | `command.section.add` 添加; `command.section.commands` 指令 | Host `commands` catalog |
| Mentions | `reference.*` | `@file` / `@session` via ui-reference |
| Built-ins | goal/plan/feedback/compact/permission/export labels in `ui-commands` | `commands.execute` |

Phone: Plus sheet + live `/` CommandPopupSheet + `@` MentionPopupSheet (`references.files` / `references.sessions`, insert into composer) — **done**.

Slash menu pick consumes the typed `/` (desktop `slash/input-consume-token`) then runs the same actions as Plus / desktop: file picker, Goal/Feedback sheets, `/plan` toggle (+ PlanChip from host `plan` projection), `/compact`, permission/model pickers, session export. Connection status green dot stays in the **drawer** only — not the chat top bar (desktop session header has no pocket status dot).

### 1.8 Message actions (copy / like / retry / fork)

| Action | Locale | Data |
|---|---|---|
| Copy | common `copy` 复制 / `copied` 复制成功 | clipboard |
| Branch / fork | `message.branch` 在新对话中分支; `message.branchUnavailable` | `sessionController.fork({sessionId, atSeq?})` |
| Like / dislike | `feedback.action.like` 好的回答; `action.dislike` 有问题的回答; dialog `feedback.dialog.*` | message-feedback Host service + `/feedback` |
| Turn usage pill | `message.turnUsage.*` | projections / step usage |
| Clock | `clock.md` / `clock.ymd` | event `time` |

Retry is **display** of Host `llm/retry` chains (`message.retry.*`), not a user button.

**Icon chrome (desktop MessageIconActions / MessageFeedbackActions):** Android uses the same ui-primitives Regular SVG paths as vector drawables (`dsh_copy` / `dsh_check` / `dsh_like` / `dsh_like_fill` / `dsh_dislike` / `dsh_dislike_fill` / `dsh_branch`). Locale strings are **contentDescription + long-press tooltip only** — no visible text buttons. Order: copy → like/dislike (assistant) → branch; user clock `start`, assistant clock `end`; 28dp hit targets, 8dp gap, tertiary caption tint (`T.Caption`). Timestamp `maxLines=1` / `softWrap=false` so it never wraps.

### 1.9 Timestamps

IconActions clock beside copy/branch (`formatMessageClock` + `clock.*`). Shown on hover/focus on desktop; on phone always-on action row (no hover).

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
| 模型 | settings-models | **done** — native Compose + `models.providers` / `models.settingsMutate` / credentials for API keys |
| 内置插件 / 插件 | plugin-manager + inventory | **done** — list / enable / detail; local-path install stays **hide** (OS picker) |
| 自动化任务 | ui-schedule | **done** — catalog / create / edit / delete / history; no pause toggle (desktop `inactive` = ended) |
| 凭证 | credentials describe/set/unset | **done** — list/add/edit/delete; values masked (`configured` only) |
| Agent 预设 | agent-preset | keep roster info |
| 打开配置文件 | `openDocument` | **hide** (local desktop editor) |
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
| Copy on messages | **done** (icon-only; was text) | feasible |
| Branch / fork from message | **done** (icon-only + `sessions.fork`) | was needs-plugin |
| Like / dislike | **done** (icon-only + feedback sheet) | was needs-plugin |
| Message clock | **done** (nowrap beside icons) | feasible |
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
| 插件 / 自动化任务 | **done** (Settings nav → Plugins / Scheduled tasks; drawer entries still omit desktop chrome installers) | was hide |
| Files / terminal / jobs / todo sheets | present | — |
| Browser | hidden | hide |
| Settings general rows | present | — |
| Language / appearance prefs | **done** (labels wired via L; en for new surfaces) | was feasible (partial) |
| Models / plugins / credentials / schedules | **done** (native Compose; ops gated by hello `ops`) | was hide |
| Open config file / local-path plugin install | hidden | hide (desktop OS chrome) |

---

## 10. Plugin RPC map (current → needed)

**Present:** `sessions.list/create/rename`, `prompt` (text/images/**files**), `cancel`, `follow`/`unfollow`, `history.page`, `models.*`, `permissions.*`, `presets.list`, `approval.*`, `devices.*`, `pairing.*`, `commands.execute`, `session.export`, `files.list/read`, `jobs.list/kill`, `terminal.*`, `sessions.fork`.

**Added in this pass (parity-ops):** `sessions.search`, `workspace.state` / `archiveSession` / `unarchiveSession` / `pinSession` / `unpinSession`, `feedback.get` / `feedback.set`, `references.files` / `references.sessions`, `session.attachment`, `attachments.upload`, `userQuestions.answer` (+ push `user-question` / `user-question.closed`).

**Settings ops (this pass):** `models.providers` / `models.providerGet` / `models.settingsMutate` / `models.discover`; `plugins.list` / `plugins.setEnabled` / `plugins.install` / `plugins.inventory`; `schedule.catalog` / `list` / `create` / `update` / `delete` / `history`; `credentials.list` / `describe` / `set` / `unset` / `deleteRecord`. Host injects: `llm`, `settings`/`settingsController`, `credentials`, `pluginManager`, `schedule`.

**Still limited / unfinished:**

| Gap | Notes |
|---|---|
| Folder attach | desktop-only (**hide**) |
| Appearance theme tokens | prefs + labels done; new settings pages use `MaterialTheme.colorScheme` (parallel theme-token agent) |
| OS workspace picker / browser / open-config / local-path plugin install | **hide** (desktop OS chrome) |
| Schedule pause toggle | desktop has no pause — `inactive` means ended after delivery |
| Plugin schema ConfigForm editors | desktop `configForms` is client-side over settings namespaces; phone shows enable/detail/parts. Full schema-driven forms stay limited — use `models.settingsMutate` / Host settings when a namespace is known |
| `settings.openSettingsDocument` | desktop OS editor (**hide**) |

**Also added:** `workspaceChanges.summary` → Host `workspaceChanges.summary(sessionId, seq)` to enrich changed-file cards; `@` mention pick inserts into composer.

**Adapted from dsh-mobile (Apache-2.0):** list→detail fullscreen Settings chrome and Host `pluginManager` / schedule call shapes studied from [saya-ch/dsh-mobile](https://github.com/saya-ch/dsh-mobile); see `docs/THIRD_PARTY_NOTICES.md`.

---

## 11. Locale appendix index

Full zh/en dictionaries were extracted from harness @ `d743267` into agent workspace `/tmp/dsh-locales-all.json` (≈50 namespaces, ≈2276 keys). Primary namespaces for phone work: `chat`, `conversation`, `ui_workspace`, `ui_commands`, `ui_message_feedback`, `ui_sidebar_*`, `job`, `deliverables`, `ui_approval`, `ui_user_questions`, `ui_model_selection`, `ui_permission_presets`, `ui_theme`, `ui_settings_general`, common `locale`.

When adding UI copy on Android, **paste the zh (and en) string from those dictionaries** — do not invent synonyms.

---

## 12. Icon-vs-text audit (desktop icons → Android)

Source: harness `d743267` / `0.2.1-alpha.2` `@deepseek-ai/dsh-client-ui-primitives` icons + owning ui-* packages. Rule: where desktop renders an SVG with Tooltip, Android must use the **same path** as a vector drawable + contentDescription/tooltip — not Material substitutes, emoji, or visible text labels.

| Surface | Desktop | Was (phone) | Fix / decision |
|---|---|---|---|
| Message action row | IconCopy / Like / Dislike / Branch + clock | Text「复制 / 在新对话中分支 / 好的回答 / 有问题的回答」; time wrapped | **Fixed** — icon-only row, desktop order/spacing, nowrap clock |
| User message actions | Same chrome, clock start, right-aligned | Row present but text labels | **Fixed** — same ActionIconButton chrome |
| Code block header | CodeToolbar: WrapFill/NowrapFill + Copy/Check icons | Language only; no wrap/copy | **Fixed** — wrap + copy icons with locale tooltips |
| Composer permission | Shield + 「工作区内修改」 + chevron | Shield-only when narrow | **Fixed** — always shield + label like desktop |
| Composer send | IconSendOutline | Material `ArrowUpward` | **Fixed** → `D.send` |
| Top bar menu | PanelLeft (sidebar) | Material `Menu` hamburger | **Fixed** → `D.panelLeft` |
| Trajectory search | IconSearchOutline | Material `Search` | **Fixed** → `D.search` |
| Top bar ⋯ / new / panel / team / preset | Desktop SVGs already | Already `D.*` icons | OK |
| Composer + / model / chevrons | Desktop SVGs + text where desktop shows text | Already icon+label | OK (model name is text on desktop too) |
| Sidebar / sheet OptionRows | Icon + text label (menus) | Icon + text | OK — menus keep text; not IconActions chrome |
| Tool sheet 「复制结果 / 复制参数」 | Desktop inspector uses code-card copy icons in-place | Full-width outlined text buttons | **Keep** — phone sheet primary actions stay labeled buttons (touch); not an IconActions row |
| Connections Devices glyph | N/A (phone-only) | Material `Devices` | **Keep** — no matching ui-primitives glyph; phone-only surface |
| Turn usage pill | Icon + usage text | Not on phone action row | **hide** / later — optional endInfo slot |
| Request chip / todo / trajectory detail tabs | Mostly text chrome or already icon+label | Aligned | OK |

**Drawables added this pass:** `dsh_branch`, `dsh_like`, `dsh_like_fill`, `dsh_dislike`, `dsh_dislike_fill`, `dsh_wrap`, `dsh_nowrap`, `dsh_wrap_lines` (JsonTree/optional). Wired on `D` in `DshIcons.kt`.

---

## 13. Long-press text selection

Every **output** text surface supports Compose `SelectionContainer` (helper `SelectableOutput` / `NonSelectable`) so users can long-press → select → copy:

| Surface | Approach |
|---|---|
| Assistant Markdown (paragraphs / lists / tables / fences) | `MarkdownView` wraps once; code toolbar icons use `NonSelectable` |
| User bubbles | `SelectableOutput` around bubble `Text` |
| Thinking / reasoning body | Fold header stays clickable; body in `SelectableOutput` |
| Notices / errors / approvals / questions / deliverables / changes | `SelectableOutput` on content |
| Tool / file preview `CodeBox` | `SelectableOutput` |
| Todo sheet items | `SelectableOutput` |
| Trajectory ledger title / preview / metrics | `SelectableOutput` (tap still opens detail) |
| Trajectory + Request detail tabs / payloads | `SelectableOutput` on payloads & KV values |
| Terminal (Canvas grid) | Long-press → selectable plain-text overlay (`AnsiTerminal.plainText()`); tap exits |

Composer input, buttons, and icon action chrome stay non-output (no forced selection).

---

## 14. Phone soft-wrap (intentional desktop deviation)

Desktop often keeps mono payloads on one horizontally scrolling line. On phone, **soft-wrap is the default** so paths / URLs / long tokens stay readable without sideways scrolling.

| Surface | Behaviour |
|---|---|
| Trajectory detail tabs (概述/参数/结果/Schema/计时/…) | `TrajPayload` / code / schema: no `horizontalScroll`; `breakAnywhere()` + `softWrap` |
| Request inspector options JSON | same |
| Chat fenced code | wrap **ON** by default; toolbar toggle still enables horizontal scroll |
| Tool / file `CodeBox` | soft-wrap |
| Thinking / notices / approval command lines | soft-wrap |
| Markdown GFM tables | keep horizontal scroll (multi-column layout) |
| Terminal VT grid | **unchanged** — wrapping would break cursor apps; long-press selectable overlay remains |
| Detail sheet tab row | `ScrollableTabRow(edgePadding = 22.dp)` so first/last labels are not clipped |

`String.breakAnywhere()` inserts zero-width spaces after path separators and every N unbroken characters so Compose can wrap mid-token while selection stays available.
