# Termux sidecar（本机 on-device 路径）

原则：**Android APK 只做控制器**。不内嵌 Node / dsh runtime，不在 APK 内执行 `su` / `tsu`。本机智能体进程跑在 [Termux](https://github.com/termux/termux-app) 里；App 通过已有 pocket 协议连 `ws://127.0.0.1:<port>`。

> APK 内嵌本地 runtime（原 design.md M4）**延期**。当前 on-device 路径就是 Termux sidecar。

远程路径不变：电脑上的 dsh + `dsh-plugin-pocket` 仍可走 **Tailscale** 或 **局域网 host:port**。

## 架构

```
┌─────────────────────────┐     loopback WS      ┌──────────────────────────┐
│  dsh-android APK        │◄─── 127.0.0.1:port ──│  Termux                  │
│  (Compose 控制器)        │   pocket JSON 协议   │  node + dsh + pocket 插件 │
│  不跑 su / 不嵌 Node     │                     │  可选：用户自行 tsu/su    │
└─────────────────────────┘                     └──────────────────────────┘
```

配对与鉴权和桌面相同：

1. `dshpocket://pair?h=127.0.0.1:7690&c=<6位码>`（深链或粘贴）
2. 或粘贴 `$DSH_HOME/pocket/token`（owner token → `hello`）

## 安装 Termux（社区渠道，本仓库不内置）

不要从 Google Play 安装过期的 Termux。请用：

- GitHub Releases：https://github.com/termux/termux-app/releases
- F-Droid：https://f-droid.org/packages/com.termux/

## 在 Termux 里跑 dsh + pocket

```bash
pkg update
pkg install -y nodejs-lts git

# 安装 dsh（见 deepseek-harness 文档），再装本仓库 plugin/
dsh plugin --profile web add /path/to/dsh-android/plugin
```

在 `~/.dsh/profiles/web/cordis.patch.yml` 的 `dsh-pocket` 配置里绑定回环：

```yaml
- id: dsh-pocket
  config:
    host: 127.0.0.1
    port: 7690
```

```bash
dsh web
# 设置 →「手机连接」出示 QR / dshpocket://
# 或：cat ~/.dsh/pocket/token
```

## App 侧：「本机 Termux」

连接页顶部入口 **本机 Termux**：

- 默认 `127.0.0.1` + 端口（默认 `7690`）
- 粘贴 `dshpocket://…` 或 owner/device token
- 可选勾选 **经 su/tsu 提权**：仅文案提醒；App **从不**调用 `su`/`tsu`
- 若回环端口无响应：主题化指导页（Tokyo Night + Meslo）展示可复制的安装/启动命令

## 「经 su/tsu 提权」含义

部分本机工具需要 root。请在 **Termux 内**自行提权启动 dsh，例如：

```bash
tsu -c 'dsh web'
# 或 su -c 'dsh web'
```

App 只连 loopback；不探测 root、不拉起 shell、不包装 su。

## 与 LAN / Tailscale 的关系

| 路径 | 谁跑 dsh | 地址 |
|---|---|---|
| 本机 Termux | 手机 Termux | `127.0.0.1:port`（路由标签「本机」） |
| Tailscale | 电脑 | `100.x` / `*.ts.net` |
| 局域网 | 电脑 | 局域网 IPv4 + mDNS `_dshpocket._tcp` |

三者共用同一套 pocket 协议与设备 token；可同时保存多个连接配置。
