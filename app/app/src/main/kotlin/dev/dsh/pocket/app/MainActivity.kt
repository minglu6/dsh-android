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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.protocol.PairingLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class Screen {
    Chat, Connections, Settings, General,
    Models, Plugins, Schedule,
}

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
    var screen by rememberSaveable { mutableStateOf(if (Pocket.active.value == null) Screen.Connections else Screen.Chat) }
    /** Plugins / Schedule can open from Settings or the drawer; back returns to the source. */
    var panelReturn by rememberSaveable { mutableStateOf(Screen.Settings) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val widthDp = LocalConfiguration.current.screenWidthDp
    val layoutMode = Pocket.prefs.layoutMode
    val twoPane = useTwoPaneLayout(widthDp, layoutMode)
    var sidebarCollapsed by rememberSaveable { mutableStateOf(Pocket.prefs.sidebarCollapsed) }
    fun toggleSidebar() {
        sidebarCollapsed = !sidebarCollapsed
        Pocket.prefs.sidebarCollapsed = sidebarCollapsed
    }
    var drawerMenuOpen by remember { mutableStateOf(false) }

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

    BackHandler(screen != Screen.Chat && active != null) {
        screen = when (screen) {
            Screen.General, Screen.Models -> Screen.Settings
            Screen.Plugins, Screen.Schedule -> panelReturn
            else -> Screen.Chat
        }
    }
    BackHandler(!twoPane && drawer.isOpen) { scope.launch { drawer.close() } }

    Box(Modifier.fillMaxSize().background(T.Bg)) {
        when (screen) {
            Screen.Connections -> ConnectionsScreen(
                pairLink = link, onPairLinkConsumed = { deepLink.value = null },
                canBack = active != null, onBack = { screen = Screen.Chat },
                onConnected = { screen = Screen.Chat },
            )
            Screen.Settings -> SettingsScreen(
                onBack = { screen = Screen.Chat },
                onGeneral = { screen = Screen.General },
                onConnections = { screen = Screen.Connections },
                onModels = { screen = Screen.Models },
                onPlugins = { panelReturn = Screen.Settings; screen = Screen.Plugins },
                onSchedule = { panelReturn = Screen.Settings; screen = Screen.Schedule },
            )
            Screen.General -> GeneralSettingsScreen(onBack = { screen = Screen.Settings })
            Screen.Models -> ModelsSettingsScreen(onBack = { screen = Screen.Settings })
            Screen.Plugins -> PluginsSettingsScreen(onBack = { screen = panelReturn })
            Screen.Schedule -> ScheduleSettingsScreen(
                onBack = { screen = panelReturn },
                onNewTask = { Pocket.closeChat(); screen = Screen.Chat },
                onOpenSession = { Pocket.openSession(it); screen = Screen.Chat },
            )
            Screen.Chat -> {
                val drawerContent: @Composable () -> Unit = {
                    SessionDrawer(
                        permanent = twoPane,
                        onPick = { Pocket.openSession(it); if (!twoPane) scope.launch { drawer.close() } },
                        onNew = { Pocket.closeChat(); if (!twoPane) scope.launch { drawer.close() } },
                        onSettings = { screen = Screen.Settings; if (!twoPane) scope.launch { drawer.close() } },
                        onConnections = { screen = Screen.Connections; if (!twoPane) scope.launch { drawer.close() } },
                        onPlugins = {
                            panelReturn = Screen.Chat
                            screen = Screen.Plugins
                            if (!twoPane) scope.launch { drawer.close() }
                        },
                        onSchedule = {
                            panelReturn = Screen.Chat
                            screen = Screen.Schedule
                            if (!twoPane) scope.launch { drawer.close() }
                        },
                        onMenuOpenChange = { drawerMenuOpen = it },
                        onCollapse = if (twoPane) ::toggleSidebar else null,
                    )
                }
                if (twoPane) {
                    Row(Modifier.fillMaxSize().statusBarsPadding()) {
                        if (!sidebarCollapsed) {
                            drawerContent()
                            VerticalDivider(color = T.Border, thickness = 0.5.dp)
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().navigationBarsPadding()) {
                            ChatRoot(onMenu = ::toggleSidebar, showMenuButton = true)
                        }
                    }
                } else {
                    ModalNavigationDrawer(
                        drawerState = drawer,
                        gesturesEnabled = !drawerMenuOpen,
                        drawerContent = { drawerContent() },
                    ) {
                        ChatRoot(onMenu = { scope.launch { drawer.open() } }, showMenuButton = true)
                    }
                }
            }
        }
        SnackbarHost(snack, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 120.dp)) {
            Snackbar(
                it,
                containerColor = ThemeState.colors.ToastBg,
                contentColor = ThemeState.colors.ToastLabel,
                shape = T.R12,
            )
        }
    }
    if (profiles.isEmpty() && screen == Screen.Chat) screen = Screen.Connections
}
