# dsh-plugin-pocket

dsh-android 的桌面端桥接插件。它运行在 dsh 进程里，直接调用 dsh 的会话服务，再通过 WebSocket 给手机提供一套简单的 JSON 协议。完整协议见 [../docs/design.md](../docs/design.md) 第 4 节。

## 安装

```bash
dsh plugin --profile web add dsh-plugin-pocket          # 发布到 npm 之后
dsh plugin --profile web add /path/to/dsh-android/plugin # 从源码安装
dsh web
```

启动后日志里会出现 `[pocket] listening on ws://127.0.0.1:7690`。第一次运行时会生成 token，保存在 `$DSH_HOME/pocket/token`，文件权限 0600。

## 配置（`$DSH_HOME/profiles/web/cordis.patch.yml`）

```yaml
- id: dsh-pocket
  config:
    host: 100.106.128.87   # 监听地址，填 Tailscale IP 即可远程访问；默认 127.0.0.1
    port: 7690
    # token: 固定 token；不填则使用自动生成的那个
```

## 协议速览（v1，M0）

- 收发的都是 WebSocket 文本帧，内容为 JSON。
- 请求格式：`{"id":1,"op":"…","args":{…}}`。成功返回 `{"id":1,"ok":true,"result":…}`，失败返回 `{"id":1,"ok":false,"error":{"code","message"}}`。
- 连上后第一帧必须是 `hello {proto:1, token}`。其他帧或 token 错误都会以 4401 关闭连接；10 秒内没发 hello 也会被断开。
- 支持的操作：

| 操作 | 说明 |
|---|---|
| `sessions.list` | 会话列表 |
| `sessions.create` | 新建会话（可传 `cwd`） |
| `sessions.rename` | 重命名会话 |
| `prompt` | 发送消息，`mode` 为 `queue` 或 `steer`；`text` 和 `images` 至少给一个 |
| `queue.update` | 调整排队中的消息 |
| `cancel` | 取消当前回复 |
| `history.page` | 翻页拉取历史 |
| `models.catalog` | 模型列表 |
| `models.select` | 切换模型 |
| `follow` | 订阅会话，返回 `subId` |
| `unfollow` | 取消订阅 |

- 服务端推送：

| 推送 | 说明 |
|---|---|
| `{"ev":"follow",subId,frame}` | 原样转发 dsh 的 SessionFollowFrame |
| `follow.end` / `follow.error` | 订阅结束 / 出错 |
| `{"ev":"session",kind,…}` | 会话增删和状态变化 |
| `{"ev":"ping"}` | 每 20 秒一次；60 秒收不到客户端任何消息就断开 |

## 开发

```bash
npm install && npm run lint && npm test
npm run test:integration   # 需要 PATH 上有 dsh 0.2.1-alpha.2（Node ≥22.19）
```
