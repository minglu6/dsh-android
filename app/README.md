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
2. 手机端：点击会话侧栏底部「设置」右侧的主机信息打开连接管理，再「扫码」或「输入地址」。
3. 「附近设备」自动发现局域网 mDNS 主机；已配对设备显示地址、状态和 `Tailscale` / `LAN` 路由。旧 iroh 配置需重新配对。
4. token 用 Android Keystore 的 AES-GCM 密钥加密保存。

## 功能对齐

见 [docs/feature-parity.md](../docs/feature-parity.md)。界面与操作以官方桌面端为准：插件支持分组管理、安装检查/取消/卸载及配置；自动化任务支持搜索、规则编辑、投递记录和删除，新建跳转会话。仅隐藏真正依赖桌面本地 UI 的功能（内嵌浏览器、本机编辑器/目录选择器）；第三方插件自定义 React 界面仍需 Web，公开的 Host 配置可在手机编辑。主机 PTY 终端、模型凭证、轨迹、指令、导出、工作区文件、后台任务、通用设置和 `parentSessionId` 子会话均已有原生入口。

输入栏的权限切换按钮仅显示当前模式图标，保留无障碍标签，把宽度留给完整模型名称。权限选项来自当前 Host 的动态目录；Host 启用自动审查插件后才显示「自动审查」，启用时必须确认风险，且不能保存为新会话默认权限。

输入栏下方默认显示生成速度、缓存命中率和上下文占用；数据来自真实 Host 投影，缺失时不伪造数值。「性能与用量 → 详细」可查看全会话耗时、Token 用量等明细。目录失效通知和统计的实时更新需要桌面端加载新版桥接，安装 APK 本身不会重载电脑上的插件。

右侧栏使用全高侧面板：手机铺满宽度，横屏/宽屏靠右显示，最大宽度 480dp。「开始」页沿用官方罗盘和入口卡片；文件、文档预览、终端共用返回/收起导航，终端随键盘调整可用高度。后台任务位于对话/轨迹标签旁，任务清单位于输入栏上方，不混入侧栏入口。退出终端只取消订阅，不结束主机 Shell。
