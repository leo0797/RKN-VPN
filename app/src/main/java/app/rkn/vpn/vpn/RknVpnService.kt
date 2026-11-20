package app.rkn.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import app.rkn.vpn.MainActivity
import app.rkn.vpn.R
import app.rkn.vpn.core.ConfigBuilder
import app.rkn.vpn.core.CoreRunner
import app.rkn.vpn.core.HevTunnel
import app.rkn.vpn.data.Prefs
import app.rkn.vpn.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.SecureRandom

/**
 * VPN-сервис: поднимает tun, запускает mihomo (SOCKS5 на loopback)
 * и tun2socks hev-socks5-tunnel между ними.
 */
class RknVpnService : VpnService() {

    companion object {
        const val ACTION_START = "app.rkn.vpn.action.START"
        const val ACTION_STOP = "app.rkn.vpn.action.STOP"

        private const val CHANNEL_ID = "vpn_status"
        private const val WARNING_CHANNEL_ID = "vpn_warnings"
        private const val ERROR_CHANNEL_ID = "vpn_errors"
        private const val NOTIFICATION_ID = 1001
        private const val WARNING_NOTIFICATION_ID = 1002
        private const val ERROR_NOTIFICATION_ID = 1003

        private const val HEV_CONFIG_NAME = "hev-socks5-tunnel.conf"
        private const val VPN_IPV4 = "198.18.0.1"
        private const val VPN_IPV6 = "fc00::1"
        private const val MAPPED_DNS = "198.18.0.2"
        private const val MTU = 8500
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private var tun: ParcelFileDescriptor? = null
    private var core: CoreRunner? = null
    private var lifecycleJob: Job? = null
    private var statsJob: Job? = null
    private var recoveryJob: Job? = null
    private var warningSent = false
    @Volatile private var recovering = false

    /**
     * Всё необходимое для перезапуска ядра без закрытия TUN. Конфиг ядра
     * удаляется на диске при stop(), поэтому храним его в памяти сессии.
     */
    private data class Session(
        val config: String,
        val credentials: Credentials,
        val mixedPort: Int,
        val apiPort: Int,
        val nodeName: String,
        var startedAt: Long
    )

    private var session: Session? = null

    /**
     * Номер последней запрошенной операции start/stop. Нужен, чтобы
     * запоздавший stop не «погасил» только что запущенный VPN.
     */
    private var generation = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn(startId)
            return START_NOT_STICKY
        }
        // Система может доставить START_STICKY с пустым intent после смерти процесса.
        // Включаемся только если пользователь действительно хотел VPN.
        if (intent == null && !VpnController.isWanted()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    override fun onRevoke() {
        // Пользователь (или другой VPN) отозвал разрешение — не пытаемся вернуть
        // соединение watchdog'ом после смены сети.
        VpnController.onPermissionDenied()
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        lifecycleJob?.cancel()
        lifecycleJob = null
        runCatching { stopResources() }
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ start

    private fun startVpn() {
        // Старт и стоп сериализуются: новый старт сначала дожидается завершения
        // предыдущей операции, иначе два потока могут одновременно поднимать
        // tun/mihomo и оставлять «висящие» процессы и файловые дескрипторы.
        val gen = ++generation
        val previous = lifecycleJob
        previous?.cancel()
        startForegroundCompat(buildNotification(null, null))
        warningSent = false
        VpnController.serviceConnecting()

        lifecycleJob = scope.launch {
            previous?.join()
            lifecycleMutex.withLock {
                if (!isActive || gen != generation) return@withLock
                try {
                    stopResources()

                    val prefs = Prefs(this@RknVpnService)
                    val node = prefs.selectedNode()
                        ?: error(getString(R.string.error_no_server_selected))
                    val credentials = Credentials.random()
                    val ports = freePorts(2)
                    val mixedPort = ports[0]
                    val apiPort = ports[1]

                    val config = ConfigBuilder.build(
                        ConfigBuilder.Params(
                            mixedPort = mixedPort,
                            apiPort = apiPort,
                            socksUser = credentials.user,
                            socksPassword = credentials.password,
                            apiSecret = credentials.secret,
                            node = node,
                            allNodes = prefs.nodes,
                            rules = prefs.rules,
                            routingMode = prefs.routingMode,
                            lanDirect = prefs.lanDirect,
                            presets = prefs.activePresets
                        )
                    )

                    val current = Session(
                        config = config,
                        credentials = credentials,
                        mixedPort = mixedPort,
                        apiPort = apiPort,
                        nodeName = node.name,
                        startedAt = 0L
                    )
                    session = current

                    val runner = CoreRunner(this@RknVpnService)
                    core = runner
                    runner.start(config, mixedPort)
                    ensureActive()

                    val builder = Builder()
                        .setSession("RKN VPN")
                        .setMtu(MTU)
                        .addAddress(VPN_IPV4, 32)
                        .addRoute("0.0.0.0", 0)
                        .addAddress(VPN_IPV6, 128)
                        .addRoute("::", 0)
                        .addDnsServer(MAPPED_DNS)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // VPN — платное соединение: системы и приложения не должны
                        // тянуть фоновые обновления, считая его безлимитным Wi-Fi.
                        builder.setMetered(true)
                    }
                    builder.setUnderlyingNetworks(null)

                    val splitApps = if (prefs.splitEnabled) prefs.splitApps else emptySet()
                    if (prefs.splitEnabled && prefs.splitMode == "exclude") {
                        // «Всё через VPN, кроме выбранных». Нельзя смешивать
                        // allowlist и disallowlist в одном VpnService.Builder —
                        // поэтому здесь только исключения.
                        runCatching { builder.addDisallowedApplication(packageName) }
                        splitApps
                            .filter { it != packageName }
                            .forEach { pkg ->
                                runCatching { builder.addDisallowedApplication(pkg) }
                            }
                    } else {
                        var allowedCount = 0
                        splitApps.forEach { pkg ->
                            // Себя в allowlist не добавляем: иначе трафик ядра и
                            // UI попадёт в собственный TUN.
                            if (pkg == packageName) return@forEach
                            if (runCatching { builder.addAllowedApplication(pkg) }.isSuccess) {
                                allowedCount++
                            }
                        }
                        // Если ни одно приложение из списка не установлено, пустой
                        // allowlist означал бы «весь трафик через VPN» и завернул бы
                        // в туннель сам клиент (петля). В этом случае исключаем себя.
                        if (allowedCount == 0) {
                            runCatching { builder.addDisallowedApplication(packageName) }
                        }
                    }

                    val fd = builder.establish() ?: error(getString(R.string.error_no_vpn_interface))
                    tun = fd
                    ensureActive()

                    val hevConfig = File(cacheDir, HEV_CONFIG_NAME).apply {
                        writeText(
                            HevTunnel.buildConfig(mixedPort, credentials.user, credentials.password),
                            Charsets.UTF_8
                        )
                    }
                    if (!HevTunnel.start(hevConfig.absolutePath, fd.fd)) {
                        error(getString(R.string.error_tun2socks))
                    }
                    ensureActive()

                    val startedAt = System.currentTimeMillis()
                    current.startedAt = startedAt
                    VpnController.serviceConnected(
                        startedAt, apiPort, credentials.secret,
                        mixedPort, credentials.user, credentials.password
                    )
                    startForegroundCompat(buildNotification(node.name, startedAt))
                    startStats()
                } catch (t: Throwable) {
                    stopResources()
                    if (t is CancellationException) return@withLock
                    val message = t.message ?: getString(R.string.error_unknown_connection)
                    VpnController.serviceError(message)
                    showErrorNotification(message)
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    // ------------------------------------------------------------------- stop

    private fun stopVpn(startId: Int = 0) {
        val gen = ++generation
        val previous = lifecycleJob
        previous?.cancel()
        lifecycleJob = scope.launch {
            previous?.join()
            lifecycleMutex.withLock { stopResources() }
            // Пока останавливались, пользователь мог снова включить VPN:
            // в этом случае не сбрасываем состояние нового подключения.
            if (gen != generation) return@launch
            VpnController.serviceStopped()
            stopForegroundCompat()
            // stopSelfResult не остановит сервис, если после ACTION_STOP
            // успела прийти более новая команда запуска.
            if (startId != 0) stopSelfResult(startId) else stopSelf()
        }
    }

    private fun stopResources() {
        statsJob?.cancel()
        statsJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        recovering = false
        runCatching { HevTunnel.stop() }
        runCatching { tun?.close() }
        tun = null
        runCatching { core?.stop() }
        core = null
        // В hev-конфиге лежит SOCKS-логин и пароль сессии.
        runCatching { File(cacheDir, HEV_CONFIG_NAME).delete() }
        session = null
    }

    // ------------------------------------------------------------------ stats

    private fun startStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            // Первый замер берём сразу: иначе на первом тике скорость считалась
            // бы от нуля и показывала весь уже прокачанный объём за секунду.
            val initial = HevTunnel.stats()
            var lastUp = if (initial.size >= 4) initial[1] else 0L
            var lastDown = if (initial.size >= 4) initial[3] else 0L
            var lastAt = System.currentTimeMillis()
            while (isActive) {
                delay(1000)
                // Ядро или tun2socks могут умереть (нехватка памяти, убийство
                // процесса системой). Без этой проверки UI показывал бы
                // «подключено», хотя трафик уже никуда не идёт.
                if (core?.isRunning() != true || !HevTunnel.isRunning()) {
                    // TUN остаётся открытым: пакеты теряются, но не идут напрямую.
                    scheduleRecovery()
                    return@launch
                }
                val stats = HevTunnel.stats()
                if (stats.size < 4) continue
                val up = stats[1]
                val down = stats[3]
                val now = System.currentTimeMillis()
                val dt = (now - lastAt).coerceAtLeast(1)
                val upSpeed = ((up - lastUp).coerceAtLeast(0) * 1000L / dt)
                val downSpeed = ((down - lastDown).coerceAtLeast(0) * 1000L / dt)
                lastUp = up
                lastDown = down
                lastAt = now
                VpnController.serviceTraffic(up, down, upSpeed, downSpeed)
                checkSubscriptionWarnings()
            }
        }
    }

    // --------------------------------------------------------------- recovery

    /**
     * Ядро или tun2socks умерли. TUN не закрываем: пока идёт перезапуск, пакеты
     * теряются, но не уходят в обход VPN (мягкий kill switch). После 5 неудачных
     * попыток с backoff поднимаем обычную ошибку и закрываем соединение.
     */
    private fun scheduleRecovery() {
        if (recovering) return
        val current = session
        val fd = tun
        if (current == null || fd == null) {
            fatalCoreLoss()
            return
        }
        recovering = true
        VpnController.serviceRecovering()
        startForegroundCompat(buildNotification(current.nodeName, null, reconnecting = true))
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            for (waitSeconds in longArrayOf(1, 2, 4, 8, 16)) {
                if (!isActive) return@launch
                delay(waitSeconds * 1_000L)
                if (!isActive || session !== current || tun !== fd) return@launch
                if (runCatching { restartCore(current, fd) }.getOrDefault(false)) {
                    recovering = false
                    VpnController.serviceRecovered(
                        System.currentTimeMillis(), current.apiPort, current.credentials.secret,
                        current.mixedPort, current.credentials.user, current.credentials.password
                    )
                    startForegroundCompat(buildNotification(current.nodeName, current.startedAt))
                    startStats()
                    return@launch
                }
            }
            recovering = false
            fatalCoreLoss()
        }
    }

    /** Перезапуск mihomo и hev на том же TUN-дескрипторе. */
    private fun restartCore(current: Session, fd: ParcelFileDescriptor): Boolean {
        runCatching { HevTunnel.stop() }
        runCatching { core?.stop() }
        core = null
        val runner = CoreRunner(this)
        runner.start(current.config, current.mixedPort)
        core = runner
        val hevConfig = File(cacheDir, HEV_CONFIG_NAME).apply {
            writeText(
                HevTunnel.buildConfig(
                    current.mixedPort, current.credentials.user, current.credentials.password
                ),
                Charsets.UTF_8
            )
        }
        if (HevTunnel.start(hevConfig.absolutePath, fd.fd)) return true
        runCatching { runner.stop() }
        core = null
        return false
    }

    private fun fatalCoreLoss() {
        val message = getString(R.string.error_core_lost)
        VpnController.serviceError(message)
        showErrorNotification(message)
        stopVpn()
    }

    // ----------------------------------------------------------- notification

    /**
     * Одноразовое предупреждение о трафике/сроке подписки. Отправляется только
     * во время активного подключения, чтобы не создавать фоновую активность.
     */
    private fun checkSubscriptionWarnings() {
        if (warningSent) return
        val sub = VpnController.state.value.subscription
        val traffic = if (sub.hasTraffic && sub.total > 0 &&
            sub.remaining.toDouble() / sub.total <= 0.1
        ) {
            getString(R.string.warning_low_traffic, 10)
        } else null
        val days = if (sub.hasExpire) Format.daysLeft(sub.expire) else Long.MAX_VALUE
        val expiry = if (days <= 3) getString(R.string.warning_expiry, days) else null
        val message = listOfNotNull(traffic, expiry).joinToString(". ")
        if (message.isBlank()) return
        warningSent = true
        showWarning(message)
    }

    private fun showWarning(message: String) {
        ensureWarningChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_subscription_title))
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(WARNING_NOTIFICATION_ID, notification)
        }
    }

    private fun ensureWarningChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(WARNING_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        WARNING_CHANNEL_ID,
                        getString(R.string.warning_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = getString(R.string.warning_channel_description) }
                )
            }
        }
    }

    private fun showErrorNotification(message: String) {
        ensureErrorChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_error_title))
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(ERROR_NOTIFICATION_ID, notification)
        }
    }

    private fun ensureErrorChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(ERROR_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        ERROR_CHANNEL_ID,
                        getString(R.string.error_channel_name),
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply { description = getString(R.string.error_channel_description) }
                )
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(
        nodeName: String?,
        startedAt: Long?,
        reconnecting: Boolean = false
    ): Notification {
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RknVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when {
            reconnecting -> getString(R.string.notification_reconnecting, nodeName.orEmpty())
            nodeName == null -> getString(R.string.notification_connecting)
            startedAt != null -> getString(R.string.notification_connected, nodeName)
            else -> nodeName
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("RKN VPN")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_notification, getString(R.string.disconnect), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.channel_description)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    // ------------------------------------------------------------------ utils

    /**
     * Держит сокеты открытыми, пока не выбраны все порты: иначе два вызова
     * freePort() подряд могут вернуть один и тот же номер.
     */
    private fun freePorts(count: Int): IntArray = runCatching {
        val sockets = ArrayList<java.net.ServerSocket>(count)
        try {
            repeat(count) {
                sockets.add(
                    java.net.ServerSocket(
                        0,
                        1,
                        java.net.InetAddress.getByName("127.0.0.1")
                    )
                )
            }
            sockets.map { it.localPort }.toIntArray()
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }.getOrElse {
        (20_000..45_000).shuffled().take(count).toIntArray()
    }

    private data class Credentials(
        val user: String,
        val password: String,
        val secret: String
    ) {
        companion object {
            private const val ALPHABET =
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

            fun random(): Credentials {
                val rnd = SecureRandom()
                fun token(length: Int) = buildString {
                    repeat(length) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) }
                }
                return Credentials(token(12), token(24), token(32))
            }
        }
    }
}
