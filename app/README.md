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

## 暂未实现（界面已放置，置灰）

轨迹页、右侧面板（工作区文件/终端/浏览器/后台任务）、插件、自动化任务、+ 菜单里的文件/目标/计划/反馈/压缩/下载日志、更多菜单里的下载日志/反馈、设置里的模型/内置插件/配置文件，以及通用设置里除「权限」「繁忙时的发送行为」以外的项。智能体团队只列出子会话。
