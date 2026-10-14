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
| **hide** | Requires local desktop chrome (OS picker, native editor). Existing desktop plugin, model API-key and task management use Host APIs; the browser uses Android WebView. An API alone must not create a new phone feature. |

Phone constraints (always): desktop `--dsw-alias-*` chrome (light/dark via Appearance), Tokyo Night tokens where terminal/code surfaces need them, Meslo for mono, themed bottom sheets (no system `AlertDialog`), desktop locale strings only, no `FLAG_SECURE`, no secrets in logs, no GMS.

---

## 1. Chat / transcript

Package: `packages/client/ui-chat` (+ folding helpers in `ui-conversation`).  
Data: `sessionController.follow` / `page` event stream (pocket `follow` / `history.page`).

### 1.1 Message kinds

| Kind | Desktop behaviour | Locale (zh / en) | Data source |
|---|---|---|---|
| User | Right-aligned bubble; optional images; pending echo until durable | — | `user/message` (typed source only) |
| Assistant text | Markdown stream | — | `assistant/message` content blocks `type=text` |
| Reasoning / thinking | Independent collapsible “思考” strip; separate whole-Turn running status | `message.think` 思考 / Think; `chat.deepDiving` 深度求索中 / Deep diving; `chat.deepDivingFor` 深度求索中，用时 {duration} ··· | reasoning blocks; running clock from Host `turn/start.time` |
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

Inline code preserves its full literal text and is not promoted to a file chip based on slashes or extensions; directory paths therefore remain non-navigating. Explicit local Markdown links still open file previews. Desktop additionally resolves inline mentions through an owner-supplied file vocabulary; Android does not infer that vocabulary from path-shaped text.

Paragraphs use one native text layout across emphasis, links and inline chips. Bold labels and their following punctuation/body share the same line; code and explicit file links retain their inline appearance and interaction. Quotes draw their rule without intrinsic measurement so nested rich text remains supported.

Headings and strong emphasis request Android's native bold weight rather than copying CSS's numeric 600: the OnePlus system font renders 600 as regular, while 700 preserves the desktop's visibly bold hierarchy. This is a rendering choice, not a change to the shared font preferences.
`MarkdownRenderingInstrumentedTest` compares actual glyph ink on-device so an OEM mapping that silently drops emphasis cannot pass only by retaining a 600-valued style in the AST.

Tables use content-sized columns instead of fixed 140/200dp cells. Fewer than four columns fill the message width while preserving a readable minimum; wider tables scroll horizontally. Every row, including the final row, has a full-width hairline inside an explicitly bounded table. Narrow-phone light/dark rendering and inline code/link interactions are covered by `MarkdownLayoutTest` and `MarkdownInteractionTest`.

Rendering evidence: [narrow/light](screenshots/markdown-comparison-light.png), [narrow/dark](screenshots/markdown-comparison-dark.png). The existing “介绍 Claude” response was also checked on OnePlus without resending its prompt; native bold ink is verified separately by the instrumented regression.

### 1.3 Thinking blocks

- Reasoning uses the official Think icon and a one-line `思考 · preview` disclosure. Expanding one reasoning row renders its recorded Markdown independently; it does not open all reasoning blocks.
- Reasoning duration is not a completed-Turn duration. The completed-Turn header uses only Host `turn/start` and `turn/end` timestamps.
- Original reasoning/tool order and Host step ownership are retained across snapshots, live delivery and history paging.

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

Android groups by Host Turn identity rather than user-message boundaries. Completed duration is `max(1000, end.time - start.time)`, floored into seconds/minutes/hours like desktop; missing boundaries show completion without an invented number. Aborted/error turns show stopped/failed without duration. Pure-text and reasoning-only turns still get the completed-Turn header.

Process groups are segmented at assistant replies, independent inputs/notices and Turn boundaries; grouping never moves a tool across a reply. The completed-Turn control is a separate row that hides process groups and intermediate replies, leaving the final answer visible. Standard/Compact process disclosures start closed; Detailed live/deferred Turns and Verbose render their members flat. Failed, aborted or interleaved-input Turns cannot hide the whole process. Tool titles use the official registry; failed or malformed output is never presented as a successful zero count.

The reported greeting was replayed from its original journal: `1791882640048 - 1791882633220 = 6828ms`, displayed as 6 seconds. All three reasoning blocks and three calls retain the order `reasoning → wait_agent → reasoning → job_list → list_agents → reasoning`. Evidence: [collapsed](screenshots/real-turn-6s-collapsed.png), [expanded](screenshots/real-turn-6s-expanded.png), [dark process](screenshots/26-message-chrome-expanded-dark.png).

The whole-Turn running status stays visible during reasoning and tool execution. Its clock uses the current open Host Turn's start, not the phone connection time; missing start data shows “深度求索中” without an invented duration. The official 60-frame whale artwork, blue theme tokens, shared text shimmer and output divider are rendered natively; disabling system animations uses the official static whale.

Renderer evidence from deterministic Host-event replay through the actual reducer and Compose UI: [standard/light](screenshots/controller-status-light.png), [standard/dark](screenshots/controller-status-dark.png), [detailed/dark](screenshots/controller-status-detailed.png). This is a rendering smoke, not a live-model run. Independent real-Host verification exercised phone-protocol writes updating the open official Web settings page, Web UI writes pushing back to the phone protocol, and stale-revision rejection.


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
| Turn usage pill | `message.turnUsage.*` | inline `用量 {total} tok` in detailed performance/usage mode, only with complete per-turn accounting; tap for exact token/model/cache details |
| Clock | `clock.md` / `clock.ymd` | event `time` |

Retry is **display** of Host `llm/retry` chains (`message.retry.*`), not a user button.

Assistant completion actions wait for the Host Turn to close and belong to its last answer, not every durable intermediate reply. The running process therefore does not gain a copy/time/action row merely because one assistant step has committed.

Turn usage follows the desktop token meter's durable attempt lifecycle, including billed failed attempts and retries. Final message usage overrides streaming samples; missing boundaries, unsafe counts or contradictory totals hide the entire disclosure. Optional cache/reasoning buckets and model routes appear only when every attempt reports them. History paging can complete a partial turn; snapshots and duplicate events never reuse or double-count old totals. Switching to Compact removes both the pill and any open details dialog. The phone bridge is unchanged.

**Icon chrome (desktop MessageIconActions / MessageFeedbackActions):** Android uses the official ui-primitives Regular SVG paths. User messages show clock → copy, without a branch control. Assistant messages show copy → like/dislike → branch → optional usage → clock; copy/branch are 17dp and feedback icons 15dp, with tertiary `T.Text3` tint. Feedback selection swaps outline/fill rather than inventing blue/red colors. Icon controls occupy 36dp horizontally with no extra inter-button gap, retain a 48dp-high touch area, and leave 8dp before/after the clock; unavailable branch actions retain their disabled state and reason. Icon labels remain accessibility labels/tooltips. The usage pill shares the row's remaining width without pushing the clock off a narrow screen. No standalone “本轮用量” row is appended below answers; composer statistics and trajectory usage details remain available. Process/reasoning chevrons use vector artwork and rotate on expansion instead of text glyphs.

The usage action is transparent at rest, like the adjacent shortcut icons. Its rounded background appears only while the details dialog is expanded; native press feedback remains enabled, and dismissing the dialog restores transparency. Its 14dp icon uses an 11dp leading inset to continue the shortcuts' 36dp center-to-center rhythm, with a 4dp icon-to-label gap. Narrow layouts retain the clock and ellipsize the usage label instead of compressing the icon spacing.

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
The Android composer dock uses authoritative whole-log `sessionStats` and `tokenUsage` plus Host context-pressure/breakdown projections. Compact displays speed/cache/context readings; Detailed adds counts and inspection dialogs. The permission trigger is intentionally icon-only on phones; its menu is the live Host catalog, including acknowledged current-session auto review when available.


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
| Busy send pref | `settings.enter.*` | shared Host `ui-conversation.busyEnter` |

### Model picker on Android

`ModelPicker.kt` uses a composer-anchored popup, not a bottom sheet. The root has Model / Effort value rows; each opens its own compact list. Plus and `/model` enter the model list directly. Back/Escape returns from a submenu, outside tap dismisses, and a successful selection closes the popup. Touch rows retain a 44dp minimum; the card is bounded on narrow and landscape screens.

- The trigger and model rows have no decorative model logo. Rows show model names and a trailing check/pending indicator, without repeated descriptions.
- Providers follow the official stable order (DeepSeek account, official, then Host order), with pinned group headings. Catalogs over four models expose name-only fuzzy search, clear/no-match states, and keyboard arrows/Enter.
- Reasoning names, availability and default come from the exact Host model. No default effort means an explicit `Default` choice; switching models never carries the old model's effort, and choosing the current model preserves it.
- Directory loading, partial-provider failures and retry are visible. Pending requests disable repeated selection; a rejected RPC leaves the original selection intact. Current selection follows `modelSelection.next` or the global catalog default, not `lastUsed`; stale replies cannot overwrite a newer projection.

Compose render evidence: [root](screenshots/10-model-picker-root.png), [models](screenshots/10b-model-picker-models.png), [efforts](screenshots/10c-model-picker-efforts.png), [dark landscape](screenshots/10d-model-picker-landscape-dark.png), [composer interaction](screenshots/10e-model-picker-composer.png). These are Robolectric renderings with fixture catalogs, not a connected-phone/production-Host acceptance run.

OnePlus device evidence with the real Host catalog: [model list](screenshots/phone-model-picker-list.png), [reasoning levels](screenshots/phone-model-picker-effort.png). Selecting the already-current model/effort closed the menu without changing the selection.

---

## 4. Sidebar (left)

Packages: `ui-sidebar`, `ui-workspace`, `ui-plugin-manager`, `ui-schedule`.

| Feature | Locale | Data | Phone decision |
|---|---|---|---|
| Brand / connection | — | pocket profiles | keep |
| 新会话 | `session.new` | `sessions.create` | keep |
| 工作区 section | `section.workspaces` 工作区 | session cwd groups | keep |
| Group / order / filter | `groupBy.*`, `orderBy.*`, `viewOptions.*` | workspace registry | **done** — drawer sliders menu + prefs |
| Search | `search.placeholder` 搜索会话名称 | `sessionController.search` | **done** — header search expands; `sessions.search` |
| Add workspace | `workspace.add` 添加工作区 | Official directory browser + `workspace.create` | **done** — desktop-style modal, editable breadcrumbs, two directory panes, hidden toggle, new folder and Cancel/Open; narrow screens scroll panes and wrap footer actions. Native-only Hosts retain path entry. |
| Rename / delete workspace | `rename.*`, `delete.workspace` | workspace controller | **done** — anchored icon menu contains Rename and red Delete workspace only; New session is a separate trailing row button. Delete confirmation preserves folders/session logs and explains Ungrouped placement. |
| Session row menu: fork / archive / pin | `menu.fork`, `menu.archiveSession`, `menu.pinSession` | `fork`; `workspace/{un,}archiveSession`; `workspace/{un,}pinSession` | **done** |
| 插件 | plugin-manager `panel` 插件 | pinwheel → native Plugins | **done** (drawer panellist under 新会话) |
| 自动化任务 | schedule `panel` 自动化任务 | clock → native Schedules | **done** (drawer panellist under 新会话) |
| 设置 | settings `trigger` 设置 | — | keep |

Empty: `empty.none` 暂无会话 / `empty.noMatches` 无匹配结果.

Workspace action buttons retain 48dp touch targets. Opening actions never toggles the group. Cancelling rename discards its draft before reopening; cancelling deletion leaves the workspace unchanged. Verified on a physical Android device with `WorkspaceDisplayInstrumentedTest`, plus release-APK menu inspection and direct New session navigation against the connected Host.

---

## 5. Right panel

Packages: `ui-sidebar-right` + files / terminal / browser / documentpreview / deliverables.  
**Desktop core guide launchers:** Workspace files, New terminal, Browser (orders 10/20/30); the optional session-inspector contributes 会话数据诊断 (order 60).
Phone adaptation: a full-height end panel (full width on phones, capped at 480dp on wider windows), shared by the guide, workspace files, document previews, terminal, browser and session diagnostics. The guide uses the official compass, 20dp capsule radius, 14dp gaps and title/description hierarchy. Back returns to the guide (or file list from preview); collapse closes the panel. Entries never require a manual conversation selection: Host-bound tools prepare an addressable blank Session in the current workspace when needed. Browser opens locally. Docking, split and floating windows remain desktop-only. The terminal entry reuses the current running Host PTY rather than creating a new shell on every visit.

Guide artwork is selected from the owning feature's registry, not from generic controls: `filesDefinition().guide[].icon` → `GuideArtworkFiles` (`guide-artwork.tsx`, fill `#FFBC4D`, stroke `#FFCD78`, width `1.97886`); terminal registration → `PluginArtworkTerminal` (`plugin-artwork.tsx`, stroke `#679EFE`, width `3.5`). Both keep the original 36×36 viewBox at 26dp display size and never receive theme tint. The compass is 56dp (`neutral-200` light / `neutral-700` dark); its title glyph is 16dp with `label-tertiary`. Expand/collapse use the mirrored Regular panel glyph at 15dp with `label-secondary`; Android retains larger touch targets.

| Entry | Locale | Data | Decision |
|---|---|---|---|
| 工作区文件 | `guide.title` 工作区文件 | `files.list` / `files.read` | feasible (done) |
| 终端 | `title` 终端 / guide `新建终端` | `terminal.*` | feasible (done) |
| 浏览器 | `guide.title` 浏览器 | Android WebView | **done** — desktop toolbar, navigation and explicit URL/title restore; no additional explanatory UI |
| 会话数据诊断 | `tab.title` 会话数据诊断 | Original follow/history records + Android chat projection | **done** — desktop presentation selector/count, table columns, type-filter popover, hierarchy, resizable JSON details and native Chat element picking |
| 文档预览 | documentpreview locales | Host file bytes + preview (opened via `openResource`, not a guide capsule) | **done** — `files.read` text paging + `files.readBytes` (base64) for image/PDF; `FilePreviewSheet` |
| Dock split / float | `dock.*` | desktop layout | **hide** (single adaptive end panel) |
| Deliverable / changes-review tabs | `deliverables.*` | events / turn payloads | feasible once events parsed |

Session diagnostics follows `experimental/session-inspector`'s `View.tsx`, `InspectorTable.tsx`, `TypeFilter.tsx`, `InspectorDetails.tsx` and locales. The compact toolbar contains the presentation selector, original crosshair, count, conditional earlier-history action and follow action. The horizontally scrollable table retains the 660dp minimum width and 64/190/65/100dp identity/type/T/S/UTC-time columns; Chat Group omits time. Filtering is explicitly confirmed, offers candidates, and preserves parent context. Detail trees use the desktop Object/Array labels, with raw JSON expanded and group children loaded on demand; the divider resizes the detail area within 15–80%. There are no added Copy/JSON/Tree controls, row-count suffix or explanatory banners.

Phone picking temporarily exposes the actual conversation, intercepts the pick without activating message actions or sending prompts, then returns to the selected diagnostic record. Back cancels picking. Selecting a diagnostic record positions its corresponding loaded Chat row. The inspected group objects remain the real Android projection, not fabricated desktop JavaScript objects: desktop runtime-object reference navigation and DOM hover overlays do not exist on Android. Raw stream chunks preserve their original indices and Turn/Step/block ancestry; live records that settle may change identity to the durable event.

**Not right-panel guide entries (desktop placement):**

| Surface | Desktop home | Locale | Phone |
|---|---|---|---|
| 后台任务 | **Session header** job list action | `job.list.aria` 后台任务 | beside chat/trajectory tabs; hidden for an empty session roster, otherwise shows live (`running` / `stopping`) count or retained total and opens `JobsSheet`; refreshes every 3s only while visible and connected; session/Host changes clear old state and errors show unavailable rather than stale counts |
| 任务 / 清单 (Todo) | **Conversation dock** `TodoPanel` (+ `todo_write` tool row) | `todo.title` 任务 | progress row above the composer, opening `TodoSheet` |

---

## 6. Settings

| Page | Locale | Decision |
|---|---|---|
| 通用设置 | `general.nav` 通用设置; language / appearance / fonts / transcript / collapse / busy-send / performance | **done with platform limits** — browser-verified separator rows; font-size pill with stacked arrows, external px and inline disclosure. Text/code/terminal family inputs commit on blur/Enter and resolve installed Android fonts with ordered fallback; expanded controls reset on remount. Host log uploads retain CAS/readback. Mobile-only preferences stay separate. |
| 模型 | settings-models | **done** — browser-verified trailing dashed add-provider entry and horizontal creation tabs, compact provider editor and ID/name/options/delete model rows. Per-model options expand in place; custom API field order and save/create actions match desktop. Revision-fenced writes retain drafts and confirm readback; key retry does not recreate configuration. No standalone credential page. |
| 内置插件 / 侧栏插件 | plugin-manager + inventory | **done with native limits** — Settings → Built-in plugins opens the independently loaded read-only inventory; sidebar Plugins retains native management. “Plugin interfaces” opens the original Host Web client, including installed custom React sections and other Web contributions without per-plugin APK adaptation. Host directory specs work; Android device file picking and desktop-native APIs remain unavailable. |
| 自动化任务 | ui-schedule | **done** — new conversation for creation; searchable catalog, precise rule/title/instruction editing, conflict-safe delete/history. No pause toggle (`inactive` = ended). |
| Agent 预设 | agent-preset | **done** — browser-verified built-in/custom cards, single-column phone and two-column wide layout, immediate default selection with CAS/readback, independent mode/usage guides and read-only YAML viewer. Creator entry stages a new Cordis draft without sending. Coding Tools visibility and custom display names follow official rules. |
| 打开配置文件 | `openDocument` | **hide** (local desktop editor) |
| Shell / 终端 limits | settings-shell | native fields over `plugins.settingsDescribe/settingsMutate` |
| Web search / subagent / agent-loop | respective settings namespaces | native configuration; bundle component settings retain secret-safe schema editing without an extra namespace directory |
| 连接 / 审批通知 | phone Connections + notify pref | keep |

Appearance values from `ui-theme` locales. Performance: `settings.performance.{compact,detailed}`.

Android is a controller of the same Host, not an independent settings store. Shared fields read the official namespaces: `ui-chat` (transcript/performance), `ui-theme` (appearance/fonts/sizes), `locale.preference`, `ui-conversation.busyEnter`, `ui-settings.enabled`, `permission.defaultPreset`, and `session-log-deepseek.enabled`. Writes use the observed namespace revision through `plugins.settingsMutate`, then read back the effective value. Desktop changes push `settings.changed` and update open mobile pages and renderers. Read-only/offline/conflicting or overridden writes remain failures; they do not save a phone-local value or replay onto another Host. Old phone preferences never upload over Host settings.

The missing-value transcript default follows Desktop (`standard`). Code wrapping and connection preferences remain device-specific. Collapse timing remains client-local and in-memory because the official desktop policy has no Host setting for it. Font names synchronize, but each device resolves its own installed fonts. Layout is always width-adaptive; no manual mode or persisted override remains. General settings uses the official row titles/descriptions without an extra “Mobile only” group or device/Host synchronization explanation.

Model settings use the desktop UI, not the mere existence of Host APIs, as their feature boundary. API keys stay write-only inside provider editors. Opening a card and applying changes refresh its Host snapshot; external configuration changes require reopening the card. Saving settings never changes the current session model.

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
| 插件 / 自动化任务 | **done** (drawer under 新会话 + Settings; pinwheel/clock icons from ui-primitives) | was hide |
| Drawer search / view options / add workspace | **done** (ui-workspace header icons + locales) | was needs-plugin / subset |
| Files / terminal / jobs / todo sheets | present | — |
| Browser / session diagnostics | present | native WebView / read-only loaded event window |
| Settings general rows | present | — |
| Language / appearance prefs | **done** (labels wired via L; en for new surfaces) | was feasible (partial) |
| Models / plugins / schedules | **done** (native Compose over official Host APIs) | Models restores default provider configuration; custom plugin cards are temporarily omitted |
| Open config file / local-path plugin install | hidden | hide (desktop OS chrome) |

---

## 10. Plugin RPC map (current → needed)

**Present:** `sessions.list/create/rename`, `prompt` (text/images/**files**), `cancel`, `follow`/`unfollow`, `history.page`, `models.*`, `permissions.*`, `presets.list`, `approval.*`, `devices.*`, `pairing.*`, `commands.execute`, `session.export`, `files.list/read`, `jobs.list/kill`, `terminal.*`, `sessions.fork`.

**Added in this pass (parity-ops):** `sessions.search`, `workspace.state` / `archiveSession` / `unarchiveSession` / `pinSession` / `unpinSession`, `feedback.get` / `feedback.set`, `references.files` / `references.sessions`, `session.attachment`, `attachments.upload`, `userQuestions.answer` (+ push `user-question` / `user-question.closed`).

**Settings ops:** Native Models uses `models.providers` / `models.providerGet` / `models.settingsMutate` / `models.discover` with write-only `credentials.*`, revision checks, draft retention and readback. Built-in Plugins independently uses `plugins.inventory`; its connection-bound load effect handles capability publication, invalidation and manual refresh without losing valid rows or accepting stale replies. Default settings do not enter WebView. Plugins retains its separate optional full-client entry via `ui.open` / `ui.close`; those device/connection-bound Web sessions keep Host process tokens/cookies server-side and enforce path/Origin/CSP restrictions.

**Still limited / unfinished:**

| Gap | Notes |
|---|---|
| Folder attach | desktop-only (**hide**) |
| Appearance theme tokens | **done** — full `--dsw-alias-*` / `--dsw-specific-*` from ui-theme design-platform.css @ d743267 → `DswColors` + theme-aware `T.*`; Appearance Light/Dark/System drives chrome |
| Connections / pair / settings chrome | **done** — `Conn` aliases `T.*` (no Tokyo Night); settings cards use `--dsw-alias-settings-card-*`; pair sheet = PocketSheet + primary CTA |
| Document preview (text / image / PDF) | **done** — tap file path/link in chat or trajectory → `FilePreviewSheet` via `files.read` / `files.readBytes`; copy uses common `copy`/`copied`; PDF via `PdfRenderer` (no GMS) |
| Wide / foldable layout | **done** — automatically uses two panes at ≥760dp, reserving 480dp for the conversation; narrower screens use the drawer. No Auto/Phone/Desktop preference. |
| Drawer tap / safe-area | **done** — session row closes drawer; workspace collapse does not; drawer session menu is `DropdownMenu` (first tap not eaten); sheets/nav use system bar insets |
| OS workspace picker / open-config / local-path plugin install | **hide** (desktop OS chrome) |
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
| Turn usage pill | Icon + usage text in detailed mode | Not on phone action row | **Hidden** — no separate turn-usage text row; composer statistics and trajectory usage details remain available |
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
