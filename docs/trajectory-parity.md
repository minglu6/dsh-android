# Trajectory parity audit

Source-driven audit of `@deepseek-ai/dsh-client-ui-trajectory@0.2.1-alpha.2`
(`[deepseek-ai/deepseek-harness` @ `d743267`](https://github.com/deepseek-ai/deepseek-harness/tree/d743267388))
against Android `dsh-android` after PRs **#24**, **#31**, **#35**, **#36**.

Desktop package root: `packages/client/ui-trajectory/src/client/`.

---

## 1. Architecture (desktop)

| Stage | Files |
|---|---|
| Locales | `locales.ts` (`NS = trajectory`; zh is source of truth) |
| Event → node | `trajectory-*-definition.ts`, `trajectory-event-projection.ts` |
| Snapshot | `trajectory-snapshot-builder.ts` (`captureSchemas`, system prompts, requests) |
| Layout cells | `layout.ts` (`deriveTrajectoryLayout`, `expandSubCalls`, `promptChangeLabel`) |
| Preview | `trajectory-preview.ts` (2048 → markdown plain → 512 + `…`) |
| Ledger + inspector | `TrajectoryTable.tsx` (`detailTabs`, `stateOf`, `recordDisplayText`) |
| Swimlane | `timeline.ts`, `TrajectoryTimeline.tsx` |
| Toolbar | `TrajectoryToolbar.tsx` |
| PTC only | `code-program.ts` (`run_code`) |

**Closed cell kinds** (`TrajectoryCellKind` in `trajectory-record.ts`):

`system` · `user` · `context` · `compacted` · `message` · `tool` · `subtool`

Thinking is **not** a kind — it is `thinkingDetail` on `message`.

**Important:** Chat `@deepseek-ai/dsh-client-ui-tool` cards (bash/read/edit/grep/…) are **not** used by Trajectory. Trajectory renders tools as `name · args` + optional `→ result`, with **only** `run_code` getting Program inspector UI. Per-tool Chat cards are documented in §4 for completeness; Android Trajectory must follow Trajectory, not invent Chat cards into the ledger.

---

## 2. Shared chrome

### Kind tag (label / icon / color token)

| Kind | zh | en | Icon | CSS class / token |
|---|---|---|---|---|
| system | 系统 | SYSTEM | `IconSettingsOutlineRegular` | `.systemNeutral` — label-secondary on module-platform |
| user | 用户 | USER | `IconUserOutlineRegular` | `.tagUser` — success-primary / success-tertiary |
| context | 上下文 | CONTEXT | info SVG | `.contextGreen` — success-primary mix / success-tertiary |
| compacted | 已压缩 | COMPACTED | compact SVG | `.compacted` — same family as system |
| message | 助手 | ASSISTANT | `IconSparkleRegular` | `.assistantVioletBright` — brand-violet × error-secondary |
| tool | 工具 | TOOL | wrench SVG | `.toolAmber` — warn-label |
| subtool | 子工具 | SUBTOOL | wrench SVG | `.subtoolAmber` — muted amber; indented |

### Ledger columns

**Live desktop table** (`TrajectoryTable`): two columns — **事件** (kind tag + turn) and **内容** (preview [`→` result]).

**Legacy cell** (`TrajectoryCell` + turn header, not wired into live TrajectoryView): **输入 / 输出 / 思考 / 时间** token+duration metrics on message rows.

**Swimlane labels**: **输入 / 模型 / 工具** (`column.input` / `column.model` / `column.tools`).

On phone-width panels, the ledger keeps kind/preview and elapsed time visible; token metrics stay in the detail inspector instead of squeezing the title. Panels at least 600dp wide additionally show the input/output/reasoning metric columns. The toolbar reflows its search field below the fold controls on narrow panels; action and ledger touch targets are at least 48dp. Swimlane selection checks both horizontal time and vertical lane, so overlapping model/tool durations select the touched record. Badge and span colors resolve the official light/dark aliases and sRGB color mixes rather than fixed light-only colors.

### States (`stateOf`)

| State | Detection | Status label |
|---|---|---|
| error | `isError` / interrupted | 失败 |
| running | tool/subtool with no `outputDetail`; assistant `completedTime === null`; compacted `timeSeconds === null` | 等待中 |
| complete | else | 已完成 |

No separate “aborted” chrome — abort maps to **failed** (tool `Interrupted: interrupted`, assistant `isError`, compaction interrupted copy).

### Preview rule

`trajectoryPreviewText`: first 2048 chars → markdown plain text → collapse whitespace → cap 512 → append `…`.

List display (`recordDisplayText`): tool-call-only → empty (UI shows muted `（仅工具调用）`); `run_code` → `run_code · {description}`; else `text · preview` or preview alone.

---

## 3. Per-kind desktop contract

### 3.1 `system` — 初始系统提示词 / 系统提示词已更新 / 工具已更新

| Field | Rule |
|---|---|
| Sources | `system/message` appends + `request.promptChange` from `request/header` |
| Title | `promptChangeLabel`: `initial`→初始系统提示词; `system`→系统提示词已更新; `tools`→工具已更新; both→系统提示词和工具已更新 |
| Preview | Static label (no markdown) |
| Columns | Event=系统; Content=label; timeSeconds=0 |
| Swimlane | Lane 0 输入; neutral gray |

**Detail tabs**

| Condition | Tabs |
|---|---|
| Prompt-only (`systemPromptDetail`, no `promptDetail`) | **系统提示词** |
| New prompt (`promptDetail`, no previous) | **系统提示词**, **工具** |
| Update (`previousPromptDetail`) | **差异**, **系统提示词**, **工具** |

| Tab | Fields |
|---|---|
| 差异 | Line-diff of system text + tools added/removed lists |
| 系统提示词 | Markdown of current system prompt (or 本次请求没有系统提示词) |
| 工具 | Tool catalog: name, description, parameters JSON (or 本次请求没有工具) |

---

### 3.2 `user`

| Field | Rule |
|---|---|
| Sources | `user/message` with `source.kind === 'user'`; claimed inbox steering laid as user without `opensTurn` |
| Title / preview | Attachment summary (`图片 ×N` / `文件 ×N`) + first text block via preview rule |
| Columns | Event=用户; Content=preview; duration usually 0 ms |
| Swimlane | Lane 0; business-primary |

**Detail tabs:** 概述 · 预览 · 原始内容 · (+ 来源 if `messageSource`)

| Tab | Fields |
|---|---|
| 概述 | Source label, 状态, 时长; preview section |
| 预览 | Rendered markdown + attachments |
| 原始内容 | Source blocks / raw |
| 来源 | `messageSource` JSON (`source.user` / `source.goal` / …) |

---

### 3.3 `context` — runtime / system-reminder / hindsight / time / tool notices

Desktop does **not** split kinds. All injections are `kind: 'context'`. Differentiation is content + optional `messageSource` / node `producer`/`form` (stored on ConversationNode; **not** shown in ledger text).

| Field | Rule |
|---|---|
| Sources | `developer/message`; `user/message` with non-user source; tool add/remove content blocks |
| Preview | Same as user; tool-update singles: `已添加工具：{name}` / `已移除工具：{name}`; multi: `工具已更新 · …` |
| Swimlane | Lane 0; green |

**Detail tabs:** same as user (概述 / 预览 / 原始内容 / 来源). Single tool-add/remove notices are not clickable on desktop.

---

### 3.4 `message` (助手) — thinking embedded

| Field | Rule |
|---|---|
| recordId | `assistant\0{turn}\0{step}` |
| Preview | Text blocks → else reasoning → else `仅工具调用` (list: muted `（仅工具调用）`) → else image count |
| Metrics | `input` / `cacheRead` / `cacheWrite` / `output` / `think` on cell; shown in inspector, not live table columns |
| Timing | `assistantMetrics`: stepStartTime, firstTokenTime, completedTime → Total / TTFT / Generation / Throughput |
| Swimlane | Lane 1; violet; optional TTFT/decoding gradient |

**Detail tabs:** 概述 · 预览 · 原始内容 · (+ 来源)

| Tab | Fields |
|---|---|
| 概述 | 层级→Request #N; 状态; Token (输出/推理/内容); Request Timing preview |
| 预览 | Collapsible 思考 + rendered output + tool-call chips + images |
| 原始内容 | Source blocks or thinking+output |
| 计时 (panel) | 开始时间, 总时长, 首 token 延迟, 生成, 吞吐量 |

Interrupted assistant → `isError` (失败), keeps partial text/thinking. No separate abort row.

---

### 3.5 `tool` / `subtool`

| Field | Rule |
|---|---|
| Sources | Assistant tool-call blocks; `tool/call`+`tool/result`; PTC `tool/ptc-dispatch-start` / `tool/ptc-dispatch` → **subtool** |
| Preview | `name · {argsRaw}`; `run_code` → `run_code · {description}` |
| Result | Inline `→` + markdown preview / `图片 ×N` / `无输出`; errors red (`error.code` or `error`) |
| Swimlane | Lane 2; warn amber (error → error-primary) |

**Detail tabs:** 概述 · 参数(if) · 结果(if or run_code) · Schema · 计时  
For `run_code`, 参数 label becomes **代码**.

| Tab | Fields |
|---|---|
| 概述 | 层级 (助手消息 / parent tool); 状态; payload/result/schema/timing previews |
| 参数 / 代码 | JSON args, or Program (source + language + wrap) |
| 结果 | Payload / ProgramOutput / blocks (error styling) |
| Schema | name, description, parameters from call-time tools |
| 计时 | 开始时间, 时长, 计时来源 |

---

### 3.6 Tool results & errors

Not separate kinds — paired onto the same tool/subtool cell via `outputDetail` / `isError`. Open tools closed by turn/step end synthesize `Interrupted: interrupted`.

---

### 3.7 `compacted`

| Status | Preview text |
|---|---|
| running | 正在压缩上下文… |
| error + interrupted | 上下文压缩在完成前被中断。 |
| error other | error string or 上下文压缩失败 |
| complete + summary | summary markdown |
| complete no summary | 上下文已压缩 |

**Detail tabs:** 概述 · 原始输出  
Placement: inside turn or **轮次之间** (`turn: null`). Swimlane lane 1.

Events: `compaction/start` · `compaction/summary` · `compaction/end`.

---

### 3.8 Abort / interrupt (no dedicated kind)

| Case | Appearance |
|---|---|
| User abort mid-assistant | `message` `isError`, partial content |
| Open tool on close | tool/subtool error Interrupted |
| Compaction interrupted | compacted error + interrupted copy |
| Turn-end error | Request inspector status only |

---

### 3.9 Turn & step markers

- Turn label: `第 {turn} 轮` / compact `#n` under 620px
- Groups: `消息`, `第 {step} 步`, `压缩 {seq}`
- Between turns: `轮次之间`
- Request boundary control on first non-user/context cell → **Request inspector**

---

### 3.10 Request / model headers (not a cell kind)

Request inspector tabs: **概述 / 选项 / 用量 / 计时**

Overview: 状态, 用途(压缩), 提供方, 模型, 工具调用, 子工具调用, 错误 / 上次尝试错误, 重试, 重试延迟, 结果.

System/tools changes also surface as `system` rows via `promptChange`.

---

## 4. Per-tool Chat cards (NOT Trajectory)

From `@deepseek-ai/dsh-client-ui-tool` `tool-call-model.ts` — used by **Chat**, not Trajectory ledger:

| Variant | Wire names | Summary keys |
|---|---|---|
| bash | bash, pwsh | description, command (+ stdout/exit in card) |
| read | read, read_image, web_fetch, … | path / range |
| search | grep, glob, web_search | pattern / queries |
| write | write | path |
| edit | edit | path (+ diff card) |
| code | run_code | description / code |
| others | MCP / subagent / … | details |

**Trajectory special-case only `run_code`** via `codeProgram()`.

---

## 5. Search / toolbar / swimlane

### Search (`trajectory-search-index.ts`)

- Index every non-`requestOnly` cell: turn, group, kind (+ `assistant` alias for message), text, preview, input/output/thinking/schema/result, callId, blocks, messageSource JSON, prompt JSON
- Query: trim → lowercase → whitespace-split → **AND** of `includes`
- Empty → no filter
- Ledger hides non-matches; timeline dims (`opacity 0.14`)

### Toolbar

| Control | Behavior |
|---|---|
| 时长 | `actualDuration`: on → duration mode (idle compressed); off → sequence equal-width |
| 实际时间 | Present but **hidden** in DOM — wall clock keeping idle |
| 轮次 | Collapse/expand multi-record turns |
| 调用 | Collapse/expand tool tails under assistants |
| 搜索 | Live query |

### Swimlane

- 3 lanes × 14px; bars 8px
- Lane map: system/user/context→0; message/compacted→1; tool/subtool→2
- Modes: `sequence` / `duration` (compress idle) / `time` / `actual`
- Drag range focus; click selects; wheel zoom; right-drag pan
- Error spans → error-primary

### History

- Page size 50 nodes; load-older near top; virtualization when >100 rows or hasOlderRecords

---

## 6. Android after #24 / #31 / #35 / #36 — gap list

Status column tracks Request inspector + load-earlier (this PR) on top of #36 kinds/detail work.

| # | Desktop | Android (pre this PR) | This PR |
|---|---|---|---|
| G1 | 7 kinds including **已压缩** / **子工具** | Only 系统/上下文/用户/助手/工具 emitted | **Fixed** — `Item.Compacted` + PTC `parentCallId` → 子工具 |
| G2 | System detail: 差异/系统提示词/工具 | 概述/原始内容 | **Fixed** — `trajDetailTabs` + previous/tools JSON |
| G3 | Assistant: 概述/预览/原始内容 + TTFT panel | 概述/思考/原始内容/计时 | **Fixed** — Preview/Raw/Timing with TTFT/生成/吞吐量 |
| G4 | User/Context: +来源 tab | No Source tab / messageSource | **Fixed** — `messageSourceJson` forwarded |
| G5 | Compacted: 概述/原始输出 | N/A | **Fixed** |
| G6 | Tool `→` result; run_code Program | Title=`name arg`; generic JSON | **Fixed** — `·` / `→` + `CodeProgram` |
| G7 | Interrupted → message **失败** | Invented 系统 Notice | **Fixed** — `StepRecord.interrupted`; no invented Notice |
| G8 | Error glyph | Ledger ✓ when not running | **Fixed** — ✗ + Err color |
| G9 | Kind badges 已压缩/子工具 | Fallback muted | **Fixed** |
| G10 | Request inspector | Schemas only | **Fixed** — `TrajectoryRequest` + boundary chip + sheet tabs 概述/选项/用量/计时; `request/header` config + `llm/retry` stamped on `StepRecord` |
| G11 | Locales from `locales.ts` | Scattered hard-coded ZH | **Fixed** — `TrajectoryLocales.kt` / `TrajL` |
| G12 | Search haystack | Partial | **Fixed** — includes schema/source/tools |
| G13 | `tool/ptc-dispatch*` / `compaction/*` | Ignored | **Fixed** — reducer consumes; plugin already forwards |
| G14 | firstTokenTime | Missing | **Fixed** — stream + StepRecord |
| G15 | Previous system + tools catalog | Latest toolsByName only | **Fixed** |
| G16 | Load-older UI | hasMore only | **Fixed** — Trajectory top “加载更早的历史” + near-top scroll → `Pocket.loadOlderHistory()` / `history.page` (#27) |
| G17 | Swimlane Actual | Toolbar hidden | OK (matches desktop) |
| G18 | Chat tool cards | N/A | Out of scope (desktop Trajectory also omits) |

---

## 7. Phone adaptations (this PR)

- Full-width ledger rows (keep)
- Detail as Tokyo Night / Meslo **bottom sheet** with desktop tabs (extend)
- Horizontally scrollable, **tappable** swimlane (keep; ensure all kinds colored)
- Plugin: follow frames already pass event types unchanged; reducer consumes newly needed fields — no secret truncation of trajectory fields beyond existing size caps

---

## 8. Implementation map

| Desktop | Android |
|---|---|
| `locales.ts` | `TrajectoryLocales.kt` |
| `layout.ts` / snapshot builder | `TranscriptReducer` + `buildTrajectory` |
| `TrajectoryTable.detailTabs` | `trajDetailTabs` / `TrajectoryDetail.kt` |
| `timeline.ts` | `TrajectoryTimeline.kt` |
| `TrajectoryToolbar` / swimlane | `Trajectory.kt` |
| `code-program.ts` | `CodeProgram.kt` |
| `requestNumbers` / REQUEST_TABS | `TrajectoryRequest.kt` / `TrajectoryRequestDetail.kt` |
| history load-earlier | `HistoryLoadEarlierRow` + `Pocket.loadOlderHistory` |
| table / layout / snapshot tests | `Trajectory*Test`, `TrajectoryRequestTest`, `TranscriptReducerTest`, Roborazzi per kind |
