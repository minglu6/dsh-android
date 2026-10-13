# Android App

Gradle 工程根目录就是 `app/`。

| 模块 | 内容 |
|---|---|
| `core/protocol` | 纯 Kotlin/JVM：`PocketClient`（WebSocket）、`Transport`（Tailscale / LAN 路由标签）、`TranscriptReducer`、模型等数据结构 |
| `app` | Compose 界面、连接管理、前台服务（审批通知）、Keystore 加密 token |

界面按 [docs/mockups](../docs/mockups/) 实现，配色和圆角沿用 dsh 网页端。

## 构建

真实模型端到端（仅本地，需要 `DEEPSEEK_API_KEY`，不进 CI）：`cd plugin && E2E_FRAMES=/tmp/frames.json node test/integration/real-model.mjs`，再用 `E2E_FRAMES=/tmp/frames.json ./gradlew :core:protocol:test` 让会话折叠逻辑回放真实帧。


```bash
cd app
./gradlew :core:protocol:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
# 对真实 dsh + 插件跑协议测试（需要 dsh、pnpm）：
cd ../plugin && node test/integration/kotlin-live.mjs
```

`assembleRelease` 开启 R8 minify + resource shrinking。未设置签名环境变量时使用 debug keystore；生产签名见仓库根 README「正式签名（CI）」——密钥来自 GitHub Secrets，切勿提交 keystore。

APK **不再**包含 `libpocket_iroh.so`；连接只走 host:port WebSocket。hello/pair 会交换 `appVersion` / `version` / `minimumAppVersion`，插件过旧时 App 显示主题化提示条。

## 使用

1. 电脑端：`dsh web` 装好插件后，设置 →「手机连接」打开配对（QR + `dshpocket://pair?h=host:port&c=`）。
2. 手机端：连接 →「局域网 / Tailscale」切换；「扫码」或「输入地址」。
3. 局域网 tab 显示附近 mDNS 设备；列表小标签按地址显示 `Tailscale` / `LAN`。旧 iroh 配置需重新配对。
4. token 用 Android Keystore 的 AES-GCM 密钥加密保存。

## 功能对齐

见 [docs/feature-parity.md](../docs/feature-parity.md)。仅隐藏需「本地应用/编辑器/内嵌浏览器」的入口（浏览器面板、插件管理、自动化、打开配置文件、设置里的模型凭据）；主机 PTY 终端经插件接通，可在手机上远程使用。轨迹、指令、导出、工作区文件、后台任务、通用设置等已接通。智能体团队列出 `parentSessionId` 子会话。
