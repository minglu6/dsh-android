# dsh-android

[DeepSeek Harness（dsh）](https://github.com/deepseek-ai/deepseek-harness) 的原生 Android 客户端，用 Kotlin 和 Jetpack Compose 开发。手机端界面按手机屏幕重新设计，视觉风格沿用 dsh 官方。连接方式支持 Tailscale、局域网、iroh P2P（打洞失败时自动走中继）和手机本地模式。

## 目录结构

| 目录 | 内容 |
|---|---|
| `plugin/` | `dsh-plugin-pocket`：装在电脑端 dsh 里的桥接插件，给手机提供带鉴权的 JSON/WebSocket 协议 |
| `app/` | Android App（M2 开始） |
| `docs/` | 设计文档 [design.md](docs/design.md)、dsh 接口笔记 [api-notes.md](docs/api-notes.md)、[界面设计稿](docs/mockups/)（示意图） |

## 安装桥接插件（电脑端）

需要 dsh 0.2.1-alpha.2，Node ≥ 22.19。

```bash
git clone https://github.com/minglu6/dsh-android && cd dsh-android/plugin && npm install
dsh plugin --profile web add "$PWD"
dsh web
# 日志会显示：[pocket] listening on ws://127.0.0.1:7690
# token 保存在 ~/.dsh/pocket/token
```

如果要通过 Tailscale 远程连接，把监听地址改成你的 tailnet IP。配置方法见 [plugin/README.md](plugin/README.md)。

## 进度

- [x] M0：插件骨架。支持会话列表、新建、重命名，发消息（排队或引导），取消，订阅流，翻历史，模型列表和切换，心跳，token 鉴权
- [ ] M1：审批转发、设备配对（6 位码和二维码）
- [ ] M2：Android App（WebSocket 连接）
- [ ] M3：iroh P2P 和中继
- [ ] M4：手机本地模式
