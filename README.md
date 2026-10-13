# dsh-android

[DeepSeek Harness（dsh）](https://github.com/deepseek-ai/deepseek-harness) 的原生 Android 客户端，用 Kotlin 和 Jetpack Compose 开发。手机端界面按手机屏幕重新设计，视觉风格沿用 dsh 官方。远程连接走 **Tailscale 或局域网 host:port** WebSocket（不再捆绑 iroh 原生库）。

## 目录结构

| 目录 | 内容 |
|---|---|
| `plugin/` | `dsh-plugin-pocket`：装在电脑端 dsh 里的桥接插件，给手机提供带鉴权的 JSON 协议（Tailscale / LAN） |
| `app/` | Android App：`core/protocol`（纯 JVM 协议客户端与会话折叠）+ `app`（Compose 界面），见 [app/README.md](app/README.md) |
| `docs/` | 设计文档 [design.md](docs/design.md)、dsh 接口笔记 [docs/api-notes.md](docs/api-notes.md)、[界面设计稿](docs/mockups/)（示意图） |

## 安装桥接插件（电脑端）

需要 dsh 0.2.1-alpha.2、Node ≥ 22.19；`dsh plugin` 依赖 PATH 上的 pnpm。

```bash
git clone https://github.com/minglu6/dsh-android && cd dsh-android/plugin && npm install && npm run build:client
dsh plugin --profile web add "$PWD"
dsh web
# 日志：[pocket] ws listening … (pairing lan: host:port)
# 设置 →「手机连接」出示 QR / 配对串（dshpocket://pair?h=host:port&c=）
```

未配置 `host` 时，插件默认绑定 `0.0.0.0`，优先使用局域网 IPv4（不需要 Tailscale）。同时检测到 Tailscale 时，可在「手机连接」中切换，二维码及复制链接跟随所选网络。配置具体 `host` 会限制为该监听接口。

`dsh web`（以及桌面 App）在 **设置 →「手机连接」** 管理配对码 / QR / 设备。配对格式见 [plugin/README.md](plugin/README.md)。

## 进度

- [x] M0：插件骨架。支持会话列表、新建、重命名，发消息（排队或引导），取消，订阅流，翻历史，模型列表和切换，心跳，token 鉴权
- [x] M1：审批转发、设备配对、断点续订
- [x] M1.5：dsh 内管理 UI（设置「手机连接」分区）
- [x] M2：Android App。最新包：[dsh-android.apk](https://github.com/minglu6/dsh-android/releases/download/dsh-android-latest/dsh-android.apk)（R8 正式签名） / [dsh-android-debug.apk](https://github.com/minglu6/dsh-android/releases/download/dsh-android-latest/dsh-android-debug.apk)
- [x] M3：远程连接 — Tailscale / 局域网 host:port（APK 不再含 iroh `.so`）
- [x] LAN mDNS 自动发现（`_dshpocket._tcp`）、设备状态探测、连接诊断
- [ ] M4：手机本地模式

## 正式签名（CI）

CI 在 `main` 上构建 **minify + resource shrinking** 的 release APK，并上传到滚动 tag `dsh-android-latest`。

在仓库 Settings → Secrets and variables → Actions 中配置（**不要把 keystore 提交进仓库**）：

| Secret | 含义 |
|---|---|
| `ANDROID_KEYSTORE_B64` | release `.jks` / `.keystore` 的 base64 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key 密码 |

未配置时 CI 回退到 debug 签名，流水线仍保持绿色；配置后产出生产签名包。CI 会在使用后删除临时 keystore 文件。

## 第三方致谢

局域网发现、设备状态轮询、连接诊断、正式签名与纯策略抽取部分逻辑改编自
[dsh-mobile](https://github.com/saya-ch/dsh-mobile)（Apache-2.0）；改编文件头部含归属说明。
