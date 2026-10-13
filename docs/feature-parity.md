# Feature parity: mobile vs DeepSeek Harness 0.2.1-alpha.2

Audit of every Android UI entry that was a stub / no-op / grayed placeholder.
Desktop reference: `deepseek-harness` @ `d743267` (0.2.1-alpha.2).
Plugin surface: `dsh-plugin-pocket` WebSocket ops (+ optional Cordis injects).

**Decision rules**
- **implement** — can work on phone via existing follow/session data and/or new plugin RPC over in-process host services (including the **host-machine PTY / terminal**, which is useful remotely).
- **hide** — surfaces requiring local desktop chrome (open-in-editor, embedded browser, OS directory picker). A plugin spec can still name a host directory; it never refers to Android storage.

| Feature | Desktop behavior (0.2.1-alpha.2) | Decision | Status | Notes |
|---|---|---|---|---|
| 轨迹 tab | Session header Chat/Trajectory; swimlane + ledger + tabbed inspector (概述/参数/结果/Schema/计时) | implement | done | Source-driven port of `@deepseek-ai/dsh-client-ui-trajectory@0.2.1-alpha.2` (`d743267`): all 7 cell kinds, desktop locales, detail tabs, swimlane; see `docs/trajectory-parity.md`. |
| Right-panel toggle | Opens right sidebar launcher | implement (partial) | done | Shown when `files.*` / `terminal.*` / `jobs.*` ops are present, or todos exist. |
| → 工作区文件 | `workspaceFiles.list/read/stat/changes` | implement | done | Plugin `files.list` / `files.read`; `FilesSheet`. |
| → 终端 | Host `terminalController` PTY create/follow/write/resize | implement | done | Plugin `terminal.*` + `TerminalSheet` (VT100 grid, Meslo Nerd Font, Tokyo Night; remote shell on the desktop). |
| → 浏览器 | Client iframe / Electron webview only (no Host browse API) | hide | done | Local browser chrome — not a Host remote surface. |
| → 后台任务 | `job-controller` list/observe/kill | implement | done | Plugin `jobs.list` / `jobs.kill`; `JobsSheet`. |
| → 任务 (清单) | conversation TodoPanel (`todo/write` / projections.todos) | implement | done | `TodoSheet`; official `todo.*` strings. |
| Message copy / clock / branch | IconActions + `sessions.fork` | implement | done | Desktop locale; plugin `sessions.fork`. |
| Turn error / max-tokens / stopped | chat notices | implement | done | Official `message.*` failure/stopped/retry copy. |
| Full inventory | — | — | see [desktop-parity.md](desktop-parity.md) | Gap matrix: feasible / needs-plugin / hide. |
| Top-bar 智能体团队 | Experimental agent-team UI + subagent lineage via `parentSessionId` | implement | done | List parent + children from `sessions.list`; switch session. |
| Top-bar Agent 预设 | Create-time preset; mid-session view | keep | done | Editable on new chat; view-only in session. |
| Top-bar ⋯ 重命名 | `sessions.rename` | keep | done | |
| Top-bar ⋯ 会话统计 | From projections / usage | keep | done | |
| Top-bar ⋯ 下载 Session 日志 | `GET /api/session.export` ZIP | implement | done | Plugin `session.export` ZIP (JSONL inside); share sheet. |
| Top-bar ⋯ 反馈 | In-app dialog → `/feedback <text>` | implement | done | `FeedbackSheet` + `commands.execute`. |
| Composer + → 文件 | Client file picker | implement | done | Android image picker → `prompt` images. |
| Composer + → 目标 | `/goal` | implement | done | `GoalSheet` + `commands.execute`. |
| Composer + → 计划 | `/plan` | implement | done | `commands.execute` `/plan`. |
| Composer + → 反馈 | Same as ⋯ 反馈 | implement | done | Shared sheet. |
| Composer + → 压缩 | `/compact` | implement | done | `commands.execute` `/compact`. |
| Composer + → 权限 / 模型 | Permission presets / model catalog | keep | done | |
| Composer + → 下载日志 | Session export | implement | done | Same `session.export`. |
| Drawer 插件 | Official configuration, optional capabilities, installed packages | implement | done | Native grouped list, runtime component switches, inspection/install/cancel/uninstall, registry and diagnostics. |
| Drawer 自动化任务 | Search/filter catalog, rule editing and delivery records | implement | done | New task opens a new conversation, matching the desktop; no separate delay-only creation form. |
| Drawer 会话 header | Search / view options / add workspace | implement | done | Desktop ui-workspace icons + locales; view options sheet; add workspace via host path (no OS picker). |
| Drawer 工作区 / 会话 | Group by cwd; open session | keep | done | groupBy / orderBy / archivedFilter prefs mirror desktop. |
| New-chat 工作区 chip | Workspace picker | implement (limited) | done | Known session `cwd`s only; no OS directory dialog. |
| New-chat / settings Agent 预设 | Preset roster | keep | done | |
| Settings → 通用设置 | Locale, theme, font, transcript, collapse, busy-send, performance | implement | done | Phone-local prefs; official labels/values. |
| Settings → 模型 | Provider / credentials configuration | implement | done | `models.providers` + credential set/unset; Compose detail. |
| Settings → 插件 | Plugin manager | implement | done with native limits | Official Host `ChangeResult` outcomes, install cancellation/reconciliation and build-script approval; native settings with revision CAS, redacted secret-safe advanced edits. Third-party React configuration slots remain Web-only. |
| Settings → 自动化任务 | Schedule catalog, edits and delivery history | implement | done | One-shot/interval/daily/weekdays/weekly/cron edits with time zone, exact expected-record CAS, history paging, confirmed delete, original conversation link. No pause/run-now invention. |
| Settings → 凭证 | credentials describe/set/unset (masked) | implement | done | `credentials.*`; values never returned. |
| Settings → 内置插件 | Plugin inventory | implement | done | Merged into Plugins page (`plugins.inventory` / listPlugins). |
| Settings → Agent 预设 | Roster / info | keep | done | |
| Settings → 打开配置文件 | Local editor | hide | done | Row removed. |
| Settings → 连接 / 审批通知 | Profiles + notify flag | keep | done | |
| General → 权限 / 繁忙发送 | Default permission; busyEnter | keep | done | |
| General → 语言 | `locale.preference` | implement | done | Phone-local `zh` / `en` preference. |
| General → 外观 | `ui-theme.preference` | implement | done | Light / dark / system → Material scheme. |
| General → 字号大小 | `ui-theme.fontSize` | implement | done | Transcript body size. |
| General → 工作步骤展示 | `ui-chat.transcriptView` | implement | done | compact/standard/detailed/verbose. |
| General → 工作步骤收起时机 | Client-only collapse timing | implement | done | Preference stored (policy hook for later fold animation). |
| General → 性能与用量 | `ui-chat.performanceUsage` | implement | done | Footer density. |
| 扫码配对 | Camera + deep link | — | elsewhere | PR #10; untouched. |
| Desktop plugin account-menu | Account menu placement | — | elsewhere | PR #9; untouched. |
| Remote transport | M3 | — | elsewhere | Tailscale / LAN host:port only; iroh removed from phone path. |

## Plugin RPC additions

| Op | Host inject | Purpose |
|---|---|---|
| `commands.execute` | `commands` + `agents` | `/compact`, `/plan`, `/goal …`, `/feedback …` |
| `session.export` | `sessionController.page` + fflate | ZIP bytes for phone share/save |
| `files.list` / `files.read` | `workspaceFiles` + `sessions` | Right-panel files |
| `jobs.list` / `jobs.kill` | `jobController` | Right-panel background tasks |
| `terminal.list` / `create` / `follow` / `write` / `resize` / `close` | `terminalController` + `agents` | Host PTY; `follow` pushes `{ev:"terminal"}` |
| `models.providers` / `providerGet` / `settingsMutate` / `discover` | `llm` + `settings`/`settingsController` + `credentials` | Settings → 模型 |
| `plugins.list` / `setEnabled` / `inventory` / `inspect` / `registries` / `install` / `cancelInstall` / `waitForInstall` / `uninstall` | `pluginManager` (+ inventory) | Official package management; unavailable optional methods are not advertised. |
| `plugins.settingsDescribe` / `settingsMutate` | `settingsController` | Redacted namespaces/schema/secrets and required observed revision; preserve Host conflict errors. |
| `schedule.catalog` / `list` / `create` / `update` / `delete` / `history` | `schedule` | Official task records and business outcomes; Android creation navigates to a conversation, editing uses `update`. |
| `credentials.list` / `describe` / `set` / `unset` / `deleteRecord` | `credentials` | Settings → 凭证 (masked) |

Existing ops kept: sessions.*, prompt, cancel, follow/unfollow, models.catalog/select, permissions.*, presets.list, approval.*, devices.*, pairing.*.

## Mobile adaptation and limits

Source of truth: the running Mac DSH Web and official `ui-plugin-manager` / `ui-schedule`, not a second mobile task engine. `dsh-mobile` is a reference for narrow-screen navigation only.

- List → detail navigation replaces desktop side-by-side panes; controls remain touch-sized.
- Plugin installation is a host operation, including npm, Git, tarball and host-path specs. Installing code or approving package scripts grants the host user's permissions.
- Failed writes retain drafts. Transport success is not application success: plugin `failed` / `overridden` / `restart-required` and schedule `schedule_conflict` remain distinct.
- The automation catalog refreshes while open; saved delivery history is a durable inbox receipt, not proof that the model completed the task.
- Native Android cannot execute third-party React slots. Exposed Host settings remain editable; custom plugin interfaces still require Web.

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
