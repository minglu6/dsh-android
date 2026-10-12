# Android App

Gradle 工程根目录就是 `app/`。

| 模块 | 内容 |
|---|---|
| `core/protocol` | 纯 Kotlin/JVM：`PocketClient`（OkHttp WebSocket，协议 v1）、`TranscriptReducer`（把 follow 帧折叠成消息、思考、工具卡片）、模型等数据结构 |
| `app` | Compose 界面、连接管理、前台服务（审批通知）、Keystore 加密 token |

界面按 [docs/mockups](../docs/mockups/) 实现，配色和圆角沿用 dsh 网页端。

## 构建

真实模型端到端（仅本地，需要 `DEEPSEEK_API_KEY`，不进 CI）：`cd plugin && E2E_FRAMES=/tmp/frames.json node test/integration/real-model.mjs`，再用 `E2E_FRAMES=/tmp/frames.json ./gradlew :core:protocol:test` 让会话折叠逻辑回放真实帧。


```bash
cd app
./gradlew :core:protocol:test :app:testDebugUnitTest :app:assembleDebug
# 对真实 dsh + 插件跑协议测试（需要 dsh、pnpm）：
cd ../plugin && node test/integration/kotlin-live.mjs
```

## 使用

1. 电脑端：`dsh web` 装好插件后，在 `plugin/` 下运行 `node bin/pocket.js pair` 显示 6 位配对码（10 分钟有效）。
2. 手机端：连接 →「输入配对码」手动填写 `IP:端口` 与 6 位码；或点「扫码配对」用应用内相机扫描桌面端二维码（`dshpocket://pair?h=…&c=…`），解析后自动填入配对表单。
3. token 用 Android Keystore 的 AES-GCM 密钥加密保存。

## 功能对齐

见 [docs/feature-parity.md](../docs/feature-parity.md)。仅隐藏需「本地应用/编辑器/内嵌浏览器」的入口（浏览器面板、插件管理、自动化、打开配置文件、设置里的模型凭据）；主机 PTY 终端经插件接通，可在手机上远程使用。轨迹、指令、导出、工作区文件、后台任务、通用设置等已接通。智能体团队列出 `parentSessionId` 子会话。
