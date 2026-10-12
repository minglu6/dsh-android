# dsh Web UI backend protocol: notes for dsh-android

Source inspected: github.com/deepseek-ai/deepseek-harness @ d7432673 (commit 2026-10-09), root/cli version **0.2.1-alpha.2**.
Every statement below was read from source. File paths are relative to `packages/`.

## 0. Key finding: there is no REST API
The web UI does not use resource-style REST endpoints. Everything is **typed Remote RPC** (the "Typert" API Gateway) carried over one shared Connection:
- **Unary calls**: `POST /api/<namespace>/<method>` (docs/api-gateway.md §"Remote calls use the Connection's /api route"; client/connection/src/client/rpc.ts).
- **Streams and events**: one multiplexed **WebSocket** at `/api/remote.mux` (api/gateway/src/stream-protocol.ts `REMOTE_STREAM_MUX_PATH`). No SSE.
- There are also "exact Fetch routes" (feature-owned raw HTTP, e.g. streamed downloads). These are not enumerated here.

## 1. Unary RPC envelope
Request: `POST /api/session/prompt`, `content-type: application/json`. The server returns 415 for any other content type.
```json
{"type":"client-request","rpcId":"<uuid>","method":"session/prompt","payload":{"args":{ "request": {...} }}}
```
- `method` must equal the path endpoint, otherwise the request is rejected (rpc-host.ts:258).
- `payload` is `{ args: {...} }`. The keys of `args` are the **Host parameter names**. Lookup params are renamed: a param `agent: Agent` becomes wire field `agentId`, and a param `session` becomes `sessionId`. The Gateway requires the arg fields to match exactly, with no extra or missing keys.
- Response (rpc-schema.ts): `{"type":"server-response","rpcId":"…","result":{"ok":true,"value":…}}` or `{"ok":false,"error":{code,message,details}}`.
- Binary results arrive as `multipart/form-data`: a `metadata` JSON part plus byte parts, with an `attachments[{path,codec:'bytes',part}]` table.
- Error codes are strings, for example `gateway/bad-request`, `gateway/cancelled`, `gateway/internal`, `gateway/lookup-unavailable`, `session/not-found`, `session/agent-busy`, `session/model-unavailable`, `session/steer-unavailable` (see api/session-controller/src/types.ts RemoteErrorDetailsMap).
- Request body cap is 300 MiB by default.

## 2. WebSocket mux `/api/remote.mux` (api/gateway/src/stream-protocol.ts)
The socket carries JSON text frames.
Client → server:
- `{type:'open', streamId, endpoint:'<ns>/<method>', payload:{args:{…}}}`
- `{type:'item', streamId, value?}` (uplink)
- `{type:'end', streamId}`
- `{type:'cancel', streamId}`

Server → client:
- `{type:'item', streamId, value?}`
- `{type:'error', streamId, error:{code,message,details}}`
- `{type:'end', streamId}`

### The `$events` stream (generation and liveness)
- Open with endpoint `$events` and payload `{args:{}}`.
- The first item is `{type:'ready', clientId, host:{home}}`. The connection counts as live only after it arrives. The client logs a warning at 3 s and aborts at 15 s.
- After that the stream carries:
  - `{type:'emit', event, args[]}`: fire-and-forget Host events.
  - `{type:'waterfall', event, eventId, agentId, request}`: **the Host waits for a client answer**.
  - `{type:'cancel', eventId}`: the Host withdrew a pending waterfall.
- Answer a waterfall with the unary call `$events/result` and args `{clientId, eventId, outcome}`. `outcome` is one of `{kind:'result', value}`, `{kind:'next'}` (decline and let the next answerer handle it), or `{kind:'rejected', error}`. (The exact args wrapping should be verified against api/gateway/src/client/remote-events.ts before coding.)
- Forwarded events (api/remotes/src/remote-events.ts):
  - waterfall: `approval/request`, `user-questions/request`
  - emit: `api-session/added|removed|status|activity|error`, `commands/change`, `goal/activation-changed`, `schedule/changed`, `settings/document-updated`, `credentials/*`, `llm/adapters-updated`, `deepseek-account/session-expired|model-sign-in-required`, `permission-presets/catalog-changed`, `plugin-manager/*`, `agent-preset/selected`, `cordis/*`.
- Reconnect: the client retries with backoff starting at 500 ms. The Gateway mux has no retry logic of its own. The client must re-open `$events`, wait for `ready`, then re-open its streams.

## 3. Auth (client/connection/src/browser-auth.ts, README §Browser authentication)
- On each start the process mints a random **launch token** and prints or opens the URL `…/?token=<t>`.
- `GET /?token=<t>`, valid only on the root path with exactly one token, sets an **HttpOnly, SameSite=Strict signed cookie** and redirects to `./`.
  - Cookie name: `dsh-auth-<b64url(sha256(authority))>`.
  - Cookie value: `v1.<b64url(json{version,authority,issuedAt,expiresAt})>.<b64url(HMAC-SHA256)>`.
  - Lifetime is `maxAgeDays`, configurable. Over TLS the cookie also gets `Secure`, and the audience includes the scheme.
- **Every** `/api` call and the WebSocket upgrade require that cookie. There is no bearer header and no logout.
- The HMAC secret lives in `$DSH_HOME/.credentials.yaml` (record `client-connection/browser-session`). Rotating it revokes every session.
- **Host-header trust fence** (api-request-trust.ts): `Host` must be loopback, the listener's bind IP literal, or match a configured `trustedHosts` entry. If an `Origin` header is attached it must also be same-origin. This matters for Tailscale and LAN access: you must either bind to the tailnet IP or add the `host:port` to `trustedHosts`.
- **Native client approach**: perform the `?token=` GET once with a cookie jar (OkHttp CookieJar) and do not follow the redirect blindly. Persist the cookie in Keystore-backed storage, then send it on HTTP calls and on the WS upgrade. Do not send an `Origin` header, or send one that is same-origin.

## 4. Session namespace (`session/*`, api/session-controller/src/index.ts, types.ts)
Unary:
- `list` {request:{cursor?}} → `{items: SessionSummary[]}`
- `search` {request:{query}}
- `create` {request:{workspaceId?, cwd?, sessionId?, agentPreset?}} → `{sessionId}`
- `rename` {request:{sessionId,title}}
- `fork` {request:{sessionId, atSeq?}}
- **`prompt`** {request:{requestId, sessionId, mode:'queue'|'steer', content: PromptContentPart[], clientTimeZone?}} → `{accepted:true}`
  - `PromptContentPart` is `{type:'text',text}` or `{type:'image',mediaType,data(b64),name?}`.
- `updateQueue` {request:{sessionId,itemId,action}}
- `cancel` {request:{sessionId}}
- `attachment` {request:{sessionId,attachmentId}} → `{attachment, data}`
- `page` {request:{address, throughSeq, beforeSeq?, maxMessages?, turnWindow?}} → `{records, hasMore}`
- `projections` {request:{sessionId}}
- `selectModel` {request:{sessionId, provider, model, reasoningEffort?}}
- `modelCatalog` → `ModelCatalog` (groups[].models[] with reasoning.defaultEffort)
- `initializeDefaultModel`

Streams:
- **`follow`** {request:{address:{kind:'session',sessionId}, assistantStream?:true, maxMessages?, turnWindow?}} yields `SessionFollowFrame`:
  - `{type:'snapshot', header, cursor, records[], hasMore, projections, assistantStream?}` (first frame)
  - `{type:'event', event: SessionWireEvent{type, seq, time, data, ignorable?, surfaceOp?}}`
  - `{type:'assistant-stream', frame}`, where `frame` is one of:
    - `{type:'start', attemptId, revision, startedAfterSeq, turn, step}`
    - `{type:'chunk', attemptId, revision, index, time, chunk}`
    - `{type:'end', …, outcome:{kind:'committed',eventType,seq}|{kind:'abandoned'}}`
  - `chunk` is an LLM `StreamChunk` (llm/llm/src/types.ts:452): `block-start` | `text-delta` | `reasoning-delta` | `tool-call-delta{id,name?,argumentsDelta}` | `block-end{block}` | `usage` | `finish{reason}`.
- `control` (no args) yields `{type:'baseline', value:{projections}}` and then `{type:'projection', sessionId, key, value, seq}`. These are per-session live state values such as title, running, and model.

SessionSummary: `{sessionId, updatedAt, running, blank, agentAvailable, cwd?, parentSessionId?, origin?, projections?}`

## 5. Session event data model (core/session/src/types.ts)
Event types:
- `user/message`
- `assistant/message` {turn, step, message:AssistantMessage, stream, usage?, interrupted?}
- `assistant/attempt`
- **`tool/call`** {turn, step, callId, name, arguments(raw JSON string)}
- **`tool/result`** {turn, step, message:ToolResultMessage{toolCallId,…}, error?{name,code,reason?}, meta?}
- `turn/start`, `turn/end`, `step/end`
- `developer/message`, `system/message`, `request/header`, `request/context`, `llm/retry*`
- `approval/asked` {id, toolName, callId?, reason?} and `approval/decided` {id, outcome}
- `approval/policy` {policy:'ask'|'never'}

Content blocks: `text`, `reasoning`, `tool-call{id,name,arguments}`, and others.

Reconstruct the transcript by folding events in `seq` order. Honour `surfaceOp` (`append` or `{op:'replace',startSeq,endSeq}`) and skip `ignorable` events.

## 6. Approval flow (interaction/user-approval)
- The Host fires waterfall `approval/request` on `$events`. Its `request` is `{toolName, callId?, reason?, displayReason?{en,…}}`, with `agentId` on the frame.
- The client answers via `$events/result` with `{kind:'result', value:'allowed-once'|'rejected'}`.
- Possible outcomes: `allowed-once` | `rejected` | `cancelled` | `unavailable`.
- If no answerer is present the request fails closed.
- The web panel (client/ui-approval) uses a warn-amber bordered card with the headline "工具 {toolName} 请求越权执行", the strip text "等待审批", and the buttons 拒绝 (outline) and 允许一次 (primary). Shortcuts are Enter and Esc.
- `user-questions/request` works the same way (ask-user tool). The answer is also available as unary `userQuestions/answer`.
- **Consequence**: approvals only reach a client that holds a live `$events` socket. Background approval on a phone therefore needs either a foreground service that keeps the WS open, or a host-side push. dsh has no push mechanism.

## 7. Settings, model, and other namespaces
- `settings/describe`, `settings/update(ns, …)`, `settings/replace`, `settings/mutate`, `settings/openSettingsDocument`
- `credentials/describe(refs)`, `credentials/set(ref,value)`, `credentials/unset(ref)`
- `account/getState|getProfile|getBalance|startSignIn|signOut|watch(stream)|watchExpiry(stream)` (DeepSeek account login)
- `workspace/create|rename|delete|archiveSession|unarchiveSession|pinSession|insertSessionBefore|initializeDefault`, plus a workspace projection stream (see api/workspace-controller)
- `commands/list(agentId)`, `commands/execute`
- `goal/*`, `schedule/list|catalog|history|update|delete`, `jobs list/follow (stream)/kill`
- `terminal/*`, `workspaceFiles/read|readBytes|stat|list|changes(stream)`
- `permissionPresets/catalog`, `skills/list`, `fileReferences/list`
- Namespace strings come from each service's `{namespace:…}`. Verify the exact names of the non-session namespaces against the generated client in api/remotes before coding.

## 8. Design tokens (client/ui-theme/src/styles/*.css)
- Fonts:
  - Text: system stack `-apple-system, 'Segoe UI', 'PingFang SC', …`
  - Brand: **Montserrat** 300/400/500 (bundled)
  - Code: `'SF Mono','JetBrains Mono','Fira Code',Consolas…`
- Radii: xs 4, sm 8, md 12, lg 16, xl 20, panel 28 (px).
- Neutral "bluish" scale (light / dark):

| Token | Light | Dark |
|---|---|---|
| bg-base | #FFFFFF | #151517 (bluish-950) |
| bg-layer-1 | #FFFFFF | #232324 (bluish-875) |
| bg-layer-2 | #FFFFFF | #2C2C2E (bluish-850) |
| bg-layer-3 | #FFFFFF | #353638 (bluish-800) |
| label-primary | #0F1115 | #F9FAFB |
| label-secondary | #61666B | #CFD3D6 |
| label-tertiary | #81858C | #ADB2B8 |
| caption | #ADB2B8 | #81858C |
| border-l2 | rgba(0,0,0,.10) | rgba(255,255,255,.12) |
| border-l3 | rgba(0,0,0,.12) | rgba(255,255,255,.16) |

- **Primary button is NOT blue**: `brand-primary` / `button-primary-fill` = #0F1115 in light and #F9FAFB in dark (black and white).
- Blue accent ("business" / info): deepseek-500 **#4176E6** (rgb 65,118,230) in light, deepseek-400 **#7AAAFF** in dark. The tertiary tint is #E4EDFD in light and #34415B in dark.
- `#4D6BFE` appears only as a CSS fallback (ui-attachment FileCard). It is not a theme token.
- State colours:

| State | Light | Dark |
|---|---|---|
| success | #22C55E | #22C55E |
| error | #EC1313 | #F25A5A |
| warn primary | #F59E0B | #F59E0B |
| warn tertiary | #FEF5E7 | #27241F |
| warn border | #F7AD31 | #F7AD31 |

- Logo: `FishLogo` SVG path (ui-primitives/src/FishLogo.tsx, viewBox 23.16×17.04) plus `BrandWordmark`.

## 9. What makes a native client hard
1. A custom RPC and mux protocol instead of REST. The Kotlin side needs a hand-written codec, and arg names must match Host parameter names exactly. Cheap mitigation: generate DTOs from the `.d.ts` emitted by the typert generator.
2. Cookie auth tied to the launch token and the Host-header fence. Remote access needs `trustedHosts` configured on the host, and the token rotates on every dsh restart, so pairing must be redone after a restart (or the cookie re-minted).
3. Approvals are waterfalls on a live WS, so the phone must keep the socket alive (foreground service) to receive them.
4. The transcript is an event log (seq, surfaceOp, assistant-stream revisions). The client must reimplement the projection fold that the web UI does in client/ui-chat and ui-renderer.
5. The surface is large (~25 namespaces) and versioned as alpha (0.2.1-alpha.2). Expect churn, so pin a dsh version per app release.
