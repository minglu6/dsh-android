package dev.dsh.pocket.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

open class PocketApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Pocket.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ConnectionService.CH_CONN, "连接", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(ConnectionService.CH_APPROVAL, "审批", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(
            NotificationChannel(ConnectionService.CH_TASK, L.notificationChannelTasks(), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }
}

/** Keeps the WebSocket alive in the background and surfaces approvals / task events as notifications. */
class ConnectionService : Service() {
    companion object {
        const val CH_CONN = "conn"
        const val CH_APPROVAL = "approval"
        const val CH_TASK = "task"
        const val EXTRA_SESSION_ID = "sessionId"
        private const val ID_FG = 1
        private const val ID_TASK_BASE = 40_000
        fun start(ctx: Context) = runCatching { ctx.startForegroundService(Intent(ctx, ConnectionService::class.java)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val shown = HashSet<String>()
    private val restoreGate = RestoreAttemptGate()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val n = fgNotification("连接中…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_FG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID_FG, n)
        registerNetworkCallback()
        scope.launch {
            combine(Pocket.active, Pocket.status) { id, st -> id to st[id] }.collect { (id, dot) ->
                if (id == null) { stopSelf(); return@collect }
                val name = Pocket.activeProfile?.name ?: ""
                val text = when (dot) {
                    Dot.Online -> "已连接 $name"
                    Dot.Unauthorized -> "$name 授权失效"
                    Dot.Offline -> "$name 离线，重连中"
                    else -> "连接 $name 中…"
                }
                notify(ID_FG, fgNotification(text))
            }
        }
        scope.launch {
            Pocket.approvals.collect { list ->
                val ids = list.map { it.approvalId }.toSet()
                (shown - ids).forEach { NotificationManagerCompat.from(this@ConnectionService).cancel(it.hashCode()) }
                shown.retainAll(ids)
                if (Pocket.prefs.approvalNotify) list.filter { it.approvalId !in shown }.forEach { a ->
                    shown += a.approvalId
                    notify(a.approvalId.hashCode(), approvalNotification(a))
                }
            }
        }
        scope.launch {
            Pocket.taskEvents.collect { event ->
                notify(taskNotificationId(event), taskNotification(event))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onDestroy() {
        unregisterNetworkCallback()
        scope.cancel()
        super.onDestroy()
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkUp()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) onNetworkUp()
            }
        }
        networkCallback = cb
        runCatching { cm.registerNetworkCallback(request, cb) }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    private fun onNetworkUp() {
        val generation = restoreGate.begin()
        // Deliver only if this generation is still current when posted (coalesces bursts).
        android.os.Handler(mainLooper).post(
            restoreGate.callback(generation) { Pocket.onNetworkRestored() },
        )
    }

    private fun notify(id: Int, n: Notification) = runCatching { NotificationManagerCompat.from(this).notify(id, n) }

    private fun openApp(sessionId: String? = null): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (sessionId != null) intent.putExtra(EXTRA_SESSION_ID, sessionId)
        val req = sessionId?.hashCode() ?: 0
        return PendingIntent.getActivity(this, req, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun fgNotification(text: String) = NotificationCompat.Builder(this, CH_CONN)
        .setSmallIcon(R.drawable.ic_stat_dsh).setContentTitle(getString(R.string.app_name)).setContentText(text)
        .setOngoing(true).setContentIntent(openApp()).build()

    private fun approvalNotification(a: dev.dsh.pocket.protocol.Approval): Notification {
        fun action(outcome: String, req: Int) = PendingIntent.getBroadcast(
            this, a.approvalId.hashCode() * 2 + req,
            Intent(this, ApprovalReceiver::class.java).putExtra("approvalId", a.approvalId).putExtra("outcome", outcome),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cmd = approvalCommand(a)
        return NotificationCompat.Builder(this, CH_APPROVAL)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle("工具 ${a.toolName} 请求越权执行")
            .setContentText(cmd ?: a.reason ?: "等待审批")
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(cmd, a.reason).joinToString("\n")))
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp()).setAutoCancel(false).setOngoing(true)
            .addAction(0, "拒绝", action("rejected", 0)).addAction(0, "允许一次", action("allowed-once", 1)).build()
    }

    private fun taskNotification(event: TaskEvent): Notification {
        val title = sessionTitle(event.sessionId)
        return NotificationCompat.Builder(this, CH_TASK)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle(TaskNotifyTexts.title(event.kind))
            .setContentText(TaskNotifyTexts.body(event.kind, title))
            .setStyle(NotificationCompat.BigTextStyle().bigText(TaskNotifyTexts.body(event.kind, title)))
            .setContentIntent(openApp(event.sessionId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
    }

    private fun sessionTitle(sessionId: String): String? =
        Pocket.sessions.value.firstOrNull { it.sessionId == sessionId }?.title
            ?: Pocket.chat.value?.takeIf { it.sessionId == sessionId }?.title

    private fun taskNotificationId(event: TaskEvent): Int =
        ID_TASK_BASE + TaskNotifyTexts.tag(event).hashCode()
}

class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("approvalId") ?: return
        val outcome = intent.getStringExtra("outcome") ?: return
        Pocket.init(context.applicationContext)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { withTimeoutOrNull(8000) { Pocket.answer(id, outcome).join() } } finally { pending.finish() }
        }
        NotificationManagerCompat.from(context).cancel(id.hashCode())
    }
}

/** Best-effort one-line command for an approval, from the matching tool call in the open chat. */
fun approvalCommand(a: dev.dsh.pocket.protocol.Approval): String? {
    val tool = Pocket.chat.value?.items?.filterIsInstance<dev.dsh.pocket.protocol.Item.Tool>()?.lastOrNull { it.callId == a.callId } ?: return null
    return primaryArg(tool.name, tool.arguments)
}
