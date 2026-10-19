# dsh-android

[English](README.md) | **简体中文**

[DeepSeek Harness（dsh）](https://github.com/deepseek-ai/deepseek-harness) 的非官方原生 Android 遥控客户端。手机仅作控制端：模型调用、工具执行、文件、终端与自动化任务仍在电脑 Host 上运行。本仓库不由 DeepSeek 官方开发或维护。

应用使用 Kotlin 与 Jetpack Compose 实现。它不是把桌面网页套进 WebView，也不在手机上运行本地 Agent。

[下载 APK](https://github.com/minglu6/dsh-android/releases/download/dsh-android-latest/dsh-android.apk) · [Android 说明](app/README.md) · [Host 插件](plugin/README.md)

## 功能特性

可用能力取决于 Host 版本与已启用的插件。下表对应当前客户端与桥接的实现，不是规划清单。

| 领域 | 能力 |
| --- | --- |
| 会话 | 流式聊天、思考与工具过程、历史记录、排队／插话发送、审批与提问、模型和推理等级选择 |
| 工作区 | 会话分组、注册与切换主机工作区、目录浏览与搜索、文件预览 |
| 主机工具 | PTY 终端、后台任务、会话轨迹与数据诊断；另提供手机端浏览器面板 |
| 配置 | Host 模型提供商、Agent 预设、共享通用设置、插件管理与内置插件目录 |
| 自动化 | 任务搜索、规则编辑、投递历史和删除；新建入口进入会话 |
| 连接 | 扫码／配对链接、局域网 mDNS 发现、多主机管理、断线按 cursor 续订 |

## 界面截图

截图由当前 Compose 界面在 Robolectric 中渲染，使用演示数据，不包含私人会话或真实凭据。

<table>
  <tr>
    <td align="center"><a href="docs/screenshots/showcase/chat.png"><img src="docs/screenshots/showcase/chat.png" width="250" alt="聊天与运行过程" /></a><br />聊天与运行过程</td>
    <td align="center"><a href="docs/screenshots/showcase/workspace.png"><img src="docs/screenshots/showcase/workspace.png" width="250" alt="工作区与会话导航" /></a><br />工作区与会话导航</td>
    <td align="center"><a href="docs/screenshots/showcase/models.png"><img src="docs/screenshots/showcase/models.png" width="250" alt="模型提供商配置" /></a><br />模型提供商配置</td>
  </tr>
  <tr>
    <td align="center"><a href="docs/screenshots/showcase/terminal.png"><img src="docs/screenshots/showcase/terminal.png" width="250" alt="主机终端" /></a><br />主机终端</td>
    <td align="center"><a href="docs/screenshots/showcase/plugins.png"><img src="docs/screenshots/showcase/plugins.png" width="250" alt="插件管理" /></a><br />插件管理</td>
    <td align="center"><a href="docs/screenshots/showcase/schedule.png"><img src="docs/screenshots/showcase/schedule.png" width="250" alt="自动化任务" /></a><br />自动化任务</td>
  </tr>
</table>

## 架构

```
Android 应用（dev.dsh.pocket）         桌面
┌──────────────────────────┐          ┌─────────────────────────────────────┐
│ Kotlin / Compose 客户端  │  JSON    │ dsh Host                            │
│ 仅负责展示与控制         │  over WS │  └─ dsh-plugin-pocket               │
│                          │◄────────►│      进程内调用 Host 服务           │
│ core/protocol            │ host:port│      带鉴权的 JSON 协议             │
└──────────────────────────┘          │                                     │
                                      │ 可选：pocket-host（iroh）           │
                                      │ 仅当 config.iroh = true             │
                                      └─────────────────────────────────────┘
```

手机通过带鉴权的 JSON/WebSocket 协议连接 `dsh-plugin-pocket`。插件运行在 dsh 进程内，直接调用 Host 的会话、工作区、设置、终端、插件与自动化服务。手机不维护另一套任务数据库、包管理器或模型执行引擎。

### 手机链路：局域网或 Tailscale WebSocket

Android 客户端只使用 **host:port WebSocket**。APK 不包含 `libpocket_iroh.so` 或 JNI。

- 默认监听 `0.0.0.0:7690`。配对只公布 IPv4，不支持 IPv6-only 配对。
- 同时存在局域网 IPv4 与 Tailscale 地址（`100.64.0.0/10` 或 `*.ts.net`）时，**设置 → 手机连接** 可切换 **局域网 / Tailscale**。公布地址、二维码与复制链接随之变化，不重启监听，也不打断已配对连接。
- 插件通过 mDNS 广播 `_dshpocket._tcp`。同一局域网内，手机可从「附近设备」发现主机。
- 局域网路径为 `ws://`／HTTP，桥接本身不提供 TLS。配对用于设备鉴权，不等于传输加密。跨网络应使用 Tailscale 或已有可信加密链路。不要将 7690 端口直接暴露到公网。
- Wi-Fi 客户端隔离会阻断局域网配对。Tailscale 需要双方处于可互访的 tailnet。

### 可选的桌面 iroh sidecar

`plugin/iroh/pocket-host` 仅在插件配置 `iroh: true` 时启动。这是遗留的桌面实验路径，**不参与手机配对**。

- sidecar 与插件通过 stdio 交换换行分隔的 JSON，并以 ALPN `dsh-pocket/1` 接受 iroh 对端。
- 发现使用 iroh N0（pkarr）。默认中继为自建东京节点 `https://iroh.9643275.xyz.`。桌面 `pocket-host` 可通过逗号分隔的环境变量 `DSH_IROH_RELAY_URLS` 覆盖中继。
- 经过 8 秒升级等待后，sidecar 将路径报告为 `direct` 或 `relay`。
- 普通 Android 使用者无需构建或安装该组件。CI 仍构建 Linux x64 与 macOS arm64 二进制，供插件测试和滚动发布使用。

## 下载与安装

运行前提：

- 手机：Android 8.0（API 26）或更新版本。
- 电脑：已安装 dsh，且 Host 保持运行。当前 CI 集成测试固定使用 `@deepseek-ai/dsh@0.2.1-alpha.2`。插件声明的 session-controller peer 范围为 `>=0.2.1-alpha.2 <0.2.2-0`，这是依赖声明，不表示范围内每个版本都已验证。
- Node.js：`^22.19.0 || >=24.0.0`，即 22.x 的 22.19.0 及以上，或 24.0.0 及以上；不包含 23.x。`dsh plugin` 需要 PATH 中有 pnpm，CI 使用 `pnpm@10`。
- 网络：手机与电脑处于可互访的局域网，或同一可互访的 Tailscale 网络。防火墙需允许桥接端口（默认 TCP `7690`）。

最新 release APK（开启 R8 与资源压缩）：

https://github.com/minglu6/dsh-android/releases/download/dsh-android-latest/dsh-android.apk

[滚动预发布页面](https://github.com/minglu6/dsh-android/releases/tag/dsh-android-latest) 上的相关产物：

- [dsh-android-debug.apk](https://github.com/minglu6/dsh-android/releases/download/dsh-android-latest/dsh-android-debug.apk)：debug 构建。
- `pocket-host-linux-x64` 与 `pocket-host-darwin-arm64`：可选桌面 sidecar 二进制。

按系统提示允许所用浏览器或文件管理器安装 APK。**release 构建不代表一定使用发布密钥签名**：CI 配齐签名 Secrets 时使用指定密钥，否则回退到 debug keystore。发布说明中的 `Signing=secrets` 或 `Signing=debug-fallback` 标明构建方式。签名不同的 APK 无法直接覆盖安装。

## 桌面插件安装与配对

插件未发布到 npm。请从本仓库安装到实际使用的 dsh profile。

### 安装桥接

以下示例使用 `web` profile。如果尚未安装 dsh 与 pnpm，可按当前 CI 的版本安装：

```bash
npm install -g pnpm@10 @deepseek-ai/dsh@0.2.1-alpha.2
```

```bash
git clone https://github.com/minglu6/dsh-android.git
cd dsh-android/plugin
npm install
npm run build:client
dsh plugin --profile web add "$PWD"
dsh web
```

桌面 App 使用 `desktop` profile，需在该 profile 安装，而不是只装到 `web`：

```bash
dsh plugin --profile desktop add "$PWD"
```

安装或升级后重启对应 Host。管理入口通过官方 client half 注册到 **设置 → 手机连接**，不注入账号菜单。

更新源码后重新安装依赖、执行 `npm run build:client`（修改 `src/client/**` 后必须重建），按相同 profile 再次安装并重启 Host。安装新 APK 不会更新或重载电脑上的桥接。

可选配置写在 `$DSH_HOME/profiles/<profile>/cordis.patch.yml`（默认 `DSH_HOME` 为 `~/.dsh`）。所有字段均可省略：

```yaml
- id: dsh-pocket
  config:
    # host: 192.168.1.10       # 指定监听／公布接口；不写则绑定 0.0.0.0
    # port: 7690
    # dataDir: /path/to/pocket # 默认 $DSH_HOME/pocket
    # token: <owner-token>     # 可选固定 owner token
    # pair: true               # 启动时打开配对窗口
    # iroh: true               # 仅用于遗留桌面 sidecar
    # hostBinary: /path/to/pocket-host
```

插件还会禁用默认 `directory-picker` 自动选择条目，并加载官方 `browse` 后端和客户端，使电脑与手机使用应用内目录浏览。

### 配对手机

1. 电脑打开 **设置 → 手机连接**，选择局域网或 Tailscale，并打开配对窗口。首次启动且没有已配对设备时会自动打开窗口，并在 Host 日志中打印 6 位码。
2. 手机打开会话侧栏底部的主机信息，进入连接管理，选择 **扫码**、**配对链接** 或 **输入地址**；同一局域网也可从 **附近设备** 发现主机。
3. 使用链接时，在电脑复制 `dshpocket://pair?h=<host:port>&c=<6位码>`，手机选择 **配对链接 → 粘贴链接 → 下一步**，核对自动填入的地址和配对码后点击 **开始配对**。也可扫码或手动输入地址与配对码。名称可留空，默认使用主机地址。
4. 连接成功后，选择工作区或会话即可使用。模型提供商和凭据由 Host 管理；手机侧的模型设置也是对该 Host 的配置。

配对码有效期为 10 分钟，最多尝试 3 次，成功后立即失效。旧的 iroh 形式（`n=` / `nodeId#code`）会被拒绝，需从桌面界面重新配对。

本机 owner CLI（在 `plugin/` 目录执行，默认 `ws://127.0.0.1:7690` 与 `$DSH_HOME/pocket/token`）：

```bash
node bin/pocket.js pair
node bin/pocket.js devices
node bin/pocket.js revoke <device-id>
```

不要把 owner token 交给手机。已配对设备能够控制当前 Host 的会话、终端和配置。手机 token 由 Android Keystore 的 AES-256-GCM 密钥加密存储；Host 在 `$DSH_HOME/pocket/devices.json`（权限 `0600`）中保存设备 token 的 SHA-256，并可在「手机连接」中撤销设备。

## 从源码构建

### Android 应用

Gradle 工程根目录为 `app/`。需要 JDK 17 与 Android SDK（`compileSdk` / `targetSdk` 为 35，`minSdk` 为 26）。使用该目录中的 Gradle Wrapper，并通过 Android Studio 或 `ANDROID_HOME` 配置 SDK。

```bash
cd app
./gradlew :core:protocol:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

产物（路径相对于仓库根目录）：

- `app/app/build/outputs/apk/debug/app-debug.apk`
- `app/app/build/outputs/apk/release/app-release.apk`

已连接 Android 设备或启动模拟器时：

```bash
cd app
./gradlew :app:connectedDebugAndroidTest
```

仅当 `DSH_ANDROID_KEYSTORE_FILE`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 均为非空时，`assembleRelease` 使用指定 keystore，否则回退到 debug 签名。备用的 `DSH_ANDROID_*` 密码与 alias 环境变量见 [app/README.md](app/README.md)。

模块职责：

| 模块 | 职责 |
| --- | --- |
| `core/protocol` | 纯 Kotlin/JVM：WebSocket 客户端、协议与数据模型、会话折叠、重连策略 |
| `app` | Compose 页面、连接管理、审批通知前台服务、Keystore token 存储 |

### Host 插件

```bash
cd plugin
npm install
npm run build:client
npm run lint
npm test
```

`npm test` 的 `pretest` 会重建客户端。真实 Host 集成测试需要 PATH 中的 dsh 与 pnpm：

```bash
npm run test:integration
```

针对临时 `dsh web` 的 Android 协议联调（需要 JDK 17）：

```bash
node test/integration/kotlin-live.mjs
```

可选 sidecar 需要 Rust 工具链：

```bash
npm run build:iroh
```

## CI

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) 在 `main` 的 push 与 pull request 上运行。

| 任务 | 内容 |
| --- | --- |
| `host-binaries` | 构建 Linux x64 与 macOS arm64 的 `pocket-host` |
| `plugin` | `npm run lint` 与 `npm test`，并将 Linux sidecar 放到 PATH |
| `integration` | 针对 `@deepseek-ai/dsh@0.2.1-alpha.2` 的真实 Host 测试 |
| `android` | `kotlin-live` 协议测试、单元测试、debug 与 R8 release APK；拒绝 `libpocket_iroh.so`；release 体积上限 40 MiB |
| `emulator` | API 34 仪器测试，以及 release APK 的安装／启动检查 |

推送到 `main` 时会强制更新 `dsh-android-latest` 预发布（APK 以及上述两个 `pocket-host` 二进制）。PR 运行相同检查，但不发布该 tag。

推送 `v*` tag（例如 `v0.1.0`）会跑同一套任务；通过后创建非 prerelease 的 GitHub Release，标题为 `dsh-android vX.Y.Z`，并上传 `dsh-android-vX.Y.Z.apk`（R8 release）以及可选的 `pocket-host` 二进制。tag 去掉 `v` 后必须与 `app/app/build.gradle.kts` 中的 `appVersionName` 一致。请先把版本改动合入 `main`，再推 version tag。

发布签名需在仓库 Actions Secrets 配齐 `ANDROID_KEYSTORE_B64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。任务写入临时 keystore，结束后删除。不要将 keystore 提交到仓库。

## 目录结构

```text
.
├── app/                      Android Gradle 工程
│   ├── app/                  Compose 应用（applicationId 为 dev.dsh.pocket）
│   └── core/protocol/        纯 Kotlin/JVM 协议客户端
├── plugin/                   Host 桥接（dsh-plugin-pocket）
│   ├── src/                  服务端、操作分发、配对、管理 UI
│   ├── bin/                  本机 owner CLI（dsh-pocket）
│   ├── iroh/                 可选的 pocket-host / pocket-iroh crate
│   └── test/                 单元测试与 Host 集成测试
├── contract/                 共享的配对 URI 用例
├── docs/                     设计说明、对齐清单、第三方声明、截图
└── .github/workflows/ci.yml
```

相关文档：

| 路径 | 内容 |
| --- | --- |
| [app/README.md](app/README.md) | Android 使用说明、构建与测试入口 |
| [plugin/README.md](plugin/README.md) | 桥接安装、协议契约、插件开发 |
| [docs/feature-parity.md](docs/feature-parity.md) | 功能对齐清单与原生能力边界 |
| [docs/desktop-parity.md](docs/desktop-parity.md) | 桌面交互与移动端实现对照 |
| [docs/trajectory-parity.md](docs/trajectory-parity.md) | 会话轨迹的数据与展示约定 |
| [docs/design.md](docs/design.md)、[docs/api-notes.md](docs/api-notes.md) | 早期架构与上游 API 笔记；当前行为以代码及上述说明为准 |

## 已知限制

- 不支持在手机本地运行 dsh。电脑休眠、Host 退出或网络不可达时无法继续远程操作。
- 终端恢复依赖 Host 仍保留对应 Shell，不是进程快照。自动化投递成功也不等于模型执行完成。
- 原生模型页不展示第三方插件自定义卡片（例如 Qoder）。插件自定义页面从插件详情进入，使用 60 秒单次票据兑换 15 分钟 HttpOnly 网页会话；不提供 Electron／桌面原生 API、设备文件选择和下载。
- 上游仍为 alpha。升级时应同时关注 App、桥接和 dsh 版本。

## 致谢

- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)：Host 能力、交互与视觉参考；部分文案、图标和动画沿用上游资产。
- [dsh-mobile](https://github.com/saya-ch/dsh-mobile)：移动导航与远程连接等实现参考。
- [AndroidSVG](https://github.com/BigBadaboom/androidsvg)：原生渲染插件 SVG 图标。

相关许可证与资产归属见 [第三方声明](docs/THIRD_PARTY_NOTICES.md)。
