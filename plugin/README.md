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
- 「连接诊断」显示完整的版本、网络地址、实例 ID 和端口检查；「复制诊断报告」复制全文，也可选择报告文字单独复制

文案走 `ctx.locale.register('pocket', { zh, en })`，跟随 dsh 的语言设置。样式只用 `--dsw-alias-*` 主题 token。

桌面 App（`~/.dsh/profiles/desktop`）与 `dsh web` 共用同一套 client module 加载器：Host 把 `dsh.client` 包打进 boot 图（结构化 `IndexInjection`），Desktop 的 IPC fetch 仍能打到 Host 上的 `/pocket/*` 管理路由。因此本 UI **预期在桌面端同样可用**。

开发时改完 `src/client/**` 后需 `npm run build:client` 生成 `lib/client.js`（lazy-CJS `window.__ModuleLoader__.load` 格式）。

## 原生菜单能力与状态

插件的 `cordis.patch.yml` 将工作区目录选择器固定为官方 `browse`：禁用 Web App 的 `directory-picker` 自动选择条目，再同时加载官方 browse Host 后端与客户端界面，避免重复注册服务和选择器。电脑与手机均使用应用内目录浏览交互；电脑不再弹出 macOS 系统文件夹选择框。桌面界面由官方客户端提供，手机使用对应的原生目录界面，两端共享同一 Host 的列目录与新建文件夹能力。

手机以 `hello.ops` 及后续能力变更为准，不把 Host 上存在某个方法视为可用产品入口：

- 工作区使用 `workspaceController` 的实体、成员关系与归档状态；归档有活动任务时保留官方拒绝详情，由手机确认是否停止活动后再次提交。
- `workspace.directoryList` 只在官方目录选择器为 `browse` 时公布；同一条件下，Host 提供 `directoryPickerController.createDirectory` 才公布 `workspace.directoryCreate`。新建请求为 `{path, name}`（现有父目录绝对路径、单个非空白目录名），返回官方创建的绝对路径字符串；保留合法名称中的空格及官方重名、权限、非法路径等业务错误，不另行实现文件系统操作。macOS `native` 选择器不公布浏览和创建能力，手机仍可输入完整路径注册工作区，不弹出电脑系统选择器。
- 指令目录来自当前 Agent 的 `commands.list`；目标修改使用官方目标的 `id/revision`，不靠目标文本猜测版本。
- 后台任务输出通过 `jobs.follow` 推送 `job` / `job.end` / `job.error`，关闭订阅使用共享 `unfollow`，不会自动终止任务。
- `workspace.changed`、`schedule.changed`、`plugins.changed` 驱动对应页面更新；预设名册和默认值从 Host 读取，配置写入携带观察到的 revision。
- 模型密钥仅经提供商页面写入或清除，不提供独立凭证目录。删除提供商先完成配置 CAS，再清理该页面管理的密钥，避免冲突导致仍在使用的密钥丢失。
- 模型设置订阅 `settings.changed`、`credentials.changed`、`models.changed` 和 `plugins.changed`，对应官方配置、凭据、适配器和插件事件。`credentials.describe {ref}` 仅返回 `ref/configured/source/writable`，不回传密钥值。插件自定义页面直接使用原有官方客户端 API，不维护提供商专属原生镜像。
- 通用设置同样复用 `plugins.settingsDescribe/settingsMutate`，不维护手机独立配置。`settings/document-updated` 广播 `settings.changed {ns, revision}`，驱动手机语言、外观、字体、聊天展示、发送偏好、默认权限与 Session Log 镜像更新；手机写入携带版本并回读，桌面使用原有官方订阅更新。

完整入口和原生边界见 [功能对齐清单](../docs/feature-parity.md)。升级 APK 不会自动重载已运行的桥接进程。

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

- `plugins.list/setEnabled`：包与组件运行态；组件以 `entryId` 关联。
- `plugins.inventory`：独立注入官方 `pluginInventory`，返回全局 `entries` 与会话预设 `agentPresets[].rows`；不依赖包管理服务，也不把预设声明转成全局启停操作。
- `plugins.inspect/registries/install/cancelInstall/waitForInstall/uninstall`：检查来源、安装源选择、安装、取消核对和卸载。支持 npm、Git、tarball、**主机路径**；不读取手机本地插件目录。可选方法仅在 Host 支持时公布。
- `plugins.settingsDescribe/settingsMutate`：调用官方 `settingsController`，读取经过脱敏的 schema/value/secrets；写入必须携带 `expectedRevision`。冲突需要重新读取，不能静默覆盖。
- `schedule.catalog/list/create/update/delete/history`：保留官方记录、时区、投递历史和业务结果。Android“新建”进入会话，已有任务编辑携带完整 `expected` 记录。

`ok: true` 仅表示 RPC 返回：客户端仍需检查插件 `application`（含 `restart-required/overridden/failed/cancelled`）和任务 `updated/deleted/code`。安装等待最长 30 分钟，不沿用普通请求的 60 秒超时；不确定的安装保留 `requestId` 供核对，不自动重复提交。插件自定义 React 页面通过已认证的 Host Web 运行时加载，公开设置也可用原生或高级路径编辑。

## 插件自定义 Web 界面

- Host 同时提供 `webServer` 与 `connection` 时，桥接公布 `ui.open({section?})` 和 `ui.close({ticket?})`。`ui.open` 返回 `{ticket,path:"/pocket-ui/open"}`；原生以表单 POST 单次票据，兑换 `dsh-pocket-web` HttpOnly/SameSite=Strict cookie 和无票据的 303 页面地址。票据 60 秒、网页会话 15 分钟，绑定已配对设备、当前 WS 连接及 authority；普通连接关闭、设备撤销、服务卸载会结束 HTTP/SSE/WS。
- `/`、官方 assets/plugins/API 和 `/api/remote.mux` 由同一手机端口代理到固定的本机 Host listener。Host 启动 URL 只在服务端兑换内部 cookie，不回传启动令牌、Host cookie 或敏感重定向。`/pocket/*`、`/open-in-app/*`、任意上游 URL、路径绕过和跨站请求均拒绝。
- `/?pocket-ui=1&section=models` 使用官方模型设置与第三方卡片；无 section 时保留完整官方客户端。设置深链等待 session baseline，避免官方初始 onboarding 关闭刚打开的设置；不替换根组件，也不更改正常桌面窗口。窄屏设置导航横向滚动。
- 已鉴权的手机页面在官方客户端启动前注入独立的 `__DSH_TRANSPORT__={ownsHost:true}`，声明与原生已配对连接相同的 Host 管理能力。否则官方 settings mirror 会把 LAN/Tailscale 地址判为普通远程网页并拒绝模型设置。上游原有 transport/native/recovery 声明仍被剥离，不继承 `streamBaseUrl`、函数、进程令牌或 Host cookie；未鉴权请求无法取得该页面。验收必须覆盖非回环地址，不能只测 USB `127.0.0.1`。
- 桥接按 `Accept-Encoding` 协商 gzip 压缩 HTML、JavaScript、CSS 和 JSON，减少蜂窝/Tailscale 中转链路上的首屏资源传输；HTML 先脱敏再压缩。SSE、Range/206、HEAD 和上游已压缩的响应不重新编码。
- 自定义页面的请求/WS 由 CSP 限定为当前手机端口 origin。既有 LAN HTTP 不增加传输加密；跨网络使用 Tailscale 或已有安全链路。没有第三方脚本到 Android 的 JavaScript bridge，也不提供 Electron 专属 API、设备文件选择或下载。

## 遗留 iroh sidecar

`plugin/iroh`（`pocket-host`）仍可在 `config.iroh: true` 时启动，供桌面侧实验；**不**再参与手机配对，Android APK 也不再打包 `libpocket_iroh.so` / JNI。
