# dsh-android design (plugin bridge + native app)

Sources verified: deepseek-harness @ d7432673 (0.2.1-alpha.2) at /tmp/dsh-harness, and dsh-tether 0.1.21 at /tmp/dsh.
Tags used below:
- **[src]** marks a claim backed by a file:line reference.
- **[design]** marks a proposal.
- **[unverified]** marks something not yet confirmed from source.

## 1. How tether hooks into dsh (verified)

**Packaging**
- A plain npm package. `package.json` declares `"dsh": {"bundle": {"patch": "./cordis.patch.yml"}}` [src /tmp/dsh/package.json].
- The patch YAML inserts plugin rows with `{id, name}` and can disable built-ins, e.g. `directory-picker-auto` [src cordis.patch.yml].
- Install with `dsh plugin --profile web add dsh-plugin-tether` [src README.md:60].
- dsh `@deepseek-ai/*` packages are declared as `peerDependencies` with version ranges. The plugin manager rejects an incompatible peer before pnpm runs [src packages/boot/plugin-manager/README.md "incompatible-version"]. **Every new dsh minor needs a range bump.**

**Cordis plugin entry**
- `export { name, inject, apply }` with `inject = ['webServer','settings']` [src index.js:17-18, 809].
- `apply(ctx, config)` receives the patch row's config, e.g. `{pair, hostBinary, sidecar}` [src index.js:150-160].

**Extension points tether uses** [src index.js]
- `ctx.webServer.register({kind:'exact'|'prefix', path, handler(req,res)})`: extra HTTP routes on dsh's own listener (pairing and device list).
  - Route API [src packages/host/webserver/src/index.ts:43-60].
  - There is also `ctx.webServer.registerUpgrade({path, handler(req,socket,head)})` for exact-path WebSocket upgrades [src webserver/index.ts:349]. Tether does not use it; we will.
- `ctx.webServer.tapIndex(html=>html)`: injects narrow-screen CSS. We do not need it.
- `ctx.inject(['connection'], …)` plus `connection.authenticatedUrl()`: mints the browser cookie so the sidecar can proxy the web UI [src index.js:283-315]. We do not need it, because we call services in-process.
- `ctx.on('approval/request', async (req,next) => …)`: a waterfall listener [src index.js:390].
  - Tether only *notifies*, then `await next()`, so the decision is still made by the proxied web UI.
  - The phone's `Decision` wire message is emitted by the sidecar as `PluginOut::Decision` [src host/src/main.rs:584], but index.js has **no handler for it** (no `decision` case in the stdout switch). Tether's native approval answer path is effectively unused.
- `ctx.effect(() => dispose)` is used for lifetime cleanup.

**Sidecar**
- A Rust binary (`tether-host`, iroh 1.0) is spawned per-platform from optionalDependencies and spoken to with **newline-JSON over stdin/stdout** [src index.js:196-260].
- Messages:
  - plugin → sidecar: `pairing-begin`, `device-list`, `device-forget`, `proxy-auth`, `approval`, `approval-cancel`
  - sidecar → plugin: `ready{endpoint_id}`, `pairing{code,expires_in_sec}`, `pairing-done`, `peer-connected`, `peer-path{kind:direct|relay}`, `devices`

**iroh layer** [src tether-core/src/lib.rs, host/src/main.rs]
- ALPN is `dsh-tether/0`.
- Endpoint uses `presets::N0`, i.e. n0's public discovery and relays [main.rs:347].
- Persistent `SecretKey` is stored in a 0600 file. `paired.json` holds the allow-list of phone node IDs.
- First bi-stream is the control stream. Its first line is `{"type":"hello",name}` for a paired peer or `{"type":"pair",code,name}`.
- Pairing:
  - 6-digit code: `format!("{:06}", rand 0..1e6)` [main.rs:315].
  - TTL 600 s [main.rs:108]. Max 3 attempts, then the window closes [main.rs:109]. A 200 ms delay follows each failure.
  - The pairing string is `<endpointId>#<code>`.
- Unpaired lines are capped at 512 B and paired lines at 64 KiB [lib.rs:18-20].
- Later bi-streams start with `{"type":"proxy"}` and then carry raw TCP to the dsh web port. The sidecar rewrites the head to inject the cookie [main.rs:592-640].
- Path reporting: after up to 8 s it reports `direct` if the selected address is an IP, otherwise `relay` [main.rs:547-568].

**Local mode** [src scripts/build-android-runtime.mjs, app/src-tauri/src/local.rs]
- Termux-built `node` is shipped as `jniLibs/arm64-v8a/libnode.so`, because Android 10+ only allows exec from the APK native-lib dir.
- Everything else ships in `assets/dsh-runtime.tar`, which is extracted to app data.
- The plugin itself is preinstalled into `profiles/web` with patch config `sidecar: false` [build-android-runtime.mjs:176-195].

## 2. How a plugin reaches sessions and approvals in-process (verified)

- The `@Remote` methods are ordinary methods on Cordis services, so a plugin can `inject` the service and call them directly. No HTTP, no cookie, no typert envelope.
  - `SessionController` registers as the service `sessionController` with Remote namespace `session` [src packages/api/session-controller/src/index.ts:143].
  - Methods [index.ts:259-560]:
    - `list({cursor?}, signal)`
    - `create({workspaceId?,cwd?,agentPreset?})`
    - `prompt({requestId,sessionId,mode:'queue'|'steer',content}, signal)`
    - `cancel({sessionId})`
    - `updateQueue(...)`
    - `follow({address:{kind:'session',sessionId}, assistantStream:true}, signal)` → `AsyncIterable<SessionFollowFrame>`
    - `page(...)`
    - `selectModel({sessionId,provider,model,reasoningEffort?})`
    - `modelCatalog()`
    - `rename`, `fork`
    - `control(signal)` → per-session projection stream
- Session lifecycle events are Cordis events: `api-session/added|removed|status|activity|error` [src session-controller/src/types.ts, end of file]. Listen with `ctx.on(...)`.
- **Approvals**:
  - `ApprovalService.request()` runs `ctx.waterfall(scope(agent), 'approval/request', req, () => 'unavailable')` [src packages/interaction/user-approval/src/index.ts:280-292].
  - A listener may return an outcome directly (`'allowed-once'|'rejected'|'cancelled'|'unavailable'`) or defer with `next()`. Non-vocabulary values and throws become `'unavailable'`, i.e. fail closed.
  - `req = {agent, toolName, callId?, reason?, displayReason?, signal?}` [src user-approval/src/types.ts]. The session id is `req.agent.session.id` [src core/agent/src/runtime-types.ts:168, core/session/src/index.ts:546].
  - The web UI's own answerer is the typert forwarder of the same event [src api/remotes/src/remote-events.ts]. **[design]** Our listener races the phone's answer against `next()`, whichever settles first, and honours `req.signal`. That way a desktop browser and the phone can both answer.
- `user-questions/request` is the same waterfall pattern (ask-user tool) [src remote-events.ts]. Handle it in phase 2.
- Service names to inject: `sessionController` (verified). The approval service is registered as `approval` [src user-approval/src/index.ts:156]. We only need `ctx.on`, so no inject is required. Use optional peer ranges like tether does.

## 3. Plugin architecture: `dsh-plugin-pocket` (working name)

```
dsh process (profile web)
 └─ dsh-plugin-pocket  (Cordis plugin, JS, inject: ['sessionController','webServer'?])
     ├─ core/        protocol router: JSON msg ⇄ sessionController calls, follow fan-out, approval broker
     ├─ auth/        device store (~/.dsh/pocket/devices.json, 0600), pairing window, token verify
     └─ transports/
         ├─ ws-listen     own http+ws listener on configurable host:port (Tailscale IP / LAN), NOT dsh's webServer
         ├─ webserver-ws  optional: ctx.webServer.registerUpgrade('/pocket/ws') reusing dsh's port
         ├─ iroh          Rust sidecar (fork of tether-host) over stdio; ALPN 'dsh-pocket/1'; byte-pipes framed JSON to core
         └─ local         loopback ws on 127.0.0.1:<port>, token from app-private file (phone local mode)
```

- **Why our own listener for Tailscale and LAN**: dsh's webServer applies the browser Host-header fence and cookie auth to `/api` only. Our routes are separate. A dedicated port, though, avoids coupling to dsh's bind address and its TLS setting. **[design]** Default `127.0.0.1:7690`; config `listen: ['100.x.y.z:7690', …]`.
- **Sidecar reuse**: fork tether-host and keep:
  - secret key and paired-store code
  - pairing window (6 digits, 600 s, 3 tries)
  - path reporting
  - bounded line IO

  Replace the `proxy` stream with a single `pocket` bi-stream that tunnels our JSON frames between phone and plugin, so the sidecar becomes a dumb pipe and stays protocol-agnostic. Authorization stays in the sidecar's allow-list: an iroh NodeId is the identity, so no token is needed on that path.
- **Config (patch row)**:
  ```yaml
  - id: dsh-pocket
    name: dsh-plugin-pocket
    config: { listen: ['127.0.0.1:7690'], iroh: true, pair: false, mode: desktop|local }
  ```
  `mode: local` disables iroh and binds loopback only.

## 4. Protocol spec v1 (JSON over WebSocket text frames, or newline-JSON in an iroh stream)

**Envelope**
- Client request: `{"id": <int>, "op": "<name>", "args": {...}}`
- Reply: `{"id": n, "ok": true, "result": …}` or `{"id": n, "ok": false, "error": {"code","message"}}`
- Server push: `{"ev": "<name>", ...}`
- Error codes mirror dsh RemoteError codes where one exists (`session/not-found`, `session/agent-busy`, `session/model-unavailable`, `session/steer-unavailable`) plus `auth/*`, `bad-request`, `internal`.

**Handshake**
1. Client → `{"op":"hello","args":{"proto":1,"device":"<deviceId>","token":"<b64>","app":"dsh-android/0.1"}}`.
   The iroh transport skips the token, because the sidecar already authenticated the NodeId.
2. Server → `{"ok":true,"result":{"proto":1,"dsh":"0.2.1-alpha.2","plugin":"0.1.0","host":"<hostname>","caps":[...]}}`.
   Any frame before a successful hello closes the connection with code 4401. The server enforces a 10 s hello timeout and a 64 KiB frame cap.

**Pairing (pure iroh)**
- The desktop opens a pairing window: settings UI, CLI, `/pocket/pair` on dsh webServer (loopback), or `pair: true`.
- Shows 6-digit code, paste line `<nodeId>#<code>`, and QR `dshpocket://pair?n=<irohNodeId>&c=<code>` (tether-style; no Tailscale/LAN host).
- Phone connects over iroh, then `{"op":"pair","args":{"code":"123456","name":"Pixel 9"}}`.
- Server → `{"result":{"deviceId":"<id>","token":"<…>"}}` (token hashed sha256 server-side).
- Same window rules as tether: 600 s TTL, 3 attempts, 200 ms failure delay.
- Transport security: iroh TLS; relay carries ciphertext only.

**Ops**
- `sessions.list {cursor?}` → `{items: SessionSummary[]}` (passed through from `sessionController.list`)
- `sessions.create {cwd?, workspaceId?, agentPreset?}` → `{sessionId}`
- `sessions.rename {sessionId, title}`
- `prompt {sessionId, mode:"queue"|"steer", text, images?: [{mediaType, data}], requestId?}` → `{accepted:true}`
  - The server fills `requestId` (uuid) and `clientTimeZone` and builds `content` parts.
- `queue.update {sessionId, itemId, action}`
- `cancel {sessionId}`
- `follow {sessionId}` → `{subId}`. The server then pushes `{"ev":"follow","subId",frame}` where `frame` is the dsh `SessionFollowFrame` verbatim (snapshot / event / assistant-stream).
  - **[design] v1 passes dsh frames through untouched** and lets the app fold them. Phase 2 may add a server-side reducer `transcript` mode that sends pre-folded items (user / assistant text / reasoning / tool{name,args,status,result-preview} / approval) to save phone CPU and bandwidth.
- `unfollow {subId}`
- `history.page {sessionId, beforeSeq, maxMessages}` (passed through to `page`)
- `models.catalog` → ModelCatalog. `models.select {sessionId, provider, model, reasoningEffort?}`
- `approval.answer {approvalId, outcome:"allowed-once"|"rejected"}`

**Pushes**
- `{"ev":"session","kind":"added|removed|status|activity|error", ...}` (from `api-session/*`)
- `{"ev":"approval","approvalId","sessionId","toolName","callId?","reason?","displayReason?"}`
- `{"ev":"approval.closed","approvalId","outcome"}`. Sent when anyone answered or the request was cancelled through `req.signal`.
- `{"ev":"ping"}` every 20 s. The client replies with `{"op":"pong"}`, and the server drops the client after 60 s of silence.

**Approval broker [design]**
- On `approval/request`, the broker assigns `approvalId`, broadcasts to all authenticated clients, and races:
  1. a phone answer
  2. `next()` (desktop web UI and other answerers)
  3. `req.signal` abort
- The first to settle wins, and the broker broadcasts `approval.closed`.
- With no phone connected it just returns `next()`, so dsh behaviour is unchanged.
- Pending approvals are replayed to a client right after hello.

**Reconnect**: the client re-sends hello, re-issues `follow`, and gets a fresh snapshot. No server-side resume cursor in v1.

## 5. Transport layer (phone side, pluggable)

```kotlin
interface Transport { suspend fun connect(): Channel; val kind: Kind }   // Channel = Flow<String> in + suspend send(String)
```

- `WsTransport(url, token)`: OkHttp WebSocket, used for Tailscale, LAN and local. Tailscale needs nothing special; the user enters the 100.x / MagicDNS address.
- `IrohTransport(nodeId)`: iroh on Android, either through official FFI bindings (**[unverified]**: iroh-ffi Kotlin availability for iroh 1.0 must be checked) or our own small Rust JNI lib built from tether-core with uniffi. Opens ALPN `dsh-pocket/1`, does hello on the control stream, then sends newline-JSON. Path direct/relay is surfaced to the UI dot.
- `LocalTransport`: starts the bundled dsh (libnode.so plus runtime tar, as in tether) in a foreground service with the plugin in `mode: local`, then a `WsTransport` to 127.0.0.1.
- The status dot follows the pocket-android convention: green when connected (iroh direct), amber when connected via relay or with high latency, red when unreachable, grey when never tried or local runtime is stopped.

## 6. Phone app modules (Kotlin + Compose)

```
:app                      MainActivity, nav graph, DI (Hilt or manual), foreground ConnectionService, notifications
:core:protocol            kotlinx.serialization models for §4 + dsh SessionFollowFrame/StreamChunk/ events
:core:transport           Transport iface, ws, iroh (JNI), local
:core:session             TranscriptReducer (fold seq events + assistant-stream into UI items), ApprovalRepo, SessionRepo
:core:storage             profiles (DataStore), tokens (Android Keystore AES-GCM — reuse pi-android SecureTokenStore)
:core:designsystem        dsh tokens (api-notes §8), Montserrat brand + system text + JetBrains Mono code, sheets, chips, status dot
:feature:chat             transcript, tool-call row + bottom sheet, reasoning, composer (queue/steer/model chips), approval card
:feature:sessions         drawer list, search, pin
:feature:connections      profiles list, pairing (code + QR scan via CameraX/ML Kit), diagnostics
:feature:settings         full-screen settings, theme
:runtime:local            libnode.so + dsh-runtime.tar extraction (port of tether local.rs), arm64 only
```

- The ConnectionService (foreground, `dataSync` type) keeps the active channel alive so approvals arrive in the background. Each approval posts a high-priority notification with 拒绝 / 允许一次 actions that call `approval.answer`.

## 7. Milestones

- **M0 (2–3 d) Plugin skeleton**: npm package plus patch. Loopback WS listener. hello with a static token from config. Ops: `sessions.list`, `sessions.create`, `prompt`, `cancel`, `follow`. Verify in the dsh 0.2.1-alpha.2 web profile with a node test client.
- **M1 (3–4 d) Approvals plus models plus pairing**: approval broker, model ops, device store, pairing window (code plus QR), listen on Tailscale IP. Ships a usable desktop side.
- **M2 (1 wk) Android MVP over WS**: design system, connections (Tailscale/LAN profiles plus pairing), drawer, chat with reducer, tool sheet, approval card plus notification, foreground service.
- **M3 (1 wk) iroh** ✅: Pure iroh (no Tailscale). `plugin/iroh` pocket-host (ALPN `dsh-pocket/1`). Pairing: tether-style `nodeId#code` / QR `dshpocket://pair?n=&c=`. Dial iroh only (loopback WS for tests). CI bundles `libpocket_iroh.so`; prebuilt `pocket-host` for linux/darwin-arm64.
- **M4 (1 wk) Local mode**: port tether's runtime build script and local.rs. Bundle the plugin with `mode: local`. Lifecycle management.
- **M5 Polish**: server-side transcript reducer, history paging, user-questions, image prompts, reconnect hardening, per-dsh-version CI matrix.

## 8. Risks

1. **dsh API churn**: alpha versions, and the plugin calls internal service methods (`sessionController.*`) whose signatures are not a published contract. Pin peer ranges per release and add a CI matrix against the latest dsh.
2. **Peer range gate**: a new dsh minor refuses to load the plugin until the range is bumped. Users can use `allow-version` as an escape hatch.
3. **Approval race semantics**: racing `next()` against a phone answer is our design. Verify with dsh that an unsettled `next()` chain, i.e. a web UI waterfall left pending, is cleaned up when we resolve first. `req.signal` covers dsh-side cancel, but the forwarder's pending event may linger until the browser gets `cancel`.
4. **iroh on Android from Kotlin is unverified.** It may need our own uniffi/JNI crate. That adds an NDK build plus roughly 10 MB per ABI.
5. **LAN plaintext**: needs TLS pinning or must be documented as Tailscale-only.
6. **Local mode size and limits**: arm64 only. Termux node build maintenance. No bash/git unless bundled, same as before.
7. **Background delivery**: Android may kill the foreground service on aggressive OEMs, and there is no push fallback because dsh has no push.
