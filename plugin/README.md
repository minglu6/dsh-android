# dsh-plugin-pocket

dsh-android 的桌面端桥接插件。它运行在 dsh 进程里，直接调用 dsh 的会话服务，再通过 WebSocket 给手机提供一套简单的 JSON 协议。完整协议见 [../docs/design.md](../docs/design.md) 第 4 节。

## 安装

```bash
dsh plugin --profile web add dsh-plugin-pocket          # 发布到 npm 之后
dsh plugin --profile web add /path/to/dsh-android/plugin # 从源码安装
dsh web
```

启动后日志会出现 `[pocket] remote transport: tailscale (advertise host:port, bind …)`。第一次运行时会生成 token，保存在 `$DSH_HOME/pocket/token`，文件权限 0600。

## 配置（`$DSH_HOME/profiles/web/cordis.patch.yml`）

```yaml
- id: dsh-pocket
  config:
    # transport: tailscale     # 手机路径仅 host:port WS
    # host:                    # 不写则自动选 Tailscale IP，否则局域网 IP；绑定默认 0.0.0.0
    # port: 7690
    # token: 固定 token；不填则使用自动生成的那个
    # iroh: true               # 可选：启动遗留 pocket-host sidecar（不参与手机配对）
    # hostBinary: /path/to/pocket-host
```

## 传输（Tailscale / 局域网）

手机只走 **host:port WebSocket**。插件默认：

1. 若配置了 `host`，用该地址做配对广告（并按该地址绑定）。
2. 否则优先检测本机 Tailscale IP（`100.64.0.0/10` / `.ts.net`），没有则用局域网 IPv4；WS 绑定 `0.0.0.0`。

配对 URI：`dshpocket://pair?h=<host:port>&c=<6位码>`（不再使用 `n=` / `nodeId#code`）。

电脑与手机需在同一 Tailscale tailnet，或同一局域网。

## 管理 UI（桌面 / `dsh web`）

插件通过官方 **client half**（`dsh.client` + `exports["./client"]`）挂到 dsh 的 slot 体系，**不**使用 `tapIndex` HTML 注入（桌面端 Electron 读自己的 `file://` index，只认结构化 index injection / client modules）。

| 入口 | Slot | 作用 |
|---|---|---|
| 设置 →「手机连接」 | `settings.section` | 官方可叠加设置页：状态 / 配对码+QR / 设备重命名·撤销 |

**账号菜单（设置 / 意见反馈 / 退出登录）无法插入「手机连接」。**  
该弹层由 `@deepseek-ai/dsh-client-ui-settings-account` 的 `AccountMenu` 占用 `settings.launcher`（`kind: 'single'`）。菜单项是写死的数组（`settings` → `contact` → `signout`），**没有**类似 `settings.launcher.item` 的 list slot。替换整个 `settings.launcher` 会盖掉官方 AccountMenu，不算合法扩展。要在「设置」与「意见反馈」之间加一行，需要上游 dsh 新增扩展点。本插件因此**不**往侧栏 footer / 账号菜单乱挂入口。

面板功能：
- 连接器状态（配对广告地址、插件版本）
- 打开配对窗口：6 位码 + 倒计时 + QR + 可复制配对串（`h=` URI）
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

`sessions.follow` 支持 `fromSeq`。手机断线重连后带上次 cursor 续订；服务端按 seq 补帧。

## 遗留 iroh sidecar

`plugin/iroh`（`pocket-host`）仍可在 `config.iroh: true` 时启动，供桌面侧实验；**不**再参与手机配对，Android APK 也不再打包 `libpocket_iroh.so` / JNI。
