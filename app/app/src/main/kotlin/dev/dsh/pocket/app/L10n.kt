package dev.dsh.pocket.app

/**
 * Desktop locale strings for new parity surfaces.
 * zh is the key-set source of truth; en mirrors desktop when [AppLanguage.En].
 */
object L {
    private val en get() = runCatching { Pocket.prefs.language == AppLanguage.En }.getOrDefault(false)

    // Busy send / composer
    fun sendMessage() = if (en) "Send message" else "发送消息"
    fun queueSend() = if (en) "Queue message" else "排队发送"
    fun steerSend() = if (en) "Steer message" else "插话发送"
    fun busySendLabel(busy: BusySend, running: Boolean): String = when {
        !running -> sendMessage()
        busy == BusySend.Steer -> steerSend()
        else -> queueSend()
    }

    // Image
    fun image() = if (en) "Image" else "图片"
    fun viewOriginal() = if (en) "View original" else "查看原图"
    fun imageLoading() = if (en) "Loading image…" else "图片加载中…"
    fun imageLoadFailed() = if (en) "Failed to load image; click to retry" else "图片加载失败，点击重试"

    // Commands / references
    fun sectionAdd() = if (en) "Add" else "添加"
    fun sectionCommands() = if (en) "Commands" else "指令"
    fun searchEllipsis() = if (en) "Search…" else "搜索…"
    fun noOptions() = if (en) "No options" else "无选项"
    fun filesAndFolders() = if (en) "Files & folders" else "文件与文件夹"
    fun subagents() = if (en) "Subagents" else "子智能体"
    fun conversations() = if (en) "Conversations" else "对话"

    // Common / message actions (desktop locale)
    fun copy() = if (en) "Copy" else "复制"
    fun copied() = if (en) "Copied" else "复制成功"
    fun branch() = if (en) "Branch into a new conversation" else "在新对话中分支"
    fun branchUnavailable() = if (en) "Available only on the last message of a completed turn" else "仅可从已完成轮次的最后一条消息分支"
    fun wrapLines() = if (en) "Wrap lines" else "自动换行"
    fun unwrapLines() = if (en) "Do not wrap lines" else "取消自动换行"
    fun menu() = if (en) "Menu" else "菜单"

    // Feedback
    fun like() = if (en) "Good response" else "好的回答"
    fun undoRating() = if (en) "Remove rating" else "取消标记"
    fun dislike() = if (en) "Bad response" else "有问题的回答"
    fun submitFeedback() = if (en) "Submit feedback" else "提交反馈"
    fun feedbackCategory() = if (en) "Feedback category" else "反馈分类"
    fun feedbackDetail() = if (en) "Feedback details" else "反馈详情"
    fun feedbackHint() = if (en) "Add details to help us improve…" else "填写详情以帮助我们改进…"
    fun thanksFeedback() = if (en) "Thanks for your feedback" else "感谢你的反馈"
    fun feedbackCategories(): List<Pair<String, String>> = listOf(
        "task-result" to if (en) "Task result" else "任务结果",
        "instruction-following" to if (en) "Instruction understanding and following" else "指令理解与遵循",
        "product-interaction" to if (en) "Product features and interaction" else "产品功能与交互",
        "service-stability" to if (en) "Stability and speed" else "稳定性和速度",
        "resource-cost" to if (en) "Resource usage and cost" else "资源使用与费用",
        "security-privacy-permission" to if (en) "Security, privacy, and permissions" else "安全隐私与权限",
        "other" to if (en) "Other" else "其他",
    )

    // Deliverables
    fun deliverablesTitle() = if (en) "Present files" else "交付文件"
    fun delivered() = if (en) "Presented" else "已交付"
    fun editedCount(count: Int) = if (en) "Edited $count files" else "已编辑 $count 个文件"
    fun editedName(name: String) = if (en) "Edited $name" else "已编辑 $name"

    // User questions
    fun completeQuestionFirst() = if (en) "Please complete this question first." else "请先完成这道问题。"
    fun selectOrCustom() = if (en) "Please select an option or enter a custom answer." else "请选择一个选项或填写自定义答案。"
    fun recommended() = if (en) "Recommended" else "推荐"
    fun typeYourAnswer() = if (en) "Type your answer" else "输入你的答案"
    fun skip() = if (en) "Skip" else "跳过"
    fun nextQuestion() = if (en) "Next" else "下一题"
    fun planReview() = if (en) "Plan review" else "计划待审"
    fun approvePlan() = if (en) "Approve" else "同意执行"
    fun declinePlan() = if (en) "Refuse" else "拒绝"
    fun requestChanges() = if (en) "Request changes" else "要求修改"
    fun planChip() = if (en) "Plan" else "计划"
    fun planModeOn() = if (en) "Plan mode on — tap to turn off" else "计划模式已开启 — 点击关闭"

    // Search / archive / pin (ui-workspace + common)
    fun search() = if (en) "Search" else "搜索"
    fun searchSessionsAria() = if (en) "Search sessions" else "搜索会话"
    fun searchSessions() = if (en) "Search session names" else "搜索会话名称"
    fun searchingSessions() = if (en) "Searching session history…" else "正在搜索会话历史…"
    fun noMatchingSessions() = if (en) "No matching sessions" else "无匹配会话"
    fun clearSearch() = if (en) "Clear search" else "清除搜索"
    fun archiveSession() = if (en) "Archive session" else "归档会话"
    fun unarchiveSession() = if (en) "Unarchive session" else "取消归档"
    fun pinSession() = if (en) "Pin session" else "置顶会话"
    fun unpinSession() = if (en) "Unpin session" else "取消置顶"
    fun archived() = if (en) "Archived" else "已归档"
    fun pinned() = if (en) "Pinned" else "已置顶"
    fun archivedToast() = if (en) "Session archived; you can " else "会话已归档，可"
    fun undo() = if (en) "Undo" else "撤销"

    // Sidebar workspace browser (ui-workspace)
    fun sectionSessions() = if (en) "Sessions" else "会话"
    fun sectionWorkspaces() = if (en) "Workspaces" else "工作区"
    fun viewOptions() = if (en) "View options" else "视图选项"
    fun groupByLabel() = if (en) "Group by" else "分组方式"
    fun groupByWorkspace() = if (en) "WorkSpace" else "按工作区"
    fun groupByWorkspaceTree() = if (en) "Workspace Tree" else "按工作区树"
    fun groupByFlat() = if (en) "In one list" else "单列表"
    fun orderByLabel() = if (en) "Order by" else "排序方式"
    fun orderByManual() = if (en) "Manual" else "手动排序"
    fun orderByUpdated() = if (en) "Last updated" else "最近更新"
    fun filterByLabel() = if (en) "Filter sessions" else "筛选会话"
    fun hideArchived() = if (en) "Hide archived" else "隐藏已归档"
    fun showArchived() = if (en) "All conversations (show archived)" else "全部对话（显示已归档）"
    fun onlyArchived() = if (en) "Archived only" else "仅显示已归档"
    fun addWorkspace() = if (en) "Add workspace" else "添加工作区"
    fun addWorkspaceEllipsis() = if (en) "Add workspace…" else "添加工作区…"
    fun workspacePathPlaceholder() = if (en) "Host directory path" else "主机目录路径"
    fun emptySessions() = if (en) "No sessions yet" else "暂无会话"
    fun emptyArchivedSessions() = if (en) "No archived sessions yet" else "暂无已归档会话"
    fun ungrouped() = if (en) "Ungrouped" else "未分组"

    // File attach
    fun pendingFiles() = if (en) "Pending files" else "待发送文件"
    fun removeFile(name: String) = if (en) "Remove file $name" else "移除文件 $name"
    fun uploading() = if (en) "Uploading…" else "上传中…"
    fun uploadFailed() = if (en) "Upload failed; click to retry" else "上传失败，点击重试"
    fun file() = if (en) "File" else "文件"

    // Settings (desktop-aligned labels)
    fun language() = if (en) "Language" else "语言"
    fun appearance() = if (en) "Appearance" else "外观"
    fun light() = if (en) "Light" else "浅色"
    fun dark() = if (en) "Dark" else "深色"
    fun followSystem() = if (en) "System" else "跟随系统"

    // Connection diagnostics (desktop pocket locales)
    fun diagnostics() = if (en) "Connection diagnostics" else "连接诊断"
    fun openDiagnostics() = if (en) "Open connection diagnostics" else "打开连接诊断"
    fun diagnosticsCopy() = if (en) "Copy redacted report" else "复制脱敏报告"
    fun diagnosticsCopied() = if (en) "Copied" else "已复制"
    fun diagnosticsChecking() = if (en) "Checking…" else "正在检查…"
    fun diagnosticsUnavailable() = if (en) "Diagnostics unavailable" else "诊断服务暂不可用"
    fun nearbyComputers() = if (en) "Nearby computers" else "附近的电脑"
    fun repairPair() = if (en) "Pair again" else "重新配对"
    fun online() = if (en) "Online" else "在线"
    fun offline() = if (en) "Offline" else "离线"
    fun revoked() = if (en) "Revoked" else "已撤销"

    // Termux sidecar (APK is controller-only; dsh runs in Termux)
    fun termuxMode() = if (en) "On-device Termux" else "本机 Termux"
    fun termuxModeHint() = if (en)
        "Connect to dsh + pocket listening on 127.0.0.1 in Termux. This app never embeds Node/dsh and never runs su."
    else
        "连接 Termux 里监听在 127.0.0.1 的 dsh + pocket。本 App 不内嵌 Node/dsh，也不会执行 su。"
    fun termuxPort() = if (en) "Port" else "端口"
    fun termuxName() = if (en) "Name" else "名称"
    fun termuxDefaultName() = if (en) "Termux" else "本机 Termux"
    fun termuxPaste() = if (en) "Pairing URI or token" else "配对串或 token"
    fun termuxPairCode() = if (en) "Pairing code" else "配对码"
    fun termuxConnect() = if (en) "Connect" else "连接"
    fun termuxShowHelp() = if (en) "Termux not running? Show install commands" else "端口未开？查看 Termux 安装命令"
    fun termuxPortDead() = if (en)
        "Nothing is listening on loopback. Start dsh in Termux, then retry."
    else
        "本机回环端口无响应。请在 Termux 启动 dsh 后再试。"
    fun termuxHelpTitle() = if (en) "Termux sidecar" else "Termux sidecar"
    fun termuxHelpBody() = if (en)
        "The APK is a controller only. Install Termux from community releases (not Play Store), run dsh + pocket there, bind 127.0.0.1, then pair with dshpocket:// or paste the owner token."
    else
        "APK 只做控制器。请从社区渠道安装 Termux（勿用 Play 商店旧包），在其中运行 dsh + pocket，绑定 127.0.0.1，再用 dshpocket:// 或粘贴 owner token 配对。"
    fun termuxInstallers() = if (en)
        "Installers: GitHub termux-app releases · F-Droid com.termux (this repo does not vendor Termux)."
    else
        "安装来源：GitHub termux-app releases · F-Droid com.termux（本仓库不内置 Termux）。"
    fun termuxBootstrap() = if (en) "Install / run commands" else "安装与启动命令"
    fun termuxPrivilegeLabel() = if (en) "Elevated via su/tsu (reminder)" else "经 su/tsu 提权（提醒）"
    fun termuxPrivilegeHint() = if (en)
        "If tools need root, start dsh under tsu/su inside Termux yourself. This app never runs su."
    else
        "若工具需要 root，请在 Termux 内自行用 tsu/su 启动 dsh。本 App 从不执行 su。"
    // Task notifications (desktop-aligned; adapted from saya-ch/dsh-mobile labels)
    fun taskFinished() = if (en) "Task finished" else "任务已完成"
    fun taskFinishedBody() = if (en) "Your DSH task finished" else "你的 DSH 任务已完成"
    fun taskFinishedBodyNamed(title: String) =
        if (en) "Your DSH task finished: $title" else "你的 DSH 任务已完成：$title"
    fun inputNeeded() = if (en) "Input needed" else "需要你确认"
    fun waitingForAnswer() = if (en) "Waiting for your answer" else "等待你的回答"
    fun waitingForAnswerNamed(title: String) =
        if (en) "$title — waiting for your answer" else "$title — 等待你的回答"
    fun notificationChannelTasks() = if (en) "Tasks" else "任务"

    // Settings nav / pages — desktop locales (ui-settings-models / plugins / schedule / credentials)
    fun settings() = if (en) "Settings" else "设置"
    fun generalNav() = if (en) "General" else "通用设置"
    fun modelsNav() = if (en) "Models" else "模型"
    fun modelsTitle() = if (en) "Models" else "模型"
    fun modelsIntro() = if (en) "Enter your API keys to use models from the following providers." else "填入各提供商的 API 密钥即可使用其模型。"
    fun pluginsNav() = if (en) "Plugins" else "插件"
    fun pluginsTitle() = if (en) "Plugins" else "插件"
    fun pluginsIntro() = if (en) "Install, enable, and configure plugins" else "安装、启用和配置插件"
    fun builtinPluginsNav() = if (en) "Built-in plugins" else "内置插件"
    fun scheduleNav() = if (en) "Scheduled tasks" else "自动化任务"
    fun scheduleTitle() = if (en) "Automation tasks" else "自动化任务"
    fun credentialsNav() = if (en) "Credentials" else "凭证"
    fun credentialsTitle() = if (en) "Credentials" else "凭证"
    fun credentialConfigured() = if (en) "API key configured" else "API 密钥已配置"
    fun credentialMissing() = if (en) "API key missing" else "API 密钥缺失"
    fun apiKey() = if (en) "API key" else "API 密钥"
    fun apiKeyPlaceholder() = if (en) "Enter your API key" else "输入 API 密钥"
    fun keyBlankKeep() = if (en) "Enter the API key, or leave the field empty to keep the stored one." else "输入 API 密钥，或留空以保留已存储的密钥。"
    fun save() = if (en) "Save" else "保存"
    fun delete() = if (en) "Delete" else "删除"
    fun add() = if (en) "Add" else "添加"
    fun retry() = if (en) "Retry" else "重试"
    fun loading() = if (en) "Loading…" else "加载中…"
    fun unavailable() = if (en) "Unavailable on this connection." else "当前连接不可用。"
    fun enabled() = if (en) "Enabled" else "已启用"
    fun disabled() = if (en) "Disabled" else "已停用"
    fun statusActive() = if (en) "Enabled" else "已开启"
    fun statusInactive() = if (en) "Inactive" else "已结束"
    fun nextRun() = if (en) "Next run" else "下次运行"
    fun scheduleListAria() = if (en) "Automation tasks" else "自动化任务"
    fun emptySchedules() = if (en) "No automation tasks" else "暂无自动化任务"
    fun emptyPlugins() = if (en) "No plugins are installed yet." else "还没有安装任何插件。"
    fun emptyCredentials() = if (en) "No credentials." else "暂无凭证。"
    fun createSchedule() = if (en) "New task" else "新建任务"
    fun scheduleTitleField() = if (en) "Title" else "标题"
    fun schedulePromptField() = if (en) "Instruction" else "指令"
    fun scheduleAfterSeconds() = if (en) "After (seconds)" else "延迟（秒）"
    fun deliveryHistory() = if (en) "Run history" else "运行历史"
    fun maskedSecret() = "••••••••"
    fun refName() = if (en) "Reference name" else "引用名"
    fun addCredential() = if (en) "Add credential" else "添加凭证"
    fun confirmDelete() = if (en) "Delete?" else "确认删除？"
    fun cancel() = if (en) "Cancel" else "取消"
    fun countUnit() = if (en) "plugins" else "个"
    fun modelsCatalog() = if (en) "Model catalog" else "模型目录"
    fun providerDetail() = if (en) "Provider" else "提供商"

    fun collapse() = if (en) "Collapse" else "收起"
    fun expand() = if (en) "Expand" else "展开"

    // Document preview (sidebarDocumentPreview + image/pdf renderer locales)
    fun docLoading() = if (en) "Rendering document..." else "文档渲染中..."
    fun docLoadMore() = if (en) "Load more" else "加载更多"
    fun docRetry() = if (en) "Retry" else "重试"
    fun docUnsupported() = if (en) "Preview is not available for this file type yet." else "该格式文件暂时无法预览"
    fun docErrorUnavailable(message: String) =
        if (en) "Read failed: $message" else "读取失败：$message"
    fun imagePreviewFailed() = if (en) "This image could not be displayed." else "无法显示这张图片"
    fun pdfRendering() = if (en) "Rendering page…" else "正在绘制页面…"
    fun pdfFailed(message: String) =
        if (en) "Cannot display PDF: $message" else "无法显示 PDF：$message"
    fun pdfPage(page: Int) = if (en) "PDF page $page" else "PDF 第 $page 页"
    fun pdfUnsupported() = if (en) "PDF preview requires the complete file contents." else "PDF 预览需要完整文件内容"

    // Layout (phone chrome; mirrors desktop always-on sidebar on wide screens)
    fun layout() = if (en) "Layout" else "布局"
    fun layoutAuto() = if (en) "Auto" else "自动"
    fun layoutPhone() = if (en) "Phone" else "手机"
    fun layoutDesktop() = if (en) "Desktop" else "桌面"
}

/** Composer primary-action label from running state + [BusySend] pref. */
fun composerSendLabel(running: Boolean, busy: BusySend = Pocket.prefs.busySend): String =
    L.busySendLabel(busy, running)
