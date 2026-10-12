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

## 配对（M1）

首次启动且还没有任何已配对设备时，会自动打开一个配对窗口，日志里打印 6 位配对码。配对码 10 分钟内有效，最多试 3 次，用一次就作废。之后要再开窗口，有三种方式：
- CLI：`node bin/pocket.js pair`，用本机的 owner token 连上插件后打开窗口；`node bin/pocket.js devices` 查看设备，`node bin/pocket.js revoke <id>` 撤销设备
- dsh 网页端口上的路由（只接受本机请求，拒绝跨站 Origin）：`curl -X POST http://127.0.0.1:<dsh端口>/pocket/pair`，`GET`/`POST /pocket/devices`
- 在 patch 配置里写 `pair: true`

手机发送 `{"op":"pair","args":{"proto":1,"code":"123456","name":"Pixel"}}` 完成配对，会拿到 `{deviceId, token}`，之后用 `hello` 带这个 token 登录。服务端只保存 token 的 sha256，写在 `$DSH_HOME/pocket/devices.json`，权限 0600。owner token（文件 `pocket/token`）只给本机 CLI 用，只有它能打开配对窗口。

## 审批（M1）

dsh 发起 `approval/request` 时，如果有已登录的设备在线，插件会推送 `{"ev":"approval",approvalId,sessionId,toolName,callId?,reason?}`。设备用 `approval.answer {approvalId, outcome:"allowed-once"|"rejected"}` 答复。手机和网页端谁先答就算谁的：
- 手机先答：网页端那张审批卡会收到 Gateway 发来的 cancel 并自动消失，这一点已在真实 dsh 上验证。
- 网页端先答：手机收到 `approval.closed`，`by` 字段为 `chain`。

工具那边取消时，结果为 `cancelled`。新连上的设备会补发还没处理的审批，也可以用 `approval.pending` 主动查询。

## 断点续订

`follow {sessionId, afterSeq}` 返回的第一帧是 `{"type":"resume", afterSeq, cursor, records:[seq>afterSeq…], projections…}`。如果快照窗口没覆盖到 afterSeq，插件会用 `page` 往前补齐，最多补 10 页；补不齐就退回发完整的 `snapshot`。

## 协议速览（v1）

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
