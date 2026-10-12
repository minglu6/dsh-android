package dev.dsh.pocket.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import dev.dsh.pocket.protocol.PairingLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class Screen { Chat, Connections, Settings, General }

class MainActivity : ComponentActivity() {
    /** Asked only once a desktop is connected (approval notifications need it). */
    val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val deepLink = MutableStateFlow<PairingLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allowScreenshotsAndRecording()
        enableEdgeToEdge()
        handle(intent)
        setContent {
            // Re-assert on composition: camera / OEM code must not leave FLAG_SECURE on.
            AllowScreenshotsEffect()
            PocketTheme { Root(deepLink) }
        }
    }

    override fun onResume() {
        super.onResume()
        allowScreenshotsAndRecording()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    private fun handle(i: Intent?) {
        i?.dataString?.let(PairingLink::parse)?.let { deepLink.value = it }
        i?.getStringExtra(ConnectionService.EXTRA_SESSION_ID)?.let { sessionId ->
            if (sessionId.isNotBlank()) Pocket.openSession(sessionId)
        }
    }

    /** Screenshots and screen recording must always work (pairing codes, terminal, tokens included). */
    private fun allowScreenshotsAndRecording() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(true)
    }
}

/** Clears FLAG_SECURE on the host activity window if anything re-set it. */
@Composable
fun AllowScreenshotsEffect() {
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

@Composable
private fun Root(deepLink: MutableStateFlow<PairingLink?>) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val active by Pocket.active.collectAsState()
    val profiles by Pocket.profiles.collectAsState()
    val link by deepLink.collectAsState()
    var screen by remember { mutableStateOf(if (Pocket.active.value == null) Screen.Connections else Screen.Chat) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(active) {
        if (active == null) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            (ctx as? MainActivity)?.notifPermission?.launch(Manifest.permission.POST_NOTIFICATIONS)
        ConnectionService.start(ctx)
    }
    LaunchedEffect(Unit) { Pocket.errors.collect { snack.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        Pocket.notices.collect { msg ->
            if (msg == L.archivedToast()) {
                val result = snack.showSnackbar(
                    message = msg,
                    actionLabel = L.undo(),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    Pocket.lastArchivedId?.let { Pocket.unarchiveSession(it) }
                }
            } else {
                snack.showSnackbar(msg)
            }
        }
    }
    LaunchedEffect(link) { if (link != null) screen = Screen.Connections }

    BackHandler(screen != Screen.Chat && active != null) { screen = if (screen == Screen.General) Screen.Settings else Screen.Chat }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }

    Box(Modifier.fillMaxSize()) {
        when (screen) {
            Screen.Connections -> ConnectionsScreen(
                pairLink = link, onPairLinkConsumed = { deepLink.value = null },
                canBack = active != null, onBack = { screen = Screen.Chat },
                onConnected = { screen = Screen.Chat },
            )
            Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Chat }, onGeneral = { screen = Screen.General }, onConnections = { screen = Screen.Connections })
            Screen.General -> GeneralSettingsScreen(onBack = { screen = Screen.Settings })
            Screen.Chat -> ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = {
                    SessionDrawer(
                        onPick = { Pocket.openSession(it); scope.launch { drawer.close() } },
                        onNew = { Pocket.closeChat(); scope.launch { drawer.close() } },
                        onSettings = { screen = Screen.Settings; scope.launch { drawer.close() } },
                        onConnections = { screen = Screen.Connections; scope.launch { drawer.close() } },
                    )
                },
            ) { ChatRoot(onMenu = { scope.launch { drawer.open() } }) }
        }
        SnackbarHost(snack, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 120.dp)) {
            Snackbar(it, containerColor = T.Ink, contentColor = Color.White, shape = T.R12)
        }
    }
    if (profiles.isEmpty() && screen == Screen.Chat) screen = Screen.Connections
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
