# Android App

使用 Kotlin / Jetpack Compose 实现的原生 Android 客户端：**高度复刻桌面版 DSH，让你像使用手机应用一样，操作电脑上的 DSH。** 功能入口与交互逻辑尽量保持桌面习惯，布局、触控与软键盘则针对手机适配；不是桌面画面的远程投屏，也不是精简的聊天替代品。

模型、工作区文件、终端进程与自动化任务在电脑 Host 上运行；手机负责展示与控制。项目定位、APK 下载及 Host 安装见 [根 README](../README.zh-CN.md)。

## 连接与日常使用

1. 电脑安装桥接并启动对应的 dsh profile，在 **设置 → 手机连接** 打开配对窗口。
2. 手机点击会话侧栏底部的主机信息，进入连接管理，选择 **扫码**、**输入地址** 或 **附近设备**。配对格式为 `dshpocket://pair?h=<host:port>&c=<6位码>`。
3. 名称留空时使用远程主机地址。已配对设备显示地址与 `LAN`／`Tailscale` 路由；主机状态点区分在线、连接中、离线和认证失效。
4. 选择工作区和会话，或在欢迎页发送新会话的首条消息。电脑需保持运行，手机不能脱离 Host 独立执行任务。

**设置 → 连接诊断** 可查看完整报告、复制全文或选择局部文字。报告包含地址与实例 ID，分享前请检查。手机 token 使用 Android Keystore 的 AES-GCM 密钥加密保存；不再使用的设备应在电脑「手机连接」中撤销。

连接只走 host:port WebSocket，APK 不包含 iroh JNI。局域网无需 Tailscale；跨网络应使用 Tailscale 或已有可信加密链路，桥接的 LAN HTTP／WebSocket 本身不提供 TLS。旧 iroh 连接配置需重新配对，IPv6-only 配对地址不受支持。

### 补充界面

以下截图由当前 Compose 界面在 Robolectric 中使用演示数据渲染。聊天、工作区、模型、终端、插件和自动化的演示见 [项目首页](../README.zh-CN.md#界面截图)。

<table>
  <tr>
    <td align="center"><a href="../docs/screenshots/showcase/queue.png"><img src="../docs/screenshots/showcase/queue.png" width="250" alt="待执行消息队列" /></a><br />待执行消息队列</td>
    <td align="center"><a href="../docs/screenshots/showcase/settings.png"><img src="../docs/screenshots/showcase/settings.png" width="250" alt="通用设置" /></a><br />通用设置</td>
    <td align="center"><a href="../docs/screenshots/showcase/connections.png"><img src="../docs/screenshots/showcase/connections.png" width="250" alt="主机连接管理" /></a><br />主机连接管理</td>
  </tr>
</table>

## 聊天与发送行为

### 首条消息

新会话点击发送后立即本地回显并显示「发送中」。创建、配置与订阅期间保留消息和输入草稿，不切换为空白页面；首条确认前禁止重复提交，确认后恢复正常发送。创建、配置或发送失败会保留原文与错误，可选择「重新编辑」。离开会话后，过期回执不会重新打开该会话或继续发送。

同一提交的本地回显、Host 消息及快照通过 `requestId` 关联，避免重复展示；新会话不等待无关的权限回读与反馈查询。

### 排队与插话

- 运行中连续发送默认使用 **排队发送**，发送偏好与桌面共享 `ui-conversation.busyEnter`；只有显式选择 **插话发送** 才引导当前轮次。
- 排队消息显示在输入栏上方：单条预览，多条默认折叠，可展开；仅未确认消息显示「发送中」。被 Host 正式消费后才进入聊天正文。
- 已确认消息可编辑、删除或插话；含附件／非文本内容不能文本编辑，插话仅在当前轮次运行时可用。操作结果以 Host 收件箱更新为准。
- 重复文本按不同 `requestId` 区分；发送失败清理对应回显，重连后从 Host 收件箱恢复队列。

### 过程、模型与审批

- 聊天按正文与输入边界分组过程；标准模式默认收起，详细模式运行时平铺。思考与工具执行期间保留「深度求索中」，计时使用 Host 轮次起点，缺少起点不编造秒数。
- 输入栏上方的模型浮层分为「模型」与「推理等级」，超过四个模型时提供搜索。等级名称与默认值来自 Host，切换模型不继承旧等级；失败保留原选择，桌面切换也会同步。
- 审批和提问接管输入区，失败保留答案，多题可返回修改。权限选项来自 Host 动态目录；自动审查仅在 Host 支持时出现，需确认风险，不能设为新会话默认权限。
- `/` 指令、`@` 引用、目标与任务清单来自当前 Host。用量与性能使用实际投影，缺失数据不伪造为数值；详细统计可从「性能与用量」查看。

## 工作区与主机工具

| 功能 | 使用方式与边界 |
|---|---|
| 工作区 | 支持注册、重命名、移除、组内新会话、目录树、搜索和排序；以 Host 实体区分，同名目录不合并。目录选择支持浏览、隐藏文件与新建目录；仅提供 `native` 选择器的 Host 保留完整路径输入。 |
| 文件预览 | 支持缩放图片、按需渲染完整 PDF、Markdown、CSV/TSV，以及禁止脚本／外网／本地资源访问的静态 HTML；Office 二进制文件不当作文本展示。 |
| 终端 | 连接主机 PTY，支持控制键、选区复制、粘贴与多终端切换。返回或收起只取消订阅，菜单「退出」经确认后才结束目标 Shell。 |
| 浏览器 | 使用手机 WebView，支持前进、后退、刷新和在系统浏览器打开；不是电脑浏览器的画面投屏。 |
| 轨迹与诊断 | 查看会话轨迹、原始记录、分组、筛选、分页及 JSON 详情；聊天选取可定位到对应诊断记录。 |
| 后台任务 | 仅在真实任务列表非空时显示入口；前台在线时每 3 秒刷新，失败明确显示状态不可用。可查看输出，终止需确认；停止查看不会终止任务。 |

工作区行不显示展开三角，点击整行即可展开／收起。空闲时显示开／合文件夹：当前工作区及工作区树的祖先为蓝色，其他工作区为灰色，同路径的其他工作区不连带高亮。欢迎页尚未进入会话时，与输入区共用工作区选择。

会话运行时在标题左侧显示桌面同款灰色转圈，等待审批／回答时改为黄色提示，结束后移除。工作区将组内及子工作区的活动状态（含置顶会话）显示在原文件夹位置：运行中以灰色转圈替换文件夹，待处理交互优先显示黄色提示，空闲后恢复原蓝色／灰色文件夹。展开、收起规则一致，行尾不额外显示状态图标。系统关闭动画时保留静态圆环。

终端会保存连接配置、所属会话及已知终端身份，重开面板／App 时可重新附加 Host 保留的 Shell。它不是进程快照：Host 重启或闲置回收后无法恢复已消失的进程，也不会把不存在的指定终端偷偷换成新 Shell。多行粘贴需确认，断线后禁止发送但保留输出供复制。远端平台由桥接返回，用于选择快捷键；旧桥接缺少该信息时显示通用 Ctrl 标记。

侧栏与右侧面板自动适配宽度，不提供手动「手机／桌面」布局选择。宽度达到 760dp 时可使用双栏侧栏，同时为主内容保留至少 480dp；窄屏使用抽屉。

## 设置、插件与自动化

### 与 Host 共享配置

语言、外观、字体与字号、聊天展示、性能与用量、繁忙发送行为、默认权限和日志上传等读写桌面同一配置。保存携带 Host revision 并回读确认；桌面变更通过 `settings.changed` 更新手机。离线、只读、冲突或覆盖不会伪装成成功，失败重试只重新读取，不自动重放写入。切换主机清除旧配置镜像与过期结果。

连接、换行等设备专属项留在本机；「工作步骤收起时机」仅在本次应用实例保持。字体在手机本地解析，不下载电脑端字体。Agent 预设来自 Host，支持查看模式帮助与只读 YAML；创建自定义预设进入会话草稿，不自动发送。

### 模型与插件

原生模型页提供提供商列表、API 密钥／地址／协议、模型目录与提供商增删。保存执行版本检查并回读，失败保留草稿；密钥不回显，删除先确认配置移除，再清理该页面管理的密钥。**该页不展示 Qoder 等插件自定义卡片，也不通过 WebView 代替默认模型配置。**

侧栏「插件」提供内置配置、实验性插件与已安装插件；设置中的「内置插件」是独立原生只读目录，包含全局组件与按 Agent 预设分组的会话插件。安装、启停、卸载及配置沿用 Host 结果，不把 RPC 受理当作操作已经生效。

第三方 React 页面可从插件详情的 **打开插件自定义界面** 主动进入：

- 通过已认证连接取得 60 秒单次票据，兑换 15 分钟 HttpOnly 网页会话，不把手机长期 token 或 Host 启动令牌交给 WebView。
- 退出、主机断连或撤销配对会关闭对应网页会话；过期后使用「重新加载」。
- 页面导航、请求与 WebSocket 限于当前 Host；禁止文件/content 访问、证书绕过、设备权限、下载、文件选择和外部窗口，不补齐 Electron／桌面原生 API。
- 与普通浏览器是不同入口和权限策略，但 Android WebView cookie 存储仍是应用级共享，不能把它理解为独立浏览器进程或完整安全沙箱。

### 自动化

支持任务搜索、常用 Cron 构建与高级原文编辑、投递历史和删除；新建入口进入会话。未修改的时间精度和时区别名保持不变。插件与自动化列表接收 Host 变更通知，并保留手动刷新。**投递回执不代表模型执行完成。**

## 版本与能力限制

App 在 `hello`／`pair` 交换版本信息，并根据 Host 公布的操作能力显示入口。新功能需要相应版本的桥接；仅安装新 APK 不会重载电脑插件。当前支持基线与网络前提见 [根 README](../README.zh-CN.md#下载与安装)。

详细对照见 [功能清单](../docs/feature-parity.md)、[桌面交互对照](../docs/desktop-parity.md) 和 [轨迹契约](../docs/trajectory-parity.md)。这些记录用于说明具体实现，不代表所有第三方插件都能在 Android 上使用。

## 开发

Gradle 工程根目录为 `app/`。需要 **JDK 17**、Android SDK（`compileSdk` / `targetSdk` 为 35，`minSdk` 为 26）；使用仓库中的 Gradle Wrapper，并通过 Android Studio 或 `ANDROID_HOME` 配置 SDK。

| 模块 | 职责 |
|---|---|
| `core/protocol` | 纯 Kotlin/JVM：WebSocket 客户端、协议与数据模型、会话折叠、重连策略 |
| `app` | Compose 页面、连接管理、审批通知前台服务、Keystore token 存储 |

### 构建与测试入口

以下命令从仓库根目录开始：

```bash
cd app
./gradlew :core:protocol:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

产物位于 `app/app/build/outputs/apk/debug/app-debug.apk` 与 `app/app/build/outputs/apk/release/app-release.apk`（相对于仓库根目录）。已连接 Android 设备或启动模拟器时，可在 Gradle 根目录执行：

```bash
./gradlew :app:connectedDebugAndroidTest
```

PDF 原生渲染由 `PdfPreviewInstrumentedTest` 覆盖，需要设备／模拟器执行；Robolectric 截图不能替代 PDFium 验证。

真实 dsh + 插件协议测试需先安装 [插件依赖](../plugin/README.md#开发与测试)，并确保 dsh、pnpm 与 JDK 17 可用。从仓库根目录执行：

```bash
cd plugin
node test/integration/kotlin-live.mjs
```

真实模型测试仅供本地显式运行，需要环境中已有 `DEEPSEEK_API_KEY`，会调用模型并触发实际工具审批，不进入 CI：

```bash
# 从仓库根目录开始
cd plugin
E2E_FRAMES=/tmp/frames.json node test/integration/real-model.mjs
cd ../app
E2E_FRAMES=/tmp/frames.json ./gradlew :core:protocol:test
```

帧文件可能包含会话内容，请妥善保管。CI 的检查和发布步骤见 [ci.yml](../.github/workflows/ci.yml)。

### Release 签名

`assembleRelease` 开启 R8 minify 与 resource shrinking。以下四个本地环境变量均为非空时使用指定 keystore，否则回退到 debug 签名：

| 环境变量 | 含义 |
|---|---|
| `DSH_ANDROID_KEYSTORE_FILE` | keystore 文件路径 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码，也支持 `DSH_ANDROID_KEYSTORE_PASSWORD` |
| `ANDROID_KEY_ALIAS` | key alias，也支持 `DSH_ANDROID_KEY_ALIAS` |
| `ANDROID_KEY_PASSWORD` | key 密码，也支持 `DSH_ANDROID_KEY_PASSWORD` |

CI 另用 `ANDROID_KEYSTORE_B64` Secret 生成临时文件，完整说明见 [根 README](../README.zh-CN.md#ci)。不要提交 keystore、密钥或会话凭据；debug 与发布密钥不同可能导致 APK 无法覆盖安装。
