package app.rkn.vpn

import android.Manifest
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
import app.rkn.vpn.data.Prefs
import app.rkn.vpn.ui.AppRoot
import app.rkn.vpn.vpn.VpnController
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : BaseActivity() {

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
        setContent {
            AppRoot(onRequestConnect = { requestVpnPermission() })
        }
        observeFirstConnection()
        if (savedInstanceState == null) handleIntent(intent)
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
     */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (data != null) {
            if (handleDeepLink(data)) {
                VpnController.showError(getString(R.string.deep_link_applied))
            } else {
                VpnController.showError(getString(R.string.error_deeplink_unknown))
            }
            intent?.data = null
        }

        val autoConnect = intent?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) ?: false
        if (!autoConnect) return
        intent?.removeExtra(EXTRA_AUTO_CONNECT)
        val state = VpnController.state.value
        if (state.connected || state.connecting || state.nodes.isEmpty()) return
        requestVpnPermission()
    }

    /**
     * Возвращает true, если ссылка распознана. Значение может быть адресом
     * подписки (http/https) или готовой share-ссылкой/базой узлов — тогда
     * используем тот же импорт, что и кнопка «Добавить».
     */
    private fun handleDeepLink(data: Uri): Boolean {
        val scheme = data.scheme?.lowercase(Locale.ROOT).orEmpty()
        val shareSchemes = setOf(
            "vless", "vmess", "trojan", "ss", "ssr", "hysteria2", "hy2",
            "tuic", "anytls", "wireguard"
        )
        val raw = when (scheme) {
            in shareSchemes -> data.toString()
            "clash" -> data.getQueryParameter("url")
                ?: data.getQueryParameter("config")
            "happ" -> data.getQueryParameter("url")
                ?: data.getQueryParameter("add")
                ?: data.path?.removePrefix("/")?.removePrefix("add/")?.let { Uri.decode(it) }
            "rknvpn" -> data.getQueryParameter("url")
                ?: data.getQueryParameter("text")
                ?: data.path?.removePrefix("/")?.removePrefix("add/")?.let { Uri.decode(it) }
            else -> null
        } ?: return false

        val value = raw.trim()
        if (value.isEmpty()) return false
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            VpnController.setSubscriptionUrl(value)
            VpnController.refreshSubscription()
            return true
        }
        return VpnController.importText(value)
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
