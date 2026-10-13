# Android App

Gradle 工程根目录就是 `app/`。

| 模块 | 内容 |
|---|---|
| `core/protocol` | 纯 Kotlin/JVM：`PocketClient`（WebSocket）、`Transport`（Tailscale / LAN / 本机 路由标签）、`TranscriptReducer`、模型等数据结构 |
| `app` | Compose 界面、连接管理（含本机 Termux sidecar）、前台服务（审批通知）、Keystore 加密 token |

界面按 [docs/mockups](../docs/mockups/) 实现，配色和圆角沿用 dsh 网页端。

## 构建

真实模型端到端（仅本地，需要 `DEEPSEEK_API_KEY`，不进 CI）：`cd plugin && E2E_FRAMES=/tmp/frames.json node test/integration/real-model.mjs`，再用 `E2E_FRAMES=/tmp/frames.json ./gradlew :core:protocol:test` 让会话折叠逻辑回放真实帧。


```bash
cd app
./gradlew :core:protocol:test :app:testDebugUnitTest :app:assembleDebug
# 对真实 dsh + 插件跑协议测试（需要 dsh、pnpm）：
cd ../plugin && node test/integration/kotlin-live.mjs
```

APK **不再**包含 `libpocket_iroh.so`；连接只走 host:port WebSocket。

## 使用

1. 电脑端：`dsh web` 装好插件后，设置 →「手机连接」打开配对（QR + `dshpocket://pair?h=host:port&c=`）。
2. 手机端：连接页对等切换「局域网 / Tailscale / 本机」；主动作「扫码 / 输入地址 / 连接附近设备」。
3. 局域网卡始终可见附近 mDNS 列表；本机走 Termux 里 `127.0.0.1:port` 的 pocket（配对串或 token）。见 [docs/termux-sidecar.md](../docs/termux-sidecar.md)。
4. 列表小标签按地址显示 `Tailscale` / `LAN` / `本机`。旧 iroh 配置需重新配对。
5. token 用 Android Keystore 的 AES-GCM 密钥加密保存。APK 不内嵌 Node/dsh，不执行 su。

## 功能对齐

见 [docs/feature-parity.md](../docs/feature-parity.md)。仅隐藏需「本地应用/编辑器/内嵌浏览器」的入口（浏览器面板、插件管理、自动化、打开配置文件、设置里的模型凭据）；主机 PTY 终端经插件接通，可在手机上远程使用。轨迹、指令、导出、工作区文件、后台任务、通用设置等已接通。智能体团队列出 `parentSessionId` 子会话。
