# dsh-android

[DeepSeek Harness（dsh）](https://github.com/deepseek-ai/deepseek-harness) 的原生 Android 客户端，用 Kotlin 和 Jetpack Compose 开发。手机端界面按手机屏幕重新设计，视觉风格沿用 dsh 官方。连接方式支持 Tailscale、局域网、iroh P2P（打洞失败时自动走中继）和手机本地模式。

## 目录结构

| 目录 | 内容 |
|---|---|
| `plugin/` | `dsh-plugin-pocket`：装在电脑端 dsh 里的桥接插件，给手机提供带鉴权的 JSON/WebSocket 协议 |
| `app/` | Android App：`core/protocol`（纯 JVM 协议客户端与会话折叠）+ `app`（Compose 界面），见 [app/README.md](app/README.md) |
| `docs/` | 设计文档 [design.md](docs/design.md)、dsh 接口笔记 [api-notes.md](docs/api-notes.md)、[界面设计稿](docs/mockups/)（示意图） |

## 安装桥接插件（电脑端）

需要 dsh 0.2.1-alpha.2、Node ≥ 22.19；`dsh plugin` 依赖 PATH 上的 pnpm。

```bash
git clone https://github.com/minglu6/dsh-android && cd dsh-android/plugin && npm install
dsh plugin --profile web add "$PWD"
dsh web
# 日志会显示：[pocket] listening on ws://127.0.0.1:7690
# token 保存在 ~/.dsh/pocket/token
```

如果要通过 Tailscale 远程连接，把监听地址改成你的 tailnet IP。配置方法见 [plugin/README.md](plugin/README.md)。

`dsh web`（以及桌面 App）在 **设置 →「手机连接」** 可管理配对码 / QR / 设备。账号菜单（设置与意见反馈之间）在 dsh 0.2.1-alpha.2 **没有**官方可插 list slot，详见 [plugin/README.md](plugin/README.md)「管理 UI」。

## 进度

- [x] M0：插件骨架。支持会话列表、新建、重命名，发消息（排队或引导），取消，订阅流，翻历史，模型列表和切换，心跳，token 鉴权
- [x] M1：审批转发（手机和网页端谁先答算谁的）、设备配对（6 位码、按设备发 token、设备列表和撤销）、断点续订
- [x] M1.5：dsh 内管理 UI（设置「手机连接」分区；状态 / 配对码+QR / 设备重命名与撤销）
- [x] M2：Android App（WebSocket 连接）。最新调试包：[dsh-android-debug.apk](https://github.com/minglu6/dsh-android/releases/download/dsh-android-latest/dsh-android-debug.apk)
- [x] M3：iroh P2P 和中继（WS 优先，失败后走 iroh；打洞失败自动 relay）。配对 URI 可带 `n=<nodeId>`，与旧版兼容
- [ ] M4：手机本地模式
