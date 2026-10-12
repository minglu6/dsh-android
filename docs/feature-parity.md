# Feature parity: mobile vs DeepSeek Harness 0.2.1-alpha.2

Audit of every Android UI entry that was a stub / no-op / grayed placeholder.
Desktop reference: `deepseek-harness` @ `d743267` (0.2.1-alpha.2).
Plugin surface: `dsh-plugin-pocket` WebSocket ops (+ optional Cordis injects).

**Decision rules**
- **implement** — can work on phone via existing follow/session data and/or new plugin RPC over in-process host services (including the **host-machine PTY / terminal**, which is useful remotely).
- **hide** — only surfaces that require a **local desktop app/editor/browser chrome** (open-in-editor, embedded browser webview, plugin install manager, schedule CRUD, credentials UI) and have no meaningful remote substitute.

| Feature | Desktop behavior (0.2.1-alpha.2) | Decision | Status | Notes |
|---|---|---|---|---|
| 轨迹 tab | Session header Chat/Trajectory; turn/step tree with input/output/think/time; detail sheet | implement | done | Client fold of chat `Item`s (`buildTrajectory` / `TrajectoryView`). |
| Right-panel toggle | Opens right sidebar launcher | implement (partial) | done | Shown when `files.*` / `terminal.*` / `jobs.*` ops are present. |
| → 工作区文件 | `workspaceFiles.list/read/stat/changes` | implement | done | Plugin `files.list` / `files.read`; `FilesSheet`. |
| → 终端 | Host `terminalController` PTY create/follow/write/resize | implement | done | Plugin `terminal.*` + `TerminalSheet` (VT100 grid, Meslo Nerd Font, Tokyo Night; remote shell on the desktop). |
| → 浏览器 | Client iframe / Electron webview only (no Host browse API) | hide | done | Local browser chrome — not a Host remote surface. |
| → 后台任务 | `job-controller` list/observe/kill | implement | done | Plugin `jobs.list` / `jobs.kill`; `JobsSheet`. |
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
| Drawer 插件 | Plugin manager | hide | done | Row removed. |
| Drawer 自动化任务 | Schedule panel | hide | done | Row removed. |
| Drawer 工作区 / 会话 | Group by cwd; open session | keep | done | |
| New-chat 工作区 chip | Workspace picker | implement (limited) | done | Known session `cwd`s only; no OS directory dialog. |
| New-chat / settings Agent 预设 | Preset roster | keep | done | |
| Settings → 通用设置 | Locale, theme, font, transcript, collapse, busy-send, performance | implement | done | Phone-local prefs; official labels/values. |
| Settings → 模型 | Provider / credentials configuration | hide | done | Row removed. |
| Settings → 内置插件 | Plugin manager | hide | done | Row removed. |
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

Existing ops kept: sessions.*, prompt, cancel, follow/unfollow, models.*, permissions.*, presets.list, approval.*, devices.*, pairing.*.
