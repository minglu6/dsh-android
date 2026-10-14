# dsh-android

**像使用手机应用一样，操作电脑上的 DSH。**

[DeepSeek Harness（dsh）](https://github.com/deepseek-ai/deepseek-harness) 的非官方原生 Android 客户端，使用 Kotlin 与 Jetpack Compose 开发。以**接近 100% 复刻桌面版的功能与交互体验**为目标，把电脑上的 DSH 带到手机，而不只是提供一个精简聊天窗口。

模型调用、工具执行、文件和任务仍由电脑上的 dsh Host 承担；手机不是独立的本地 Agent，也不是把完整桌面网页套进 WebView。本项目不由 DeepSeek 官方开发或维护。

[下载 APK](https://github.com/minglu6/dsh-android/releases/tag/dsh-android-latest) · [快速开始](#快速开始) · [Android 使用与开发](app/README.md) · [Host 插件说明](plugin/README.md)

## 项目特色

- **桌面体验高度复刻**：围绕桌面版的功能入口、界面风格和操作逻辑持续对齐，覆盖会话、模型、审批、工作区、终端、插件与自动化等主要场景。
- **用手机的方式操作电脑**：针对触屏、软键盘和窄屏布局做原生适配，使用抽屉、侧面板和触控操作，不用在手机上拖动桌面画面或点击缩小的网页按钮。
- **同一台电脑，同一套 DSH**：直接操作电脑 Host 的会话、文件、工具与配置，任务仍在电脑执行；离开电脑后，也能在手机上继续查看和处理。

## 主要功能

| 场景 | 能力 |
|---|---|
| 会话与协作 | 流式聊天、思考与工具过程、历史记录、排队／插话发送、审批与提问、模型和推理等级选择 |
| 工作区 | 会话分组、注册与切换主机工作区、目录浏览与搜索、文件预览 |
| 主机工具 | PTY 终端、后台任务、会话轨迹与数据诊断；另提供手机端浏览器面板 |
| 配置管理 | Host 模型提供商、Agent 预设、共享通用设置、插件管理与内置插件目录 |
| 自动化 | 任务搜索、规则编辑、投递历史和删除；新建入口进入会话 |
| 连接 | 扫码／配对链接、局域网 mDNS 发现、多主机管理、连接诊断、断线续订 |

“接近 100%”是功能与体验的对齐目标，不是已量化的覆盖率。可用功能取决于 Host 版本与启用的插件；第三方页面和平台能力的具体差异见[已知限制](#已知限制)。

## 界面演示

截图由当前 Compose 界面在 Robolectric 中渲染，使用演示数据，不包含私人会话或真实凭据。点击图片可查看大图。

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

## 下载与运行前提

- **手机：** Android 8.0（API 26）或更新版本。
- **电脑：** 已安装 dsh，且保持 Host 运行。当前 CI 集成测试固定使用 `@deepseek-ai/dsh@0.2.1-alpha.2`；插件声明的 session-controller peer 范围为 `>=0.2.1-alpha.2 <0.2.2-0`，不等于所有版本均已验证。
- **Node.js：** `^22.19.0 || >=24.0.0`，即 22.x 的 22.19.0 及以上，或 24.0.0 及以上；不包含 23.x。`dsh plugin` 需要 PATH 中有 pnpm，CI 使用 `pnpm@10`。
- **网络：** 手机与电脑在可互访的局域网，或同一可互访的 Tailscale 网络。默认桥接端口为 TCP `7690`，防火墙需允许访问；Wi-Fi 客户端隔离会阻断连接。

从 [滚动预发布页面](https://github.com/minglu6/dsh-android/releases/tag/dsh-android-latest) 下载安装：

- [dsh-android.apk](https://github.com/minglu6/dsh-android/releases/download/dsh-android-latest/dsh-android.apk)：release 构建，开启 R8 与资源压缩。
- [dsh-android-debug.apk](https://github.com/minglu6/dsh-android/releases/download/dsh-android-latest/dsh-android-debug.apk)：debug 构建，供调试使用。

按 Android 提示允许所用浏览器或文件管理器安装 APK。**release 构建不代表一定使用发布密钥签名**：CI 配齐签名 Secrets 时使用指定密钥，否则回退到 debug keystore。发布说明中的 `Signing=secrets` 或 `Signing=debug-fallback` 标明构建方式；签名不同的 APK 无法直接覆盖安装，应先保留需要的数据再处理安装冲突。

## 快速开始

### 1. 在电脑安装桥接插件

以下从源码安装，示例使用 `web` profile。如果尚未安装 dsh 与 pnpm，可按当前 CI 的版本安装：

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

插件安装到指定 profile；若使用桌面 App，需在其 `desktop` profile 安装，而不是只装到 `web`。管理入口位于 **设置 → 手机连接**，不在账号菜单里。更多配置见 [插件说明](plugin/README.md)。

### 2. 配对手机

1. 电脑打开 **设置 → 手机连接**，选择局域网或 Tailscale，并打开配对窗口。
2. 手机打开会话侧栏底部的主机信息，进入连接管理，选择 **扫码** 或 **输入地址**；同一局域网也可从 **附近设备** 发现主机。
3. 扫描二维码或输入配对链接 `dshpocket://pair?h=<host:port>&c=<6位码>`。名称可留空，默认使用主机地址。
4. 连接成功后，选择工作区／会话即可使用。模型提供商和凭据由 Host 管理；手机侧的模型设置也是对该 Host 的配置。

配对码有效期为 10 分钟，最多尝试 3 次，成功后立即失效。更新 APK 后若新入口仍不可用，应同步更新插件并重启相应 Host；安装 APK 不会替换电脑上正在运行的桥接。

## 网络与安全边界

- 默认监听 `0.0.0.0:7690`，优先公布局域网 IPv4；也可选 Tailscale 或配置特定监听地址。请用防火墙限制访问范围，**不要将该端口直接暴露到公网**。
- 局域网使用 `ws://`／HTTP，桥接本身不提供 TLS；配对鉴权不等于传输加密。跨网络连接应使用 Tailscale 或已有可信的加密链路。
- 配对设备能够控制当前 Host 的会话、终端和配置，应按主机管理凭据保护。手机 token 由 Android Keystore 密钥加密存储；Host 保存设备 token 的 SHA-256，并可在「手机连接」撤销设备。
- 可选的插件自定义 Web 页面使用短期票据与网页会话，不向 WebView 暴露长期手机 token 或 Host 启动令牌；这不是对任意第三方插件的安全担保。仅安装和访问可信插件。
- 诊断报告包含网络地址、实例 ID 等信息，分享前请自行检查敏感内容。

## 已知限制

- 不支持手机本地运行 dsh；电脑休眠、Host 退出或网络不可达时无法继续远程操作。
- 配对地址发现使用 IPv4，不支持 IPv6-only 配对。Android 手机链路仅使用 host:port WebSocket，不包含 iroh JNI；仓库保留的 iroh sidecar 只用于可选桌面实验。
- 模型设置是原生页面，不展示 Qoder 等插件自定义卡片；第三方页面通过插件详情的独立入口打开，Electron／桌面原生 API、设备文件选择和下载等能力不受支持。
- 终端恢复依赖 Host 仍保留对应 Shell，不是进程快照。自动化投递成功也不等于模型执行完成。
- 上游仍为 alpha，能力与内部 API 可能变化。升级时应同时关注 App、桥接和 dsh 版本；详细边界见 [Android 说明](app/README.md) 与 [功能对齐清单](docs/feature-parity.md)。

## 开发与文档导航

| 路径 | 内容 |
|---|---|
| [app/](app/README.md) | Android 工程、使用说明、构建与测试入口；`core/protocol` 为纯 Kotlin/JVM 协议模块，`app` 为 Compose 应用 |
| [plugin/](plugin/README.md) | Host 桥接、安装配置、配对与协议契约、插件开发 |
| [docs/feature-parity.md](docs/feature-parity.md) | 功能对齐清单与原生能力边界 |
| [docs/desktop-parity.md](docs/desktop-parity.md) | 桌面交互与移动端实现对照 |
| [docs/trajectory-parity.md](docs/trajectory-parity.md) | 会话轨迹的数据与展示约定 |
| [docs/design.md](docs/design.md)、[docs/api-notes.md](docs/api-notes.md) | 初始架构、协议设计及上游 API 研究；含历史规划，当前行为以代码和本项目说明为准 |
| [.github/workflows/ci.yml](.github/workflows/ci.yml) | 插件检查、真实 Host 集成、Android 构建、模拟器测试及滚动发布配置 |

CI 在 `main` push 时更新 `dsh-android-latest` 滚动预发布；PR 也运行检查，但不发布该 tag。构建签名需在仓库 Actions Secrets 配齐 `ANDROID_KEYSTORE_B64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；keystore 不应提交到仓库，CI 使用后会删除临时文件。Android 本地构建与签名环境变量见 [app/README.md](app/README.md)。

## 致谢与第三方归属

- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)：Host 能力、交互与视觉参考；部分文案、图标和动画沿用上游资产。
- [dsh-mobile](https://github.com/saya-ch/dsh-mobile)：移动导航与远程连接等实现参考；改编部分的文件头保留归属说明。
- [AndroidSVG](https://github.com/BigBadaboom/androidsvg)：原生渲染插件 SVG 图标。

相关许可证与资产归属见 [第三方声明](docs/THIRD_PARTY_NOTICES.md)。
