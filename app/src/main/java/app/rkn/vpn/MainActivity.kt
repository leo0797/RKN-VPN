package app.rkn.vpn

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.rkn.vpn.core.DeepLinkCoordinator
import app.rkn.vpn.core.DeepLinkTarget
import app.rkn.vpn.data.Prefs
import app.rkn.vpn.ui.AppRoot
import app.rkn.vpn.vpn.VpnController
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {

    private lateinit var deepLinks: DeepLinkCoordinator
    private var confirmationDialog: AlertDialog? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            VpnController.connect(this)
        } else {
            VpnController.onPermissionDenied()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLinks = DeepLinkCoordinator(
            onConfirmationRequired = { showDeepLinkDialog(it) },
            profileName = { source -> getString(R.string.deep_link_profile_name, source) },
            addNodes = { value, name -> VpnController.addNodesProfileFromLink(value, name) },
            addSubscription = { url, name -> VpnController.addSubscriptionProfileFromLink(url, name) }
        )
        setContent {
            AppRoot(onRequestConnect = { requestVpnPermission() })
        }
        observeFirstConnection()
        if (savedInstanceState == null) {
            val hadAutoConnectExtra = intent?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) == true
            val deepLinkHandled = handleIntent(intent)
            if (!deepLinkHandled && !hadAutoConnectExtra) maybeAutoConnectOnLaunch()
        }
    }

    override fun onDestroy() {
        confirmationDialog?.dismiss()
        confirmationDialog = null
        super.onDestroy()
    }

    /**
     * Системный диалог POST_NOTIFICATIONS не должен встречать пользователя
     * сразу при открытии приложения — спрашиваем после первого подключения,
     * когда стало ясно, зачем нужны уведомления.
     */
    private fun observeFirstConnection() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VpnController.state.collect { state ->
                    if (!state.connected) return@collect
                    val prefs = Prefs(this@MainActivity)
                    if (prefs.notificationPermissionRequested) return@collect
                    prefs.notificationPermissionRequested = true
                    requestNotificationPermission()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Плитка быстрых настроек не может сама показать системный диалог
     * разрешения VPN, поэтому открывает приложение с этим extra —
     * и мы сразу запрашиваем разрешение и подключаемся.
     *
     * @return true, если intent был deep link: в этом случае автоподключение
     * и автопереподключение запускать нельзя.
     */
    private fun handleIntent(intent: Intent?): Boolean {
        val data = intent?.data
        if (data != null) {
            intent?.data = null
            if (!handleDeepLink(data)) {
                VpnController.showError(getString(R.string.error_deeplink_unknown))
            }
            // Даже если в intent есть EXTRA_AUTO_CONNECT, deep link не должен
            // инициировать соединение без явного выбора пользователя.
            return true
        }

        val autoConnect = intent?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) ?: false
        if (!autoConnect) return false
        intent?.removeExtra(EXTRA_AUTO_CONNECT)
        val state = VpnController.state.value
        if (state.connected || state.connecting || state.nodes.isEmpty()) return false
        requestVpnPermission()
        return false
    }

    /** Только ставит ссылку в очередь ожидания подтверждения. */
    private fun handleDeepLink(data: Uri): Boolean = deepLinks.handleDeepLink(data)

    private fun showDeepLinkDialog(target: DeepLinkTarget) {
        // Если пришла новая ссылка, старый диалог не должен сбросить уже
        // поставленный в очередь новый target через onCancel.
        val previous = confirmationDialog
        confirmationDialog = null
        previous?.dismiss()
        val nodes = target.nodeCount?.toString()
            ?: getString(R.string.deep_link_nodes_unknown)
        val message = buildString {
            append(getString(R.string.deep_link_source, target.source))
            append('\n')
            append(getString(R.string.deep_link_nodes, nodes))
            if (target.insecureHttp) {
                append("\n\n")
                append(getString(R.string.warning_http_subscription))
            }
        }
        confirmationDialog = AlertDialog.Builder(this)
            .setTitle(R.string.deep_link_title)
            .setMessage(message)
            .setPositiveButton(R.string.add) { _, _ ->
                confirmationDialog = null
                lifecycleScope.launch {
                    if (!deepLinks.confirm()) {
                        VpnController.showError(getString(R.string.error_deeplink_failed))
                    }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                deepLinks.cancel()
                confirmationDialog = null
            }
            .setOnCancelListener { dialog ->
                if (confirmationDialog === dialog) {
                    deepLinks.cancel()
                    confirmationDialog = null
                }
            }
            .show()
    }

    /**
     * Автоподключение при запуске приложения (если включено в настройках):
     * только при наличии выбранного узла и уже выданном VPN-разрешении.
     */
    private fun maybeAutoConnectOnLaunch() {
        val prefs = Prefs(this)
        if (!prefs.connectOnLaunch) return
        val state = VpnController.state.value
        if (state.connected || state.connecting) return
        if (state.selectedNode == null) return
        val prepareIntent = runCatching { VpnService.prepare(this) }.getOrNull()
        if (prepareIntent == null) {
            VpnController.connect(this)
        }
    }

    private fun requestVpnPermission() {
        val intent = runCatching { VpnService.prepare(this) }.getOrNull()
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            VpnController.connect(this)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_AUTO_CONNECT = "app.rkn.vpn.extra.AUTO_CONNECT"
    }
}
