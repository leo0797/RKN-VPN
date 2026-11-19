package app.rkn.vpn.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rkn.vpn.R
import app.rkn.vpn.ui.components.SingleLineLabel
import app.rkn.vpn.ui.screens.AppsScreen
import app.rkn.vpn.ui.screens.HomeScreen
import app.rkn.vpn.ui.screens.RoutingScreen
import app.rkn.vpn.ui.screens.ServersScreen
import app.rkn.vpn.ui.screens.SettingsScreen
import app.rkn.vpn.ui.theme.RknTheme
import app.rkn.vpn.vpn.VpnController

private data class NavItem(val labelRes: Int, val icon: Int)

private val NAV_ITEMS = listOf(
    NavItem(R.string.home, R.drawable.ic_shield),
    NavItem(R.string.servers, R.drawable.ic_servers),
    NavItem(R.string.rules, R.drawable.ic_route),
    NavItem(R.string.apps, R.drawable.ic_apps),
    NavItem(R.string.more, R.drawable.ic_settings)
)

private const val TAB_HOME = 0
private const val TAB_SERVERS = 1
private const val TAB_ROUTING = 2
private const val TAB_APPS = 3
private const val TAB_SETTINGS = 4

@Composable
fun AppRoot(onRequestConnect: () -> Unit) {
    val state by VpnController.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(TAB_HOME) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val startLanguage = remember { state.language }

    LaunchedEffect(state.language) {
        if (state.language != startLanguage) (context as? Activity)?.recreate()
    }

    LaunchedEffect(Unit) { VpnController.maybeAutoRefresh() }

    LaunchedEffect(state.error) {
        val message = state.error ?: return@LaunchedEffect
        snackbar.showSnackbar(
            message = message,
            withDismissAction = true,
            duration = SnackbarDuration.Long
        )
        VpnController.clearError()
    }

    LaunchedEffect(state.toast) {
        val message = state.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        VpnController.consumeToast()
    }

    BackHandler(enabled = tab != TAB_HOME) { tab = TAB_HOME }

    RknTheme(theme = state.theme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NAV_ITEMS.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = {
                                Icon(
                                    painter = painterResource(item.icon),
                                    contentDescription = stringResource(item.labelRes)
                                )
                            },
                            label = {
                                // Пять пунктов на узких экранах: подпись всегда одна
                                // строка. WordBreak/Hyphens заданы в SingleLineLabel,
                                // чтобы «Приложения» не превращалось в «Приложен-ия».
                                SingleLineLabel(
                                    text = stringResource(item.labelRes),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.5.sp,
                                        lineHeight = 13.sp,
                                        letterSpacing = 0.sp
                                    ),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (tab) {
                    TAB_HOME -> HomeScreen(
                        state = state,
                        onConnect = onRequestConnect,
                        onDisconnect = { VpnController.disconnect() },
                        onRefresh = {
                            if (state.subscriptionUrl.isBlank()) tab = TAB_SETTINGS
                            else VpnController.refreshSubscription()
                        },
                        onBest = { VpnController.selectBestNode() },
                        onOpenServers = { tab = TAB_SERVERS },
                        onAddSubscription = { tab = TAB_SETTINGS }
                    )

                    TAB_SERVERS -> ServersScreen(
                        state = state,
                        onSelect = { VpnController.selectNode(it) },
                        onRefresh = { VpnController.refreshSubscription() },
                        onPingAll = { VpnController.toggleLatencyTest() },
                        onBest = { VpnController.selectBestNode() },
                        onCheckHealth = { VpnController.checkNodesHealth() }
                    )

                    TAB_ROUTING -> RoutingScreen(
                        state = state,
                        onModeChange = { VpnController.setRoutingMode(it) },
                        onLanDirectChange = { VpnController.setLanDirect(it) },
                        onAddRule = { VpnController.addRule(it) },
                        onDeleteRule = { VpnController.deleteRule(it) }
                    )

                    TAB_APPS -> AppsScreen(
                        state = state,
                        onToggleSplit = { VpnController.setSplitEnabled(it) },
                        onSetApps = { VpnController.setSplitApps(it) }
                    )

                    TAB_SETTINGS -> SettingsScreen(
                        state = state,
                        onSaveSubscription = { url ->
                            VpnController.setSubscriptionUrl(url)
                            if (url.isNotBlank()) VpnController.refreshSubscription()
                        },
                        onRefresh = { VpnController.refreshSubscription() },
                        onThemeChange = { VpnController.setTheme(it) }
                    )
                }
            }
        }
    }
}
