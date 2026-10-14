# dsh-plugin-pocket

[dsh-android](../README.zh-CN.md) 的 Host 桥接插件。在电脑端 dsh 进程中调用官方会话、配置、工作区等服务，通过带鉴权的 JSON/WebSocket 协议供原生 Android 客户端使用。手机不运行另一套任务数据库、包管理器或模型执行引擎。

## 安装与升级

### 运行前提

- Node.js：`^22.19.0 || >=24.0.0`，不包含 23.x。
- dsh：当前 CI 使用 `@deepseek-ai/dsh@0.2.1-alpha.2`；session-controller peer 范围为 `>=0.2.1-alpha.2 <0.2.2-0`。该范围是依赖声明，不代表每个版本都经过测试。
- `dsh plugin` 需要 PATH 中的 pnpm；CI 使用 `pnpm@10`。
- 手机与电脑可通过局域网或 Tailscale 互访，防火墙允许桥接端口（默认 TCP `7690`）。

### 从源码安装

从仓库根目录执行；尚未安装 dsh／pnpm 时，可先执行根 README 中的 [环境安装命令](../README.zh-CN.md#安装桥接)。不依赖该插件已发布到 npm。

```bash
cd plugin
npm install
npm run build:client
dsh plugin --profile web add "$PWD"
dsh web
```

桌面 App 使用 `desktop` profile，需在该 profile 安装，而不是只装到 `web`：

```bash
dsh plugin --profile desktop add "$PWD"
```

随后重启相应 Host。管理 UI 通过官方 client half（`dsh.client` 与 `exports["./client"]`）注册到 **设置 → 手机连接**；不修改官方账号菜单，也不使用 HTML 注入替换桌面入口。

更新源码后重新安装依赖、执行 `npm run build:client`，按相同 profile 安装流程更新并重启 Host。尤其是修改 `src/client/**` 后必须重建 `lib/client.js`。**升级 APK 不会自动更新或重载电脑端桥接。**

本轮文件改动通过 `workspaceChanges.summary {sessionId, seq}` 返回 Host 的完整摘要，通过 `workspaceChanges.diff {sessionId, seq, index}` 返回该轮指定文件的快照差异。`index` 是摘要文件列表的原始下标；只公布 Host 实际提供的方法，并传递请求取消信号。摘要／差异已释放时返回 `null`，不改读当前工作区文件。旧桥接需重启才能启用新增的差异接口。

## 配置与网络

以 Web profile 为例，在 `$DSH_HOME/profiles/web/cordis.patch.yml` 配置（默认 `DSH_HOME` 为 `~/.dsh`）；桌面使用对应的 `desktop` profile。所有配置项均可省略：

```yaml
- id: dsh-pocket
  config:
    # host: 192.168.1.10       # 指定监听接口；不写则绑定 0.0.0.0
    # port: 7690
    # dataDir: /path/to/pocket # 覆盖默认 $DSH_HOME/pocket 数据目录
    # token: <owner-token>    # 可选固定 owner token，通常使用自动生成值
    # pair: true             # 启动时打开配对窗口
    # iroh: true             # 仅用于遗留桌面 sidecar 实验
    # hostBinary: /path/to/pocket-host
```

手机链路仅为 **host:port WebSocket**。内部的 `tailscale` 传输标识不代表必须安装 Tailscale：

- 未指定 `host` 时绑定 `0.0.0.0`，默认公布局域网 IPv4，没有局域网地址时使用可用的 Tailscale 地址。
- 两类地址均可用时，「手机连接」可切换 **局域网 / Tailscale**；地址、二维码与复制链接随之变化，不重启服务或打断已配对连接。
- 指定具体 `host` 会限制监听与公布的接口；希望两类网络均可连接时，应省略该项或使用 `0.0.0.0`。
- 刷新重新发现网卡；明确选择的网络不可用时会报错，不静默切换到另一网络。没有可用地址时禁用配对。
- 每类网络使用首个可用 IPv4，不支持 IPv6-only 配对。局域网需要关闭客户端隔离；Tailscale 需要双方处于可互访的 tailnet。

默认全接口监听不是公网部署方案。请用防火墙限制访问，跨网络使用 Tailscale 或已有可信加密链路；`ws://` 与 LAN HTTP 本身没有 TLS，配对不能防止未加密链路上的窃听。

插件的 `cordis.patch.yml` 还会禁用默认 `directory-picker` 自动选择条目，并加载官方 `browse` 后端和客户端。电脑与手机因此使用应用内目录浏览，电脑不再弹出 macOS 系统目录选择器。

## 配对、设备与诊断

首次启动且没有已配对设备时会自动打开配对窗口，并在日志中打印 6 位码。配对码 **10 分钟有效、最多尝试 3 次、成功后单次作废**；请勿公开启动日志或配对二维码。

管理 UI 支持选择网络、复制地址、打开／关闭配对窗口、显示 QR、设备重命名与撤销，以及完整连接诊断。配对 URI：

```text
dshpocket://pair?h=<host:port>&c=<6位码>
```

### 本机 CLI

在 `plugin/` 目录执行：

```bash
node bin/pocket.js pair
node bin/pocket.js devices
node bin/pocket.js revoke <device-id>
```

默认使用 `ws://127.0.0.1:7690` 和 `$DSH_HOME/pocket/token`。自定义端口或数据目录时可指定 `--url ws://127.0.0.1:<port>`、`--token-file /path/to/token`。CLI 使用本机 owner token，手机应使用配对所得设备 token，不应共享 owner token。

### 凭据与撤销

手机发送 `pair {proto:1, code, name}` 获得 `{deviceId, token}`，之后用 `hello` 携带设备 token 登录。默认存储如下；设置 `dataDir` 后位置随之变化：

| 文件 | 内容与保护 |
|---|---|
| `$DSH_HOME/pocket/token` | 自动生成的 owner token，权限 `0600`；配置固定 `token` 时不生成该文件 |
| `$DSH_HOME/pocket/devices.json` | 设备记录与设备 token 的 SHA-256，权限 `0600`，不保存设备 token 明文 |

WebSocket 管理操作要求 owner 身份。管理 UI 通过受本机信任边界保护的 HTTP 路由操作；已配对手机不因此获得开配对窗口的 owner 凭据。撤销设备会断开其已认证连接。配对设备仍具有广泛的 Host 控制能力，包括会话、终端与配置，请按主机管理权限保护它。

### 本机管理路由

以下路由在 **dsh 网页端口**，不是普通手机 RPC；要求 Host 为回环 authority，并拒绝 `Sec-Fetch-Site: cross-site` 与跨站 Origin：

| 路由 | 用途 |
|---|---|
| `GET /pocket/status[?network=lan|tailscale]` | 状态、网络候选、选中连接类型、地址、版本与设备 |
| `POST /pocket/pair[?network=lan|tailscale]` | 打开配对窗口 |
| `GET /pocket/pair[?network=lan|tailscale]` | 查询窗口与对应网络的 URI |
| `DELETE /pocket/pair` | 关闭窗口 |
| `GET /pocket/devices` | 设备列表 |
| `POST /pocket/devices`，body `{id}` | 撤销设备 |
| `PATCH /pocket/devices`，body `{id,name}` | 重命名设备 |

`network` 缺省时优先 LAN；未知值返回 400，不可用网络返回 503，均不打开配对窗口。诊断报告包含完整地址、实例 ID、版本及端口检查结果，可复制全文或局部内容；分享前需检查敏感信息。

## Host 能力与操作结果

客户端以 `hello.ops` 及后续能力变更为准，不把 Host 上存在某个方法视为必定可用的产品入口。

| 领域 | 必须保留的契约 |
|---|---|
| 工作区 | 使用官方实体、成员关系和归档状态；有活动任务时保留官方拒绝详情，由用户确认停止后再提交。 |
| 目录选择 | `workspace.directoryList` 仅在官方 `browse` 模式公布；后端支持创建时才公布 `workspace.directoryCreate {path,name}`。返回官方绝对路径，保留空格及业务错误，不另写文件系统实现。仅有 `native` 时手机可输入完整路径，不弹电脑系统窗口。 |
| 指令与目标 | 指令来自当前 Agent 的 `commands.list`；目标更新使用官方 `id/revision`，不能从文本猜版本。 |
| `@` 引用 | 两类查询通过官方 `sessionController.resolveAgent(sessionId)` 加载或恢复 Agent；Host 重载后的历史会话也可直接查询，不以先发送消息为前提。`references.files` 调用 Host `fileReferences.list(agent, query, signal)`，返回 `{items:[{path,kind}]}`；`references.sessions` 调用 `sessionReferenceResolver.remoteExportCandidates(agent, query, signal)`（`candidates` 只是 Remote 名称），保留官方 canonical `mention`，将 `displayTitle ?? label` 映射为 `title`。`kind=subagent` 仅用于 `sessionQuery` 中 `origin=subagent` 且 `parentSession` 为当前会话的直接子智能体，其他候选为 `session`；普通 fork 不算子智能体。 |
| 后台任务 | `jobs.follow` 推送 `job` / `job.end` / `job.error`，共享 `unfollow` 只取消订阅，不终止任务。 |
| 页面更新 | `workspace.changed`、`schedule.changed`、`plugins.changed` 驱动对应页面；预设目录和默认值来自 Host。 |
| 共享设置 | `plugins.settingsDescribe/settingsMutate` 复用官方配置；写入携带 `expectedRevision`，冲突重新读取，不静默覆盖。`settings.changed {ns,revision}` 通知手机更新镜像。 |
| 模型与凭据 | 订阅 `settings.changed`、`credentials.changed`、`models.changed`、`plugins.changed`。`credentials.describe {ref}` 仅返回状态元数据，不回传密钥；删除提供商先完成配置 CAS，再清理该页面管理的密钥。 |
| 插件目录 | `plugins.inventory` 独立返回全局 `entries` 和 `agentPresets[].rows`，不依赖包管理，也不将会话预设声明转成全局开关。 |
| 插件管理 | `plugins.list/setEnabled` 以 `entryId` 关联组件；检查、安装、取消、等待与卸载按 Host 可用方法公布。来源支持 npm、Git、tarball 和主机路径，不是手机本地目录。 |
| 自动化 | `schedule.catalog/list/create/update/delete/history` 保留官方记录、时区与业务结果；编辑带完整 `expected` 记录。手机新建入口进入会话，投递成功不表示模型执行完成。 |

`ok: true` 只表示 RPC 返回。客户端还需检查插件 `application`（如 `restart-required/overridden/failed/cancelled`）与任务 `updated/deleted/code`。安装等待最长 30 分钟，不沿用普通 RPC 的 60 秒超时；不确定的安装保留 `requestId` 供核对，不能自动重复提交。

完整原生入口与限制见 [功能对齐清单](../docs/feature-parity.md) 和 [Android 使用说明](../app/README.md)。

## 会话流、审批与恢复

- `follow` 支持 `afterSeq`，断线重连携带上次 cursor 续订，Host 按序补帧。手机的排队消息以 Host 收件箱为准；`queue` 等待后续轮次，`steer` 引导当前轮次，详见 [发送语义](../app/README.md#排队与插话)。
- Host 发起 `approval/request` 时向在线设备推送 `approval`。`approval.answer` 接受 `allowed-once` 或 `rejected`，手机与网页谁先答谁生效；另一端收到关闭／取消通知。工具取消对应 `cancelled`，新连接补发待审批项，也可用 `approval.pending` 查询。
- 用量与权限来自 `sessionStats`、`tokenUsage`、`contextPressure`、`contextBreakdown`、`permissions` 等 Host 投影，仅推送给已认证且订阅该会话的客户端。
- 首个 snapshot/resume 前的投影变更按 key 暂存，先发基线再发较新投影。**投影 `seq` 是投影水位，不用于推进历史 cursor。**
- `permissions.catalog` 返回 `options/defaultOptions/defaultPreset`；自动审查只在 Host 注册 `auto` 时可选，且不能作为默认权限。`permissions.get/set` 保持 `{current:"…"}` 格式，目录变更通过 `permission-presets/catalog-changed` 通知客户端重新读取。

上述实时通知需要运行相应版本桥接；只更新 App 无法让旧 Host 产生新事件。

### 双端完成未读状态

- Host 桥接维护 `{epoch, revision, unreadIds}`，运行开始清除旧提醒，运行结束且没有查看端时产生提醒；历史空闲会话不会在初始化时全部标为未读。
- 手机通过 `completion.state` 读取快照、`completion` 事件接收更新，使用 `completion.view {sessionId:string|null}` 声明当前实际可见会话。`follow` 不等于查看；连接断开自动释放查看状态，未读保留至查看或 Host／桥接重启。
- 桌面通过同源 `GET /pocket/completion/events` SSE 接收 `{clientId,...snapshot}`，以 `POST /pocket/completion/view {clientId,sessionId}` 回写查看状态。路由沿用本机 Host/Origin 信任边界；SSE 断开后旧 `clientId` 失效。
- 桌面优先使用 `uiSession.setCompletionUnreadSource`（若实际挂载的 client 提供）。官方 `ui-session@0.2.1-alpha.2` 没有该钩子：改为叠加同一对象上的 `sessionStatus`（`completionUnread` 以 Host 快照为准，running/pending 仍来自官方），并通知官方 `statusListeners`。缺少 `sessionStatus` 或监听集合则抛错，不 warn 后假装已同步。
- 页面失焦、隐藏、全局面板／设置覆盖或内容未加载时不确认已读。手机插件设置 WebView 不代理上述路由，由原生会话界面管理查看状态。客户端按连接代际和快照版本拒绝过期响应，防止绿点被旧数据恢复。

## 插件自定义 Web 界面

这是插件详情的可选入口，不是原生模型设置或内置插件目录的默认实现。仅当 Host 提供 `webServer` 与 `connection` 时公布 `ui.open({section?})` / `ui.close({ticket?})`。

1. `ui.open` 返回 `{ticket,path:"/pocket-ui/open"}`；原生以表单 POST 兑换 `dsh-pocket-web` HttpOnly / SameSite=Strict cookie，并跳转至不带票据的 303 地址。
2. 单次票据有效 60 秒，网页会话 15 分钟，绑定设备、当前 WS 连接与 authority；断连、撤销或卸载结束相关 HTTP/SSE/WS 会话。
3. 根页面、官方 assets/plugins/API 及 `/api/remote.mux` 代理到固定本机 Host listener。启动令牌只在服务端兑换内部 cookie，不向手机页面回传 Host 启动令牌、Host cookie 或敏感重定向。
4. 拒绝 `/pocket/*`、`/open-in-app/*`、任意上游 URL、路径绕过和跨站请求。页面请求与 WS 由 CSP 限定为当前手机端口 origin；不提供第三方脚本到 Android 的 JavaScript bridge、Electron API、设备文件选择或下载。

`/?pocket-ui=1&section=models` 可打开官方模型设置及第三方卡片；无 section 保留完整官方客户端。设置深链等待 session baseline，不替换根组件或影响正常桌面窗口。

已认证页面在官方客户端启动前注入独立的 `__DSH_TRANSPORT__={ownsHost:true}`，使 LAN／Tailscale 页面具备与原生已配对连接相同的 Host 管理声明。上游 transport/native/recovery 声明被剥离，不继承其函数、`streamBaseUrl`、进程令牌或 Host cookie；未认证请求无法取得该页面。修改这条链路时必须验证非回环地址，不能只测 USB `127.0.0.1`。

HTML 在脱敏后按 `Accept-Encoding` 协商 gzip，JavaScript/CSS/JSON 同样可压缩；SSE、Range/206、HEAD 与上游已压缩响应不重编码。以上措施**不增加 LAN HTTP 的传输加密**，也不保证任意第三方插件兼容或可信。Android WebView 的 cookie 存储仍为应用级共享；服务端仅接受唯一有效且匹配当前 authority 的会话。

## 开发与测试

在 `plugin/` 目录执行：

```bash
npm install
npm run build:client
npm run lint
npm test
```

`npm test` 的 `pretest` 会重建客户端。若需覆盖遗留 sidecar 相关测试，先准备 Rust 工具链并执行 `npm run build:iroh`；CI 会先构建 sidecar，再将二进制提供给插件测试。

真实 Host 集成测试需要 PATH 中的 dsh 与 pnpm：

```bash
npm run test:integration
```

只验证真实 Host 的 `@` 引用契约（文件候选、标题映射、canonical mention、自身排除、Host 重启后旧会话的并发引用查询及不存在会话的真实错误），可执行 `node --test --test-name-pattern='file and session reference contracts' test/integration/dsh.test.js`。测试使用临时 `DSH_HOME` 与回环桥接端口，复用临时目录验证真实进程重启，不重启当前桌面 Host；它调用真实服务，不用同名 mock 模拟 Host 方法。PATH 中没有 `dsh` 时会跳过，不能把跳过视为通过。

Android 协议联调还需 JDK 17 与 Android 开发环境：

```bash
node test/integration/kotlin-live.mjs
```

该脚本启动临时真实 `dsh web` 并执行 Kotlin 协议测试。真实模型测试需要额外凭据，会调用模型与工具，仅供显式本地执行，见 [Android 测试说明](../app/README.md#构建与测试入口)。

### 协议与源码导航

- [设计文档第 4 节](../docs/design.md#4-protocol-spec-v1-json-over-websocket-text-frames)：协议 envelope 与早期核心操作；该文档含历史规划，不是全部当前 RPC 的清单。
- [上游 API 笔记](../docs/api-notes.md)：dsh Remote RPC、会话事件及权限模型研究。
- [`src/server.js`](src/server.js)：鉴权、操作分发与会话订阅；[`src/parity-ops.js`](src/parity-ops.js)、[`src/settings-ops.js`](src/settings-ops.js)、[`src/extra-ops.js`](src/extra-ops.js)：扩展操作。
- [`src/host-ui.js`](src/host-ui.js)：插件自定义页面的 Host 代理；[`src/manage.js`](src/manage.js)：本机管理路由。
- [Android `PocketClient.kt`](../app/core/protocol/src/main/kotlin/dev/dsh/pocket/protocol/PocketClient.kt)：当前客户端协议调用入口。
- [CI 配置](../.github/workflows/ci.yml)：固定 dsh 版本、插件检查与 Android 联调流程；[第三方声明](../docs/THIRD_PARTY_NOTICES.md)：上游参考与资产归属。

## 遗留桌面 sidecar

`iroh/` 中的 `pocket-host` 仅在 `config.iroh: true` 时启动，供桌面侧实验，不参与手机配对。Android APK 不包含 `libpocket_iroh.so`／JNI。普通 Android 使用者无需构建或安装它；CI 仍构建 Linux x64 与 macOS arm64 二进制供相关测试和可选发布产物使用。
