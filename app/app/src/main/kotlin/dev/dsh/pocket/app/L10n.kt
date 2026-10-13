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

    // Search / archive / pin
    fun searchSessions() = if (en) "Search session titles" else "搜索会话名称"
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
}

/** Composer primary-action label from running state + [BusySend] pref. */
fun composerSendLabel(running: Boolean, busy: BusySend = Pocket.prefs.busySend): String =
    L.busySendLabel(busy, running)
