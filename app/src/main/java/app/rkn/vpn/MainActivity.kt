package app.rkn.vpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.rkn.vpn.core.ApkInstaller
import app.rkn.vpn.ui.AppRoot
import app.rkn.vpn.vpn.VpnController

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
        requestNotificationPermission()
        setContent {
            AppRoot(onRequestConnect = { requestVpnPermission() })
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Установщик уже закрыт (или пользователь отказался) — кэш APK больше не нужен.
        ApkInstaller.cleanup(this)
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
        val autoConnect = intent?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) ?: false
        if (!autoConnect) return
        intent?.removeExtra(EXTRA_AUTO_CONNECT)
        val state = VpnController.state.value
        if (state.connected || state.connecting || state.nodes.isEmpty()) return
        requestVpnPermission()
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
