# dsh-plugin-pocket

dsh-android 的桌面端桥接插件。它运行在 dsh 进程里，直接调用 dsh 的会话服务，再通过 WebSocket 给手机提供一套简单的 JSON 协议。完整协议见 [../docs/design.md](../docs/design.md) 第 4 节。

## 安装

```bash
dsh plugin --profile web add dsh-plugin-pocket          # 发布到 npm 之后
dsh plugin --profile web add /path/to/dsh-android/plugin # 从源码安装
dsh web
```

启动后日志里会出现 `[pocket] listening on ws://127.0.0.1:7690`。第一次运行时会生成 token，保存在 `$DSH_HOME/pocket/token`，文件权限 0600。

## 配置（`$DSH_HOME/profiles/web/cordis.patch.yml`）

```yaml
- id: dsh-pocket
  config:
    host: 100.106.128.87   # 监听地址，填 Tailscale IP 即可远程访问；默认 127.0.0.1
    port: 7690
    # token: 固定 token；不填则使用自动生成的那个
    # iroh: true           # 默认在找到 pocket-host 时启用；false 可关掉
    # hostBinary: /path/to/pocket-host
```

## iroh（M3）

桌面侧额外跑一个 Rust sidecar（`plugin/iroh`，ALPN `dsh-pocket/1`），与现有 `ws://host:port` 并行。手机连接顺序：**Tailscale/LAN WebSocket → iroh（打洞 direct，失败则 relay）**。鉴权仍是同一套 `pair` / `hello` token。

**一行安装 sidecar（macOS arm64 / Linux）：**

```bash
cd plugin && npm run build:iroh
```

需要本机有 Rust（[rustup](https://rustup.rs)）。也可以不预编译：启动 `dsh web` 时插件会依次尝试已有二进制 → 下载 `dsh-android-latest` 发布的 `pocket-host-darwin-arm64` / `pocket-host-linux-x64` → 若有 `cargo` 则自动编译。都失败时日志会说明原因，并继续只用 WebSocket（Tailscale/局域网不受影响）。

配对 URI（管理面板 QR / 日志）在有 sidecar 时带上 node id，旧手机可忽略 `n`：

```
dshpocket://pair?h=<host:port>&c=<code>&n=<irohNodeId>
```

## 管理 UI（桌面 / `dsh web`）

插件通过官方 **client half**（`dsh.client` + `exports["./client"]`）挂到 dsh 的 slot 体系，**不**使用 `tapIndex` HTML 注入（桌面端 Electron 读自己的 `file://` index，只认结构化 index injection / client modules）。

| 入口 | Slot | 作用 |
|---|---|---|
| 设置 →「手机连接」 | `settings.section` | 官方可叠加设置页：状态 / 配对码+QR / 设备重命名·撤销 |

**账号菜单（设置 / 意见反馈 / 退出登录）无法插入「手机连接」。**  
该弹层由 `@deepseek-ai/dsh-client-ui-settings-account` 的 `AccountMenu` 占用 `settings.launcher`（`kind: 'single'`）。菜单项是写死的数组（`settings` → `contact` → `signout`），**没有**类似 `settings.launcher.item` 的 list slot。替换整个 `settings.launcher` 会盖掉官方 AccountMenu，不算合法扩展。要在「设置」与「意见反馈」之间加一行，需要上游 dsh 新增扩展点。本插件因此**不**往侧栏 footer / 账号菜单乱挂入口。

面板功能：
- 连接器状态（是否在跑、监听地址、插件版本）
- 打开配对窗口：6 位码 + 倒计时 + `dshpocket://pair?…` 二维码
- 已配对设备：名称、最近活跃、是否在线；可重命名 / 撤销

文案走 `ctx.locale.register('pocket', { zh, en })`，跟随 dsh 的语言设置。样式只用 `--dsw-alias-*` 主题 token。

桌面 App（`~/.dsh/profiles/desktop`）与 `dsh web` 共用同一套 client module 加载器：Host 把 `dsh.client` 包打进 boot 图（结构化 `IndexInjection`），Desktop 的 IPC fetch 仍能打到 Host 上的 `/pocket/*` 管理路由。因此本 UI **预期在桌面端同样可用**。

开发时改完 `src/client/**` 后需 `npm run build:client` 生成 `lib/client.js`（lazy-CJS `window.__ModuleLoader__.load` 格式）。

## 配对（M1）

首次启动且还没有任何已配对设备时，会自动打开一个配对窗口，日志里打印 6 位配对码。配对码 10 分钟内有效，最多试 3 次，用一次就作废。之后要再开窗口，有四种方式：
- **管理 UI**：设置 →「手机连接」（推荐）
- CLI：`node bin/pocket.js pair`，用本机的 owner token 连上插件后打开窗口；`node bin/pocket.js devices` 查看设备，`node bin/pocket.js revoke <id>` 撤销设备
- dsh 网页端口上的管理路由（Host 必须是回环权威，拒绝 `sec-fetch-site: cross-site` 与跨站 Origin）：
  - `GET /pocket/status` — 运行状态 / 监听地址 / 版本 / 设备（含 online）
  - `POST /pocket/pair` 打开窗口，`GET` 查状态（含 code），`DELETE` 关闭
  - `GET /pocket/devices` 列表；`POST` `{id}` 撤销；`PATCH` `{id,name}` 重命名
- 在 patch 配置里写 `pair: true`

手机发送 `{"op":"pair","args":{"proto":1,"code":"123456","name":"Pixel"}}` 完成配对，会拿到 `{deviceId, token}`，之后用 `hello` 带这个 token 登录。服务端只保存 token 的 sha256，写在 `$DSH_HOME/pocket/devices.json`，权限 0600。owner token（文件 `pocket/token`）只给本机 CLI 用，只有它能打开配对窗口。

## 审批（M1）

dsh 发起 `approval/request` 时，如果有已登录的设备在线，插件会推送 `{"ev":"approval",approvalId,sessionId,toolName,callId?,reason?}`。设备用 `approval.answer {approvalId, outcome:"allowed-once"|"rejected"}` 答复。手机和网页端谁先答就算谁的：
- 手机先答：网页端那张审批卡会收到 Gateway 发来的 cancel 并自动消失，这一点已在真实 dsh 上验证。
- 网页端先答：手机收到 `approval.closed`，`by` 字段为 `chain`。

工具那边取消时，结果为 `cancelled`。新连上的设备会补发还没处理的审批，也可以用 `approval.pending` 主动查询。

## 断点续订

`follow {sessionId, afterSeq}` 返回的第一帧是 `{"type":"resume", afterSeq, cursor, records:[seq>afterSeq…], projections…}`。如果快照窗口没覆盖到 afterSeq，插件会用 `page` 往前补齐，最多补 10 页；补不齐就退回发完整的 `snapshot`。

## 协议速览（v1）

- 收发的都是 WebSocket 文本帧，内容为 JSON。
- 请求格式：`{"id":1,"op":"…","args":{…}}`。成功返回 `{"id":1,"ok":true,"result":…}`，失败返回 `{"id":1,"ok":false,"error":{"code","message"}}`。
- 连上后第一帧必须是 `hello {proto:1, token}`。其他帧或 token 错误都会以 4401 关闭连接；10 秒内没发 hello 也会被断开。
- 支持的操作：

| 操作 | 说明 |
|---|---|
| `sessions.list` | 会话列表 |
| `sessions.create` | 新建会话（可传 `cwd`） |
| `sessions.rename` | 重命名会话 |
| `prompt` | 发送消息，`mode` 为 `queue` 或 `steer`；`text` 和 `images` 至少给一个 |
| `queue.update` | 调整排队中的消息 |
| `cancel` | 取消当前回复 |
| `history.page` | 翻页拉取历史 |
| `models.catalog` | 模型列表 |
| `models.select` | 切换模型 |
| `follow` | 订阅会话，返回 `subId` |
| `unfollow` | 取消订阅 |
| `permissions.catalog` / `permissions.get` / `permissions.set` | 访问模式（`read-only` / `workspace-write` / `danger-full-access`），走 dsh 的 `permissionPresets` 服务 |
| `presets.list` | Agent 预设列表（standard / ptc / minimal / cordis） |
| `commands.execute` | 斜杠指令（`/compact`、`/plan`、`/goal …`、`/feedback …`），需 `commands` + `agents` |
| `session.export` | 将会话历史打成 ZIP（JSONL），手机端分享/保存 |
| `files.list` / `files.read` | 工作区文件浏览（`workspaceFiles`） |
| `jobs.list` / `jobs.kill` | 后台任务列表与终止（`jobController`） |
| `terminal.list` / `create` / `follow` / `write` / `resize` / `close` | 桌面主机上的真实 PTY（`terminalController`）；`follow` 推送 `{ev:"terminal",subId,frame}`，用 `unfollow` 取消 |

`permissions.*`、`presets.list`、`commands.execute`、`files.*`、`jobs.*`、`terminal.*` 依赖可选服务，服务不在时不出现在 hello 的 `ops` 里。`session.export` 始终注册。

- 服务端推送：

| 推送 | 说明 |
|---|---|
| `{"ev":"follow",subId,frame}` | 原样转发 dsh 的 SessionFollowFrame |
| `follow.end` / `follow.error` | 订阅结束 / 出错 |
| `{"ev":"session",kind,…}` | 会话增删和状态变化 |
| `{"ev":"ping"}` | 每 20 秒一次；60 秒收不到客户端任何消息就断开 |

## 开发

```bash
npm install
npm run build:client       # 生成 lib/client.js
npm run lint && npm test
npm run test:integration   # 需要 PATH 上有 dsh 0.2.1-alpha.2（Node ≥22.19）
```
