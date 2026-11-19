package app.rkn.vpn.vpn

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.rkn.vpn.MainActivity
import app.rkn.vpn.R
import app.rkn.vpn.data.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Плитка в панели быстрых настроек («шторке»): подключение и отключение VPN
 * одним нажатием. Состояние плитки повторяет состояние приложения.
 */
class RknTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var listenJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        listenJob?.cancel()
        listenJob = s.launch {
            VpnController.state.collectLatest { render(it) }
        }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val state = VpnController.state.value
        when {
            state.connected || state.connecting -> {
                VpnController.disconnect(this)
                render(VpnController.state.value)
            }

            // Без узлов подключаться некуда — открываем приложение.
            state.nodes.isEmpty() -> openApp(autoConnect = false)

            // Разрешение VPN выдаёт только системный диалог, а он открывается
            // из активности: из плитки его напрямую не показать.
            runCatching { VpnService.prepare(this) }.getOrNull() != null ->
                openApp(autoConnect = true)

            else -> {
                VpnController.connect(this)
                // На части прошивок запуск foreground-сервиса из плитки
                // запрещён — тогда открываем приложение и подключаемся оттуда.
                if (VpnController.state.value.error != null) openApp(autoConnect = true)
                render(VpnController.state.value)
            }
        }
    }

    private fun render(state: AppState) {
        val tile = qsTile ?: return
        val node = state.nodes.firstOrNull { it.id == state.selectedId }
        tile.state = if (state.connected || state.connecting) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_shield)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.label = getString(R.string.app_name)
            tile.subtitle = when {
                state.connected -> node?.name?.let { name ->
                    // SystemUI сам переносит длинные подписи плитки, иногда по буквам.
                    // Ограничиваем имя узла, чтобы подпись оставалась компактной.
                    val shortName = if (name.length > 18) name.take(17) + "…" else name
                    getString(R.string.tile_connected, shortName)
                } ?: getString(R.string.tile_connected_short)
                state.connecting -> getString(R.string.tile_connecting)
                state.error != null -> getString(R.string.tile_error)
                else -> getString(R.string.tile_disabled)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = when {
                state.connected -> getString(R.string.tile_state_connected)
                state.connecting -> getString(R.string.tile_state_connecting)
                else -> getString(R.string.tile_state_disabled)
            }
        }
        tile.updateTile()
    }

    private fun openApp(autoConnect: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_AUTO_CONNECT, autoConnect)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                if (autoConnect) 1 else 0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
