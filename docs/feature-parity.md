# Feature parity: mobile vs DeepSeek Harness 0.2.1-alpha.2

Audit of every Android UI entry that was a stub / no-op / grayed placeholder.
Desktop reference: `deepseek-harness` @ `d743267` (0.2.1-alpha.2).
Plugin surface: `dsh-plugin-pocket` WebSocket ops (+ optional Cordis injects).

**Decision rules**
- Desktop UI is the product source of truth. A Host API alone does not justify a new phone feature or settings page.
- **implement** — can work on phone via existing follow/session data and/or new plugin RPC over in-process host services (including the **host-machine PTY / terminal**, which is useful remotely).
- **hide** — surfaces requiring local desktop chrome (open-in-editor, OS directory picker). The browser is a phone-local WebView; a plugin spec can still name a host directory, never Android storage.

| Feature | Desktop behavior (0.2.1-alpha.2) | Decision | Status | Notes |
|---|---|---|---|---|
| 轨迹 tab | Session header Chat/Trajectory; swimlane + ledger + tabbed inspector (概述/参数/结果/Schema/计时) | implement | done | Source-driven port of `@deepseek-ai/dsh-client-ui-trajectory@0.2.1-alpha.2` (`d743267`): all 7 cell kinds, desktop locales, detail tabs, swimlane; see `docs/trajectory-parity.md`. |
| Right-panel toggle | Opens right sidebar launcher | implement | done | Full-height end panel; official compass/capsule guide. Entries do not require manually selecting a conversation. Host-bound tools prepare a blank Session in the current workspace when needed; the browser opens locally without a Session. |
| → 工作区文件 | `workspaceFiles.list/read/stat/changes` | implement | done | `FilesSheet`: compact scrollable listing, bounded parent navigation, reload/loading/error/empty states; document preview shares the end-panel chrome and returns to the current directory. |
| → 终端 | Host `terminalController` PTY create/follow/write/resize | implement | done | Full-height PTY with official theme aliases, independent font size, IME and helper keys; retry reattaches the existing shell. Leaving detaches; termination is a separate confirmed action. |
| → 浏览器 | Client iframe / Electron webview | implement | done | Android WebView with the desktop's single navigation toolbar, address entry, back/forward/reload/external open and explicit restore. No added explanatory banners. |
| → 会话数据诊断 | Raw log / grouped chat inspection | implement | done | Original follow events and observed stream frames; current Android chat groups; type filter, earlier history, follow latest and expandable/copyable JSON details. Read-only inspection, no additional Host RPC. |
| 后台任务 | Session-header `job-controller` list/observe/kill | implement | done | Header hidden for an empty session roster; shows live count or retained total. Foreground-only 3s refresh, session/Host-scoped state, explicit unavailable status on query failure. Task detail retains/live-streams output with byte offsets and loss notices. Termination requires separate confirmation. |
| 任务 (清单) | conversation TodoPanel (`todo/write` / projections.todos) | implement | done | Progress row above composer → `TodoSheet`; not a right-panel guide entry. |
| Message copy / clock / branch | IconActions + `sessions.fork` | implement | done | Desktop locale; plugin `sessions.fork`. |
| Running status / process groups | Host Turn clock, live activity, nested process disclosures | implement | done | Official “深度求索中” with Host start time, whale/shimmer theme; groups preserve reply boundaries and obey the shared presentation policy. Completed-Turn controls retain the final answer. |
| Turn error / max-tokens / stopped | chat notices | implement | done | Official `message.*` failure/stopped/retry copy. |
| Full inventory | — | — | see [desktop-parity.md](desktop-parity.md) | Gap matrix: feasible / needs-plugin / hide. |
| Top-bar 智能体团队 | Experimental agent-team UI + subagent lineage via `parentSessionId` | implement | done | List parent + children from `sessions.list`; switch session. |
| Top-bar Agent 预设 | Create-time preset; mid-session view | keep | done | Editable on new chat; view-only in session. |
| Top-bar ⋯ 重命名 | `sessions.rename` | keep | done | |
| Top-bar ⋯ 会话统计 | From projections / usage | keep | done | |
| Top-bar ⋯ 下载 Session 日志 | `GET /api/session.export` ZIP | implement | done | Plugin `session.export` ZIP (JSONL inside); share sheet. |
| Top-bar ⋯ 反馈 | In-app dialog → `/feedback <text>` | implement | done | `FeedbackSheet` + `commands.execute`. |
| Composer + → 文件 | Client file picker | implement | done | Android image picker → `prompt` images. |
| Composer + → 目标 | Goal command and persistent GoalBar | implement | done | Dynamic Host command identity creates the goal; projected goal state exposes edit/pause/resume/clear with id/revision CAS. |
| Composer + → 计划 | `/plan` | implement | done | `commands.execute` `/plan`. |
| Composer + → 反馈 | Same as ⋯ 反馈 | implement | done | Shared sheet. |
| Composer + → 压缩 | `/compact` | implement | done | `commands.execute` `/compact`. |
| Composer + → 权限 / 模型 | Live permission catalog / model catalog | implement | done | Host `options` drive the picker, including `auto` only when registered; full-access/auto require risk acknowledgement. Composer uses an icon-only permission trigger to preserve model-name width. |
| Composer model picker | Two-level Model / Effort menu | implement | done | Anchored compact popup; no redundant model logos/descriptions; stable provider groups, fuzzy search above four models, Host effort names/defaults, pending/error feedback and success-only close. `modelSelection.next` / catalog default synchronize the current selection. |
| Composer + → 下载日志 | Session export | implement | done | Same `session.export`. |
| Composer statistics dock | Speed / cache hit / context pressure | implement | done | `sessionStats`, `tokenUsage`, `contextPressure`, `contextBreakdown` snapshots and live projections; Compact defaults to the three readings, Detailed exposes counts and dialogs. Unknown metrics stay absent, real zero stays visible. |
| Drawer 插件 | Official configuration, optional capabilities, installed packages | implement | done | Native grouped list, runtime component switches, inspection/install/cancel/uninstall, registry and diagnostics. |
| Drawer 自动化任务 | Search/filter catalog, rule editing and delivery records | implement | done | New task opens a new conversation, matching the desktop; no separate delay-only creation form. |
| Drawer 会话 header | Search / view options / add workspace | implement | done | Local title/workspace matching plus Host content search and snippets. Registration uses real Workspace entities; remote directory browsing is exposed only for a Host `browse` picker, otherwise full-path input remains available. |
| Drawer 工作区 / 会话 | Workspace entities, nested paths and session menus | implement | done | Entity IDs prevent same-name directory merging; normal forks remain visible, subagents are distinguished by origin. Rename/fork/archive/restore follow Host outcomes. Manual order and collapsed state are stored per Host. |
| Drawer footer connection | Settings row + remote identity | mobile adaptation | done | Settings stays on the left; the remote machine name is the only text on the right, without an address/port subtitle. The connection indicator retains its live status. Clicking the name opens connection management, where connection details remain available. Header no longer repeats it. |
| New-chat 工作区 chip | Workspace picker | implement | done | Select/register real Host workspaces and create sessions with `workspaceId`; never open a Host-native OS picker remotely. |
| New-chat / settings Agent 预设 | Host roster / default / composition | implement | done | Host names/descriptions, default selection with revision CAS, broken-state details and read-only YAML composition. |
| Settings → 通用设置 | Locale, theme, font, transcript, collapse, busy-send, performance | implement | done | Host settings mirror with revision-fenced writes, effective-value readback and live desktop updates; device-specific settings remain separate. |
| Settings → 模型 | Provider list, inline editor and API-key configuration | implement | done | Single expanded card, collapsed customized settings, native model fields/input types, restore defaults, catalog/custom creation and discovery selection. No standalone credential manager or JSON model editor. |
| Settings → 插件 | Plugin manager | implement | done with native limits | Official Host `ChangeResult` outcomes, install cancellation/reconciliation and build-script approval; native settings with revision CAS, redacted secret-safe advanced edits. Third-party React configuration slots remain Web-only. |
| Settings → 自动化任务 | Schedule catalog, edits and delivery history | implement | done | One-shot/interval/daily/weekdays/weekly/cron edits with time zone, exact expected-record CAS, history paging, confirmed delete, original conversation link. No pause/run-now invention. |
| Settings → 内置插件 | Plugin inventory | implement | done | Merged into Plugins page (`plugins.inventory` / listPlugins). |
| Settings → Agent 预设 | Roster, default and composition | implement | done | `presets.settings/read`; default changes use the official settings controller with observed revision. |
| Settings → 打开配置文件 | Local editor | hide | done | Row removed. |
| Settings → 连接 / 审批通知 | Profiles + notify flag | keep | done | |
| General → 权限 / 繁忙发送 | Default permission; busyEnter | keep | done | Shared `permission.defaultPreset` / `ui-conversation.busyEnter`; choices use Host `defaultOptions`. Auto is current-session-only. |
| General → 语言 | `locale.preference` | implement | done | Shared Host preference; open pages react to desktop changes. |
| General → 外观 | `ui-theme.preference` | implement | done | Official Light / Dark / System cards; shared Host changes repaint the active theme. |
| General → 字号大小 | Independent conversation/code/terminal font sizes | implement | done | Shared Host values update transcript, code and terminal consumers. |
| General → 工作步骤展示 | `ui-chat.transcriptView` | implement | done | Shared compact/standard/detailed/verbose setting; missing value follows Desktop Standard. |
| General → 工作步骤收起时机 | Client-only collapse timing | implement | done | Observable in-memory state for the current client instance, matching official policy lifetime. |
| General → 性能与用量 | `ui-chat.performanceUsage` | implement | done | Compact shows decode tok/s and cache hit; Detailed adds whole-log counts/token totals and breakdown dialogs. Context percentage remains independently available. |
| 扫码配对 | Camera + deep link | — | elsewhere | PR #10; untouched. |
| Desktop plugin account-menu | Account menu placement | — | elsewhere | PR #9; untouched. |
| Remote transport | M3 | — | elsewhere | Tailscale / LAN host:port only; iroh removed from phone path. |

## Plugin RPC additions

| Op | Host inject | Purpose |
|---|---|---|
| `commands.list` / `execute` | `commands` + `agents` | Agent-scoped dynamic command directory and official command execution |
| `session.export` | `sessionController.page` + fflate | ZIP bytes for phone share/save |
| `files.list` / `files.read` | `workspaceFiles` + `sessions` | Right-panel files |
| `jobs.list` / `jobs.follow` / `unfollow` / `jobs.kill` | `jobController` | Task rows, live output subscription with shared cancellation, and confirmed termination |
| `terminal.list` / `create` / `follow` / `write` / `resize` / `close` | `terminalController` + `agents` | Host PTY; `follow` pushes `{ev:"terminal"}` |
| `models.providers` / `providerGet` / `settingsMutate` / `discover` | `llm` + `settings`/`settingsController` + `credentials` | Settings → 模型 |
| `plugins.list` / `setEnabled` / `inventory` / `inspect` / `registries` / `install` / `cancelInstall` / `waitForInstall` / `uninstall` | `pluginManager` (+ inventory) | Official package management; unavailable optional methods are not advertised. |
| `plugins.settingsDescribe` / `settingsMutate` | `settingsController` | Redacted namespaces/schema/secrets and required observed revision; preserve Host conflict errors. |
| `schedule.catalog` / `list` / `create` / `update` / `delete` / `history` | `schedule` | Official task records and business outcomes; Android creation navigates to a conversation, editing uses `update`. |
| `credentials.set` / `unset` | `credentials` | Write-only API keys inside model provider settings; no standalone credential manager. |
| `workspace.state` / `create` / `rename` / `delete` / ordering / archive / pin | `workspaceController` | Real entities and session membership; preserve archive activity-refusal details |
| `workspace.directoryList` | `directoryPicker` + `directoryPickerController` | Advertised only when the composed Host picker has `browse` capability |
| `goals.get` / `edit` / `pause` / `resume` / `clear` | `goals` + `agents` | Official goal projection and id/revision-guarded mutations |
| `presets.settings` / `read` | `agentPresets` + `settingsController` | Full roster, default namespace and read-only composition document |

Existing ops kept: sessions.*, prompt, cancel, follow/unfollow, models.catalog/select, permissions.*, presets.list, approval.*, devices.*, pairing.*.

## Mobile adaptation and limits

Source of truth: the running Mac DSH Web and official `ui-plugin-manager` / `ui-schedule`, not a second mobile task engine. `dsh-mobile` is a reference for narrow-screen navigation only.

- List → detail navigation replaces desktop side-by-side panes; controls remain touch-sized.
- Plugin installation is a host operation, including npm, Git, tarball and host-path specs. Installing code or approving package scripts grants the host user's permissions.
- Failed writes retain drafts. Transport success is not application success: plugin `failed` / `overridden` / `restart-required` and schedule `schedule_conflict` remain distinct.
- The automation and plugin catalogs refresh on official Host change events; manual refresh remains available. Saved delivery history is a durable inbox receipt, not proof that the model completed the task.
- Native Android cannot execute third-party React slots. Exposed Host settings remain editable; custom plugin interfaces still require Web.
- Browser requests originate on Android. HTTP(S) only; no DSH credential or JavaScript bridge, file/content access, certificate bypass, permissions or downloads. WebView site storage is shared by the app, not isolated per conversation. Saved URLs/titles restore only on user action.
- Session diagnostics retain unredacted original data in the loaded window; copy only material safe to share. Group inspection reflects Android `ChatRow` / `ProcessStep` / `Item`, not desktop JavaScript object identity or DOM picking.
- Images support full-screen zoom/pan; PDFs render all pages on demand with bounded bitmaps and asynchronous cleanup. Markdown, CSV/TSV and static HTML have dedicated previews; HTML cannot execute scripts or access network/local resources. Office binary formats remain explicitly unsupported.
- Approval/question panels take over the composer. Per-question drafts survive navigation; failed submission keeps pending content. Dismissing a question panel is not reported as cancelling a Host request.

### Icons

Permission controls use the official `PermissionIconReadOnlyRegular`,
`PermissionIconWorkspaceWriteRegular`, `PermissionIconFullAccessRegular` and
`PermissionIconAutoReviewRegular` paths. Unknown Host presets have no invented
glyph. The composer and permission menu share the same mapping.

Plugin cards preserve the official fixed-color artwork and gradients rather
than tinting them into generic navigation icons. Built-in configuration art is
native; bundle icons use the Host's SVG data URI with no network loading.

Model commands use `IconData`, feedback uses `IconPaperPlane`, the terminal
entry uses the official `TerminalIcon` prompt, and settings navigation uses
the desktop's Medium model/personalization glyphs and clock. Compact retains
the official 0.35-opacity ring; generic files use the document glyph, not Plan.

### Statistics and permission state

- Speed is `sessionStats.decodeTokens / (decodeMs / 1000)`, not output tokens divided by whole-turn time. Without that projection, only loaded timed steps contribute to a clearly scoped fallback.
- Cache share uses all three prompt-side buckets: uncached input, cache reads, cache writes. Context uses Host projected pressure (or measured pressure) against the current Host context window.
- Projection watermarks are separate from the follow event cursor. Paging, duplicate frames and reconnect snapshots must not double-count totals or overwrite newer readings.
- Follow subscribers receive selected Host projection changes after their initial snapshot/resume. Permission-catalog invalidations trigger a fresh read; connection changes discard the previous Host's choices.
- Auto review does not use a sandbox. Its enablement confirmation explains experimental per-call model review and additional token use. No auto option is invented when the Host plugin is absent.
- RPC reply deadlines are ordinary `timeout` failures; caller cancellation remains cancellation. Permission failures release retry controls, and a catalog failure does not prevent session resubscription.
- Session changes revoke the old subscription before awaiting Host cleanup. Subscription generations fence late acknowledgements, and projection frames must belong to the selected session before updating values or sequence watermarks.
- Window-scoped fallback tool time uses matched `tool/call` and `tool/result` timestamps, including pairs split across history pages. Tool declaration time, calls that never execute and PTC dispatch duplication do not contribute.

### Analysis progress

Reasoning uses a compact Think-icon row with a single-line preview and independent Markdown expansion. Completed process groups preserve Host step order instead of displaying all reasoning before all tools; tool rows show localized names and recorded-result summaries. Completed-Turn duration comes exclusively from Host turn start/end, never reasoning time, summed tool time or the phone's local clock. Missing boundaries, stopped turns and errors do not fabricate elapsed time.

Live reasoning uses the official “正在分析请求 / Analyzing request” label with a single-line preview; collapsed live groups retain that preview. The separate waiting line is omitted while live reasoning is already shown, avoiding duplicate progress indicators.
