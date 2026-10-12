# Feature parity: mobile vs DeepSeek Harness 0.2.1-alpha.2

Audit of every Android UI entry that was a stub / no-op / grayed placeholder.
Desktop reference: `deepseek-harness` @ `d743267` (0.2.1-alpha.2).
Plugin surface: `dsh-plugin-pocket` WebSocket ops (+ optional Cordis injects).

**Decision rules**
- **implement** — can work on phone via existing follow/session data and/or new plugin RPC over in-process host services.
- **hide** — needs local desktop chrome (editor, PTY window, in-app browser webview, Host cookie download to Electron) and has no meaningful mobile substitute.

| Feature | Desktop behavior (0.2.1-alpha.2) | Decision | Status | Notes |
|---|---|---|---|---|
| 轨迹 tab | Session header Chat/Trajectory; turn/step tree with input/output/think/time; detail sheet | implement | todo | Fold follow history + live events client-side (port simplified trajectory layout from `ui-trajectory`). Needs `history.page` for older turns. |
| Right-panel toggle | Opens right sidebar launcher | implement (partial) | todo | Keep toggle; sheet only lists implementable panels. |
| → 工作区文件 | `workspaceFiles.list/read/stat/changes` | implement | todo | Plugin `files.list` / `files.read`; full-screen browse on phone. |
| → 终端 | `terminal-controller` PTY create/follow/write/resize | hide | todo | API exists but interactive PTY UX is desktop-native; not meaningful on phone. |
| → 浏览器 | Client iframe / Electron webview only (no Host browse API) | hide | todo | No remote surface to bridge. |
| → 后台任务 | `job-controller` list/observe/kill | implement | todo | Plugin `jobs.list` / `jobs.kill`; full-screen list. |
| Top-bar 智能体团队 | Experimental agent-team UI + subagent lineage via `parentSessionId` | implement | todo | Keep sheet: list parent + children from `sessions.list`; switch session. No spawn/team-board without experimental Host projection. |
| Top-bar Agent 预设 | Create-time preset; mid-session view | keep | done | Editable on new chat; view-only in session (Host constraint). |
| Top-bar ⋯ 重命名 | `sessions.rename` | keep | done | |
| Top-bar ⋯ 会话统计 | From projections / usage | keep | done | |
| Top-bar ⋯ 下载 Session 日志 | `GET /api/session.export?sessionId&includeDescendants=true` ZIP | implement | todo | Plugin `session.export` builds ZIP in-process (`session-log-export` archive) and returns base64; app shares/saves file. |
| Top-bar ⋯ 反馈 | Opens in-app feedback dialog → `sessionFeedback` / `/feedback <text>` | implement | todo | Sheet + `commands.execute` `/feedback …` (or `sessionFeedback.record` when injected). |
| Composer + → 文件 | Client-only file picker (`inputHub.pickFiles`); not a Host command | implement | todo | Android picker → `prompt` images / attachments. |
| Composer + → 目标 | `/goal` (`dsh-command-goal`): show/create/edit/pause/resume/clear | implement | todo | Goal sheet + `commands.execute`. |
| Composer + → 计划 | `/plan` toggles plan mode | implement | todo | `commands.execute` `/plan` / `/plan off`. |
| Composer + → 反馈 | Same as ⋯ 反馈 | implement | todo | Shared sheet. |
| Composer + → 压缩 | `/compact` → `ctx.compaction.compactNow` | implement | todo | `commands.execute` `/compact`. |
| Composer + → 权限 / 模型 | Permission presets / model catalog | keep | done | |
| Composer + → 下载日志 | Same as session export | implement | todo | Same `session.export` op. |
| Drawer 插件 | Plugin manager panel (`pluginManager` / `pluginInventory`) | hide | todo | Install/toggle is desktop Host lifecycle; read-only inventory is not the official entry UX. |
| Drawer 自动化任务 | Schedule panel (`schedule.catalog/list/…`) | hide | todo | Full schedule CRUD + task detail is a large desktop surface; no stub list. |
| Drawer 工作区 / 会话 | Group by cwd; open session | keep | done | |
| New-chat 工作区 chip | Workspace picker / create | implement (limited) | todo | Keep listing known `cwd`s from sessions; no OS directory dialog. Optional later: `workspace/*` list via plugin. |
| New-chat / settings Agent 预设 | Preset roster | keep | done | `presets.list`. |
| Settings → 通用设置 | Locale, theme, font, transcript, collapse, busy-send, performance | implement | todo | Phone-local prefs matching official labels/values (collapse is client-memory on desktop too). Do not open Host settings documents. |
| Settings → 模型 | Provider / credentials configuration | hide | todo | Not the session model picker; needs credentials UI on Host. |
| Settings → 内置插件 | Plugin manager | hide | todo | Same as drawer. |
| Settings → Agent 预设 | Roster / info | keep | done | View-only sheet. |
| Settings → 打开配置文件 | `settings/openSettingsDocument` → local editor | hide | todo | Desktop-only. |
| Settings → 连接 / 审批通知 | Profiles + FCM-less local notify flag | keep | done | |
| General → 权限 / 繁忙发送 | Default permission; `busyEnter` queue/steer | keep | done | |
| General → 语言 | `locale.preference` | implement | todo | Phone UI language only (`zh` / `en`). |
| General → 外观 | `ui-theme.preference` light/dark/system | implement | todo | Phone theme. |
| General → 字号大小 | `ui-theme.fontSize` | implement | todo | Phone transcript font. |
| General → 工作步骤展示 | `ui-chat.transcriptView` compact/standard/detailed/verbose | implement | todo | Drives tool-row density. |
| General → 工作步骤收起时机 | Client-only: 回答结束后 / 下次有新消息时 | implement | todo | |
| General → 性能与用量 | `ui-chat.performanceUsage` compact/detailed | implement | todo | Footer stats density. |
| 扫码配对 | Camera + `dshpocket://` deep link | — | elsewhere | Owned by concurrent QR/rename work (PR #10); do not touch. |
| Desktop plugin account-menu entry | Account menu placement | — | elsewhere | PR #9; do not touch. |
| iroh transport | M3 | — | elsewhere | Do not touch. |

## Plugin RPC additions (planned)

| Op | Host inject | Purpose |
|---|---|---|
| `commands.execute` | `commands` + `agents` | `/compact`, `/plan`, `/goal …`, `/feedback …` |
| `session.export` | session-log-export deps (persistence / session-query) | ZIP bytes for phone share/save |
| `files.list` / `files.read` | `workspaceFiles` | Right-panel files |
| `jobs.list` / `jobs.kill` | job controller | Right-panel background tasks |

Existing ops kept: sessions.*, prompt, cancel, follow, models.*, permissions.*, presets.list, approval.*, devices.*, pairing.*.

## Out of scope this pass

- Interactive terminal / embedded browser.
- Plugin install manager and schedule automation CRUD.
- Host credentials / “打开配置文件”.
- Experimental Agent Teams task board (beyond `parentSessionId` children).
- QR pairing / app rename / iroh (other agents).
