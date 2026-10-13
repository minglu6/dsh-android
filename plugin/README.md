# dsh-plugin-pocket

dsh-android 的桌面端桥接插件。它运行在 dsh 进程里，直接调用 dsh 的会话服务，再通过 WebSocket 给手机提供一套简单的 JSON 协议。完整协议见 [../docs/design.md](../docs/design.md) 第 4 节。

## 安装

```bash
dsh plugin --profile web add dsh-plugin-pocket          # 发布到 npm 之后
dsh plugin --profile web add /path/to/dsh-android/plugin # 从源码安装
dsh web
```

启动后日志会出现 `[pocket] remote transport: tailscale (bind …)`，随后显示实际监听端口和默认配对地址。`tailscale` 是沿用的 WebSocket 传输标识，不代表必须安装 Tailscale。第一次运行时会生成 token，保存在 `$DSH_HOME/pocket/token`，文件权限 0600。

## 配置（`$DSH_HOME/profiles/web/cordis.patch.yml`）

```yaml
- id: dsh-pocket
  config:
    # transport: tailscale     # 手机路径仅 host:port WS
    # host:                    # 不写则默认局域网，可选 Tailscale；绑定默认 0.0.0.0
    # port: 7690
    # token: 固定 token；不填则使用自动生成的那个
    # iroh: true               # 可选：启动遗留 pocket-host sidecar（不参与手机配对）
    # hostBinary: /path/to/pocket-host
```

## 传输（Tailscale / 局域网）

手机只走 **host:port WebSocket**。插件默认：

1. 不配置 `host` 时，WS 绑定 `0.0.0.0`，默认使用局域网 IPv4；无局域网地址时使用可用的 Tailscale 地址。
2. 同时有两类地址时，设置页显示「局域网 / Tailscale」切换；地址、二维码和复制的配对链接同步切换，不重启服务、不影响已配对设备。
3. 配置具体 `host` 时，仅公布该监听接口的地址。若希望两类网络均可连接，请移除具体 `host` 配置；配置 `0.0.0.0` 也可。
4. 仅有一种可用网络时不显示切换控件；无可用地址时禁用配对。刷新会重新发现网卡，明确选择的网络不可用时会报错，不悄悄生成另一网络的二维码。

配对 URI：`dshpocket://pair?h=<host:port>&c=<6位码>`。每类网络使用第一个可用 IPv4 地址，不支持 IPv6-only 配对地址。

局域网连接无需 Tailscale；手机与电脑需在同一可互访网络，防火墙允许配对端口，Wi-Fi 不启用设备隔离。Tailscale 连接要求两端在可互访的 tailnet 中。

## 管理 UI（桌面 / `dsh web`）

插件通过官方 **client half**（`dsh.client` + `exports["./client"]`）挂到 dsh 的 slot 体系，**不**使用 `tapIndex` HTML 注入（桌面端 Electron 读自己的 `file://` index，只认结构化 index injection / client modules）。

| 入口 | Slot | 作用 |
|---|---|---|
| 设置 →「手机连接」 | `settings.section` | 官方可叠加设置页：状态 / 配对码+QR / 设备重命名·撤销 |

**账号菜单（设置 / 意见反馈 / 退出登录）无法插入「手机连接」。**  
该弹层由 `@deepseek-ai/dsh-client-ui-settings-account` 的 `AccountMenu` 占用 `settings.launcher`（`kind: 'single'`）。菜单项是写死的数组（`settings` → `contact` → `signout`），**没有**类似 `settings.launcher.item` 的 list slot。替换整个 `settings.launcher` 会盖掉官方 AccountMenu，不算合法扩展。要在「设置」与「意见反馈」之间加一行，需要上游 dsh 新增扩展点。本插件因此**不**往侧栏 footer / 账号菜单乱挂入口。

面板功能：
- 简洁的服务状态、网络选择和可复制地址；主页面不显示版本、协议和操作说明
- 打开配对窗口：6 位码、倒计时、二维码、复制配对链接及关闭/重新配对
- 已配对设备：数量、名称、在线状态及简短时间；重命名、二次确认后解除配对
- 「连接诊断」展开脱敏报告，保留版本、网络、端口检查和报告复制

文案走 `ctx.locale.register('pocket', { zh, en })`，跟随 dsh 的语言设置。样式只用 `--dsw-alias-*` 主题 token。

桌面 App（`~/.dsh/profiles/desktop`）与 `dsh web` 共用同一套 client module 加载器：Host 把 `dsh.client` 包打进 boot 图（结构化 `IndexInjection`），Desktop 的 IPC fetch 仍能打到 Host 上的 `/pocket/*` 管理路由。因此本 UI **预期在桌面端同样可用**。

开发时改完 `src/client/**` 后需 `npm run build:client` 生成 `lib/client.js`（lazy-CJS `window.__ModuleLoader__.load` 格式）。

## 配对（M1）

首次启动且还没有任何已配对设备时，会自动打开一个配对窗口，日志里打印 6 位配对码。配对码 10 分钟内有效，最多试 3 次，用一次就作废。之后要再开窗口，有四种方式：
- **管理 UI**：设置 →「手机连接」（推荐）
- CLI：`node bin/pocket.js pair`，用本机的 owner token 连上插件后打开窗口；`node bin/pocket.js devices` 查看设备，`node bin/pocket.js revoke <id>` 撤销设备
- dsh 网页端口上的管理路由（Host 必须是回环权威，拒绝 `sec-fetch-site: cross-site` 与跨站 Origin）：
  - `GET /pocket/status[?network=lan|tailscale]` — 状态、可用 `networks`、实际选中的 `connectionType`、地址、版本、设备
  - `POST /pocket/pair[?network=lan|tailscale]` 打开窗口；`GET` 支持相同选择并返回对应 URI；`DELETE` 关闭
  - `network` 缺省时优先 LAN；未知值返回 400，不可用网络返回 503，均不打开配对窗口
  - `GET /pocket/devices` 列表；`POST` `{id}` 撤销；`PATCH` `{id,name}` 重命名
- 在 patch 配置里写 `pair: true`

手机发送 `{"op":"pair","args":{"proto":1,"code":"123456","name":"Pixel"}}` 完成配对，会拿到 `{deviceId, token}`，之后用 `hello` 带这个 token 登录。服务端只保存 token 的 sha256，写在 `$DSH_HOME/pocket/devices.json`，权限 0600。owner token（文件 `pocket/token`）只给本机 CLI 用，只有它能打开配对窗口。

## 审批（M1）

dsh 发起 `approval/request` 时，如果有已登录的设备在线，插件会推送 `{"ev":"approval",approvalId,sessionId,toolName,callId?,reason?}`。设备用 `approval.answer {approvalId, outcome:"allowed-once"|"rejected"}` 答复。手机和网页端谁先答就算谁的：
- 手机先答：网页端那张审批卡会收到 Gateway 发来的 cancel 并自动消失，这一点已在真实 dsh 上验证。
- 网页端先答：手机收到 `approval.closed`，`by` 字段为 `chain`。

工具那边取消时，结果为 `cancelled`。新连上的设备会补发还没处理的审批，也可以用 `approval.pending` 主动查询。

## 断点续订

`follow` 支持 `afterSeq`。手机断线重连后带上次 cursor 续订；服务端按 seq 补帧。

手机的统计栏使用 Host `sessionStats`、`tokenUsage`、`contextPressure`、`contextBreakdown` 投影，当前权限使用 `permissions` 投影。桥接订阅 `sessionProjections.onChanged`，只向正在 follow 对应会话的已认证客户端发送：

```json
{"ev":"follow","subId":"s1","frame":{"type":"projection","sessionId":"…","key":"contextPressure","value":{"pressureTokens":15437,"projectedTokens":15501,"contextWindow":180000},"seq":22}}
```

首个 snapshot/resume 发送前发生的变更按 key 暂存，先发送基线，再发送较新的投影。`seq` 是投影水位，不用于推进历史 cursor。

`permissions.catalog` 返回完整 `options`、`defaultOptions`、`defaultPreset`。自动审查只在 Host 注册 `auto` 时可选，且不允许作为默认权限；`permissions.get/set` 保持既有 `{current: "…"}` 返回格式。Host 目录变化时广播 `{"ev":"permission-presets/catalog-changed"}`，客户端撤回旧选项并重新读取。新统计的实时更新与目录失效通知需要运行本版桥接；只更新 APK 不会自动重载电脑上的插件。

## 插件与自动化管理

Android 页面直接使用官方 Host 服务，不另建定时任务数据库或包管理器：

- `plugins.list/setEnabled/inventory`：包与组件运行态；组件以 `entryId` 关联。
- `plugins.inspect/registries/install/cancelInstall/waitForInstall/uninstall`：检查来源、安装源选择、安装、取消核对和卸载。支持 npm、Git、tarball、**主机路径**；不读取手机本地插件目录。可选方法仅在 Host 支持时公布。
- `plugins.settingsDescribe/settingsMutate`：调用官方 `settingsController`，读取经过脱敏的 schema/value/secrets；写入必须携带 `expectedRevision`。冲突需要重新读取，不能静默覆盖。
- `schedule.catalog/list/create/update/delete/history`：保留官方记录、时区、投递历史和业务结果。Android“新建”进入会话，已有任务编辑携带完整 `expected` 记录。

`ok: true` 仅表示 RPC 返回：客户端仍需检查插件 `application`（含 `restart-required/overridden/failed/cancelled`）和任务 `updated/deleted/code`。安装等待最长 30 分钟，不沿用普通请求的 60 秒超时；不确定的安装保留 `requestId` 供核对，不自动重复提交。插件专属 React 界面不能由原生 Android 执行，公开设置可用原生或高级路径编辑。

## 遗留 iroh sidecar

`plugin/iroh`（`pocket-host`）仍可在 `config.iroh: true` 时启动，供桌面侧实验；**不**再参与手机配对，Android APK 也不再打包 `libpocket_iroh.so` / JNI。
