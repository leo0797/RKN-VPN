package app.rkn.vpn.vpn

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import app.rkn.vpn.R
import app.rkn.vpn.core.ConfigBuilder
import app.rkn.vpn.core.ConfigImporter
import app.rkn.vpn.core.CoreRunner
import app.rkn.vpn.core.LocalProxy
import app.rkn.vpn.core.MihomoApi
import app.rkn.vpn.core.NodeSelector
import app.rkn.vpn.core.Ping
import app.rkn.vpn.core.RoutingPresets
import app.rkn.vpn.core.Strings
import app.rkn.vpn.data.AppState
import app.rkn.vpn.data.Prefs
import app.rkn.vpn.data.RoutingRule
import app.rkn.vpn.data.SubscriptionInfo
import app.rkn.vpn.data.SubscriptionRepository
import app.rkn.vpn.data.nodesFromJson
import app.rkn.vpn.data.nodesToJson
import app.rkn.vpn.data.rulesFromJson
import app.rkn.vpn.data.rulesToJson
import app.rkn.vpn.data.subscriptionFromJson
import app.rkn.vpn.data.subscriptionToJson
import app.rkn.vpn.util.AppLanguage
import app.rkn.vpn.util.SystemKillSwitch
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Единая точка состояния приложения. Живёт в процессе приложения,
 * UI подписывается на StateFlow, сервис пишет сюда статус подключения.
 */
object VpnController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var prefs: Prefs? = null
    private var appContext: Context? = null
    private var pingJobRunning = false
    private var reconnectJob: Job? = null
    private var autoReconnectJob: Job? = null
    private var healthJob: Job? = null
    private var autoSwitchJob: Job? = null
    // Узлы, уже проверенные автопереключением в рамках текущей сессии.
    // Без этого мёртвые узлы переключались бы друг на друга по кругу.
    private val autoSwitchTriedIds = mutableSetOf<String>()
    private var wantConnected = false
    private var networkLost = false
    private var watchdogStarted = false
    private var apiPort: Int? = null
    private var apiSecret = ""
    private var proxyPort: Int? = null
    private var proxyUser = ""
    private var proxyPassword = ""

    private fun text(id: Int, vararg args: Any): String = appContext?.getString(id, *args) ?: ""

    /**
     * mixed-port активного ядра. Приложение исключено из своего TUN, поэтому
     * запросы к панели/GitHub идут напрямую; этот прокси — запасной путь,
     * когда сеть блокирует нужный домен.
     */
    internal fun localProxy(): LocalProxy? {
        val port = proxyPort ?: return null
        if (!_state.value.connected) return null
        return LocalProxy(port, proxyUser, proxyPassword)
    }

    fun init(context: Context) {
        if (prefs != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val p = Prefs(ctx)
        prefs = p
        wantConnected = p.vpnWanted
        val nodes = p.nodes
        val selected = p.selectedId?.takeIf { id -> nodes.any { it.id == id } }
            ?: nodes.firstOrNull()?.id
        _state.value = AppState(
            nodes = nodes,
            selectedId = selected,
            subscription = p.subscription,
            subscriptionUrl = p.subscriptionUrl,
            rules = p.rules,
            routingMode = p.routingMode,
            lanDirect = p.lanDirect,
            activePresets = p.activePresets,
            splitEnabled = p.splitEnabled,
            splitMode = p.splitMode,
            splitApps = p.splitApps,
            checkUrl = p.checkUrl,
            autoSwitch = p.autoSwitch,
            showKillSwitchStatus = p.showKillSwitchStatus,
            killSwitchStatus = SystemKillSwitch.status(ctx),
            theme = p.theme,
            language = p.language,
            corePresent = CoreRunner(ctx).isAvailable()
        )
        startNetworkWatchdog(ctx)
    }

    private fun requirePrefs(): Prefs = prefs ?: error("VpnController не инициализирован")

    /** Показывает сообщение из deep link через общий snackbar. */
    fun showError(message: String) {
        _state.update { it.copy(error = message) }
    }

    /** Сервис спрашивает перед START_STICKY-перезапуском с пустым intent. */
    fun isWanted(): Boolean = wantConnected

    // ------------------------------------------------------------ connection

    fun connect(context: Context? = appContext) {
        val ctx = context ?: return
        val s = _state.value
        if (s.nodes.isEmpty()) {
            _state.update { it.copy(error = text(R.string.error_no_subscription)) }
            return
        }
        if (!s.corePresent) {
            _state.update {
                it.copy(error = text(R.string.error_no_core))
            }
            return
        }
        wantConnected = true
        prefs?.vpnWanted = true
        autoSwitchTriedIds.clear()
        val intent = Intent(ctx, RknVpnService::class.java)
            .setAction(RknVpnService.ACTION_START)
        runCatching {
            ContextCompat.startForegroundService(ctx, intent)
            _state.update {
                it.copy(connecting = true, connected = false, reconnecting = false, error = null)
            }
        }.onFailure { e ->
            // Не оставляем «хочу подключения» после неудачного запуска, иначе
            // watchdog попытается включить VPN ещё раз без команды пользователя.
            wantConnected = false
            prefs?.vpnWanted = false
            _state.update { it.copy(error = text(R.string.error_start_vpn, e.message ?: "")) }
        }
    }

    fun disconnect(context: Context? = appContext) {
        val ctx = context ?: return
        wantConnected = false
        prefs?.vpnWanted = false
        reconnectJob?.cancel()
        autoReconnectJob?.cancel()
        healthJob?.cancel()
        autoSwitchJob?.cancel()
        autoSwitchTriedIds.clear()
        val intent = Intent(ctx, RknVpnService::class.java)
            .setAction(RknVpnService.ACTION_STOP)
        runCatching { ctx.startService(intent) }
        _state.update { it.copy(connecting = false, reconnecting = false, nodeReachable = null) }
    }

    fun onPermissionDenied() {
        wantConnected = false
        prefs?.vpnWanted = false
        _state.update { it.copy(error = text(R.string.error_vpn_permission)) }
    }

    /**
     * Настройки маршрутизации/split tunneling попадают в конфиг mihomo и TUN
     * только при старте сервиса, поэтому активное подключение нужно перезапустить.
     * Небольшая задержка склеивает серию изменений (например, отметки приложений).
     */
    private fun scheduleReconnect(delayMs: Long = 1_200L) {
        if (!_state.value.connected && !_state.value.connecting) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (_state.value.connected || _state.value.connecting) restartService()
        }
    }

    /**
     * Перезапуск уже работающего сервиса. Именно startService, а не
     * startForegroundService: на Android 12+ фоновый запуск FGS ограничен,
     * а для уже созданного сервиса повторный startService разрешён.
     */
    private fun restartService() {
        val ctx = appContext ?: return
        wantConnected = true
        prefs?.vpnWanted = true
        val intent = Intent(ctx, RknVpnService::class.java)
            .setAction(RknVpnService.ACTION_START)
        runCatching {
            ctx.startService(intent)
            _state.update {
                it.copy(connecting = true, connected = false, reconnecting = false, error = null)
            }
        }.onFailure { e ->
            _state.update { it.copy(error = text(R.string.error_restart_vpn, e.message ?: "")) }
        }
    }

    // ---------------------------------------------------------- service hooks

    fun serviceConnecting() {
        _state.update { it.copy(connecting = true, connected = false, error = null) }
        refreshTile()
    }

    fun serviceConnected(
        startedAt: Long,
        apiPort: Int,
        apiSecret: String,
        mixedPort: Int,
        proxyUser: String,
        proxyPassword: String
    ) {
        this.apiPort = apiPort
        this.apiSecret = apiSecret
        this.proxyPort = mixedPort
        this.proxyUser = proxyUser
        this.proxyPassword = proxyPassword
        autoSwitchJob?.cancel()
        _state.update {
            it.copy(
                connecting = false,
                connected = true,
                reconnecting = false,
                startedAt = startedAt,
                upload = 0L,
                download = 0L,
                upSpeed = 0L,
                downSpeed = 0L,
                healthLatencies = emptyMap(),
                healthAvailable = true,
                nodeReachable = null,
                error = null
            )
        }
        refreshTile()
        maybeShowKillSwitchReminder()
        checkSelectedNode()
    }

    /**
     * Старт hev и TUN ещё не означает, что узел жив. Проверяем выбранный
     * outbound через API ядра, чтобы не показывать «защищено» при мёртвом узле.
     */
    private fun checkSelectedNode() {
        healthJob?.cancel()
        healthJob = scope.launch {
            delay(1_500)
            val port = apiPort ?: return@launch
            val snapshot = _state.value
            val selected = snapshot.selectedId ?: snapshot.nodes.firstOrNull()?.id ?: return@launch
            val name = ConfigBuilder.proxyNames(snapshot.nodes, snapshot.selectedId)[selected]
                ?: return@launch
            val testUrl = prefs?.checkUrl ?: MihomoApi.DEFAULT_TEST_URL
            val latency = withContext(Dispatchers.IO) {
                MihomoApi.delay(port, apiSecret, name, 6_000, testUrl)
            }
            if (port != this@VpnController.apiPort) return@launch
            _state.update {
                it.copy(
                    nodeReachable = latency != null,
                    healthLatencies = if (latency != null) mapOf(selected to latency) else emptyMap(),
                    toast = if (latency == null) text(R.string.toast_node_unreachable) else it.toast
                )
            }
            if (latency == null) maybeAutoSwitchNode()
        }
    }

    /**
     * Выбранный узел не отвечает — пробуем до трёх запасных через тот же API
     * ядра. Каждый проверенный узел запоминается до явного отключения/смены
     * узла пользователем: иначе мёртвые узлы переключались бы друг на друга
     * по кругу при каждом перезапуске сервиса.
     */
    private fun maybeAutoSwitchNode() {
        if (!_state.value.autoSwitch) return
        if (!wantConnected || !_state.value.connected) return
        val snapshot = _state.value
        val selected = snapshot.selectedId ?: return
        autoSwitchTriedIds.add(selected)
        val candidates = NodeSelector.fallbackCandidates(
            snapshot.nodes,
            selected,
            snapshot.effectiveLatencies
        ).filterNot { it.id in autoSwitchTriedIds }
        if (candidates.isEmpty()) return
        autoSwitchJob?.cancel()
        autoSwitchJob = scope.launch {
            val port = apiPort ?: return@launch
            val names = ConfigBuilder.proxyNames(snapshot.nodes, selected)
            val testUrl = prefs?.checkUrl ?: MihomoApi.DEFAULT_TEST_URL
            for (node in candidates) {
                if (_state.value.selectedId != selected) return@launch
                autoSwitchTriedIds.add(node.id)
                val name = names[node.id] ?: continue
                val latency = withContext(Dispatchers.IO) {
                    MihomoApi.delay(port, apiSecret, name, 4_000, testUrl)
                }
                if (port != this@VpnController.apiPort || !wantConnected) return@launch
                if (_state.value.selectedId != selected) return@launch
                if (latency == null) continue
                requirePrefs().selectedId = node.id
                _state.update {
                    it.copy(
                        selectedId = node.id,
                        healthLatencies = mapOf(node.id to latency),
                        toast = text(R.string.toast_auto_switched, node.name)
                    )
                }
                scheduleReconnect(0L)
                return@launch
            }
        }
    }

    /** Кратковременный сбой ядра: TUN не закрываем, трафик не уходит напрямую. */
    fun serviceRecovering() {
        _state.update { it.copy(reconnecting = true, connected = true, nodeReachable = null) }
        refreshTile()
    }

    fun serviceRecovered(
        startedAt: Long,
        apiPort: Int,
        apiSecret: String,
        mixedPort: Int,
        proxyUser: String,
        proxyPassword: String
    ) {
        this.apiPort = apiPort
        this.apiSecret = apiSecret
        this.proxyPort = mixedPort
        this.proxyUser = proxyUser
        this.proxyPassword = proxyPassword
        autoSwitchJob?.cancel()
        _state.update {
            it.copy(reconnecting = false, connected = true, startedAt = startedAt, error = null)
        }
        refreshTile()
        checkSelectedNode()
    }

    /** Однократное вежливое напоминание после первого успешного подключения. */
    private fun maybeShowKillSwitchReminder() {
        val p = prefs ?: return
        if (p.killSwitchReminderShown) return
        p.killSwitchReminderShown = true
        _state.update { it.copy(toast = text(R.string.kill_switch_reminder)) }
    }

    fun serviceTraffic(upload: Long, download: Long, upSpeed: Long = 0L, downSpeed: Long = 0L) {
        _state.update {
            it.copy(upload = upload, download = download, upSpeed = upSpeed, downSpeed = downSpeed)
        }
    }

    fun serviceStopped() {
        apiPort = null
        apiSecret = ""
        proxyPort = null
        proxyUser = ""
        proxyPassword = ""
        autoSwitchTriedIds.clear()
        autoSwitchJob?.cancel()
        _state.update {
            it.copy(
                connected = false,
                connecting = false,
                reconnecting = false,
                nodeReachable = null,
                startedAt = 0L,
                upload = 0L,
                download = 0L,
                upSpeed = 0L,
                downSpeed = 0L,
                healthAvailable = false,
                killSwitchStatus = appContext?.let { SystemKillSwitch.status(it) }
                    ?: SystemKillSwitch.Status.UNKNOWN
            )
        }
        refreshTile()
    }

    /**
     * @param fatal false ��� �६����� ᡮ��, ����� TUN ��� ����� � ��
     * ��१���᪠����: � �⮬ ��砥 ����७�� ���짮��⥫� ��࠭塞.
     */
    fun serviceError(message: String, fatal: Boolean = true) {
        if (fatal) {
            wantConnected = false
            prefs?.vpnWanted = false
        }
        apiPort = null
        apiSecret = ""
        proxyPort = null
        proxyUser = ""
        proxyPassword = ""
        autoSwitchTriedIds.clear()
        autoSwitchJob?.cancel()
        _state.update {
            it.copy(
                connected = false,
                connecting = false,
                reconnecting = false,
                nodeReachable = null,
                error = message,
                startedAt = 0L,
                healthAvailable = false,
                killSwitchStatus = appContext?.let { ctx -> SystemKillSwitch.status(ctx) }
                    ?: SystemKillSwitch.Status.UNKNOWN
            )
        }
        refreshTile()
    }
    // ------------------------------------------------------------------ nodes

    fun selectNode(id: String) {
        val p = requirePrefs()
        p.selectedId = id
        autoSwitchTriedIds.clear()
        _state.update { it.copy(selectedId = id, error = null) }
        if (_state.value.connected) connect()
    }

    fun selectBestNode() {
        val s = _state.value
        if (s.nodes.isEmpty()) return
        val best = s.effectiveLatencies
            .filterValues { it != null }
            .minByOrNull { it.value ?: Int.MAX_VALUE }
            ?.key
        if (best != null) {
            selectNode(best)
            return
        }
        // Пинги ещё не собраны — измеряем и выбираем автоматически.
        scope.launch {
            pingAll()
            val winner = _state.value.effectiveLatencies
                .filterValues { it != null }
                .minByOrNull { it.value ?: Int.MAX_VALUE }
                ?.key
            if (winner != null) selectNode(winner)
        }
    }

    fun toggleLatencyTest() {
        if (pingJobRunning) return
        scope.launch { pingAll() }
    }

    suspend fun pingAll() {
        val nodes = _state.value.nodes
        if (nodes.isEmpty()) return
        pingJobRunning = true
        try {
            val results = withContext(Dispatchers.IO) {
                coroutineScope {
                    nodes.map { node ->
                        async { node.id to Ping.tcp(node.server, node.port) }
                    }.awaitAll()
                }
            }
            _state.update { it.copy(latencies = results.toMap()) }
        } finally {
            pingJobRunning = false
        }
    }

    /**
     * Проверка узлов через локальный API mihomo. В отличие от TCP-пинга, она
     * реально открывает соединение через outbound и работает с UDP-протоколами.
     * Доступна только при активном VPN: API живёт внутри запущенного ядра.
     */
    fun checkNodesHealth() {
        val port = apiPort
        val state = _state.value
        if (port == null || !state.connected || state.nodes.isEmpty()) {
            _state.update {
                it.copy(error = text(R.string.error_health_requires_vpn))
            }
            return
        }
        if (healthJob?.isActive == true) return
        val names = ConfigBuilder.proxyNames(state.nodes, state.selectedId)
        val testUrl = prefs?.checkUrl ?: MihomoApi.DEFAULT_TEST_URL
        _state.update { it.copy(healthChecking = true, error = null) }
        healthJob = scope.launch {
            val results = withContext(Dispatchers.IO) {
                coroutineScope {
                    state.nodes.map { node ->
                        async {
                            val name = names[node.id] ?: return@async node.id to null
                            node.id to MihomoApi.delay(port, apiSecret, name, testUrl = testUrl)
                        }
                    }.awaitAll()
                }
            }
            _state.update {
                it.copy(healthLatencies = results.toMap(), healthChecking = false)
            }
        }
    }

    // ----------------------------------------------------------- subscription

    fun setSubscriptionUrl(url: String) {
        val p = requirePrefs()
        p.subscriptionUrl = url
        _state.update { it.copy(subscriptionUrl = url) }
    }

    /** Адрес, который mihomo запрашивает через выбранный outbound. */
    fun setCheckUrl(url: String) {
        val p = requirePrefs()
        p.checkUrl = url
        _state.update { it.copy(checkUrl = p.checkUrl) }
    }

    /** Автоматический переход на другой узел, если выбранный не отвечает. */
    fun setAutoSwitch(enabled: Boolean) {
        val p = requirePrefs()
        p.autoSwitch = enabled
        _state.update { it.copy(autoSwitch = enabled) }
    }

    /** Карточка статуса kill switch на главном экране: скрыть или вернуть. */
    fun setKillSwitchCardVisible(visible: Boolean) {
        val p = requirePrefs()
        p.showKillSwitchStatus = visible
        _state.update { it.copy(showKillSwitchStatus = visible) }
    }

    fun reportKillSwitchStatus(status: SystemKillSwitch.Status) {
        _state.update { it.copy(killSwitchStatus = status) }
    }

    fun refreshKillSwitchStatus() {
        val ctx = appContext ?: return
        _state.update { it.copy(killSwitchStatus = SystemKillSwitch.status(ctx)) }
    }

    fun refreshSubscription(showToast: Boolean = true) {
        scope.launch { refreshSubscriptionInternal(showToast) }
    }

    suspend fun refreshSubscriptionAwait(): Boolean = refreshSubscriptionInternal(true)

    /** Фоновая проверка: не показывает тост и не портит UI-состояние ошибкой. */
    suspend fun refreshSubscriptionBackground(): Boolean =
        refreshSubscriptionInternal(showToast = false, silent = true)

    private suspend fun refreshSubscriptionInternal(
        showToast: Boolean,
        silent: Boolean = false
    ): Boolean {
        val p = requirePrefs()
        val url = p.subscriptionUrl
        if (url.isBlank()) {
            _state.update { it.copy(error = text(R.string.error_subscription_url_required)) }
            return false
        }
        _state.update { it.copy(refreshing = true, error = null) }
        val proxy = localProxy()
        val strings = Strings { id, args -> text(id, *args) }
        return withContext(Dispatchers.IO) {
            runCatching { SubscriptionRepository(p, strings).fetch(url, proxy) }
                .fold(
                    onSuccess = { result ->
                        val oldSelected = p.selectedId
                        p.nodes = result.nodes
                        val selected = result.nodes.firstOrNull { it.id == oldSelected }?.id
                            ?: result.nodes.first().id
                        p.selectedId = selected
                        autoSwitchTriedIds.clear()
                        p.subscription = result.info
                        p.updateIntervalHours = result.info.updateIntervalHours
                        p.saveLastRefresh()
                        _state.update {
                            it.copy(
                                nodes = result.nodes,
                                selectedId = selected,
                                subscription = result.info,
                                refreshing = false,
                                latencies = emptyMap(),
                                healthLatencies = emptyMap(),
                                toast = if (showToast) {
                                    text(R.string.toast_subscription_updated, result.nodes.size)
                                } else null
                            )
                        }
                        // Если VPN уже поднят, узлы/названия в конфиге устарели.
                        scheduleReconnect(0L)
                        pingAll()
                        true
                    },
                    onFailure = { e ->
                        _state.update {
                            it.copy(
                                refreshing = false,
                                error = if (silent) {
                                    null
                                } else {
                                    e.message ?: text(R.string.error_refresh_failed)
                                }
                            )
                        }
                        false
                    }
                )
        }
    }

    fun maybeAutoRefresh() {
        val p = prefs ?: return
        if (p.subscriptionUrl.isBlank()) return
        if (p.nodes.isEmpty()) {
            refreshSubscription(showToast = false)
            return
        }
        val intervalMs = p.updateIntervalHours.coerceAtLeast(1) * 3_600_000L
        if (System.currentTimeMillis() - p.lastRefresh > intervalMs) {
            refreshSubscription(showToast = false)
        }
    }

    // ----------------------------------------------------------- импорт узлов

    /**
     * Универсальный импорт: Clash/Mihomo YAML, SIP008 JSON, base64 или plain
     * список share-ссылок. Результат заменяет текущие узлы — так проще избежать
     * дублей и непонятных запутанных списков.
     */
    fun importText(text: String): Boolean {
        if (text.isBlank()) {
            _state.update { it.copy(error = text(R.string.error_import_empty)) }
            return false
        }
        val imported = ConfigImporter.import(text)
        if (imported.isEmpty()) {
            _state.update {
                it.copy(error = text(R.string.error_import_unknown))
            }
            return false
        }
        val p = requirePrefs()
        p.nodes = imported
        p.selectedId = imported.first().id
        // Статистика подписки больше не относится к ручному списку узлов.
        p.subscriptionUrl = ""
        p.subscription = SubscriptionInfo()
        _state.update {
            it.copy(
                nodes = imported,
                selectedId = imported.first().id,
                subscriptionUrl = "",
                subscription = SubscriptionInfo(),
                latencies = emptyMap(),
                healthLatencies = emptyMap(),
                error = null,
                toast = text(R.string.toast_imported_nodes, imported.size)
            )
        }
        scheduleReconnect(0L)
        return true
    }

    // ------------------------------------------------------- резервная копия

    /** Экспорт настроек в текст. HWID не попадает в копию: он привязан к устройству. */
    fun exportSettings(): String {
        val p = requirePrefs()
        return JSONObject()
            .put("format", "rkn-vpn-backup")
            .put("version", 1)
            .put("subscriptionUrl", p.subscriptionUrl)
            .put("subscription", subscriptionToJson(p.subscription))
            .put("nodes", nodesToJson(p.nodes))
            .put("selectedId", p.selectedId ?: JSONObject.NULL)
            .put("rules", rulesToJson(p.rules))
            .put("routingMode", p.routingMode)
            .put("lanDirect", p.lanDirect)
            .put("activePresets", JSONArray(p.activePresets))
            .put("splitEnabled", p.splitEnabled)
            .put("splitMode", p.splitMode)
            .put("splitApps", JSONArray(p.splitApps))
            .put("theme", p.theme)
            .put("language", p.language)
            .put("updateIntervalHours", p.updateIntervalHours)
            .put("checkUrl", p.checkUrl)
            .put("autoSwitch", p.autoSwitch)
            .toString(2)
    }

    fun importSettings(json: String): Boolean {
        val obj = runCatching { JSONObject(json) }.getOrNull()
        if (obj == null || obj.optString("format") != "rkn-vpn-backup") {
            _state.update { it.copy(error = text(R.string.error_not_backup)) }
            return false
        }
        val p = requirePrefs()
        val restored = runCatching {
            p.subscriptionUrl = obj.optString("subscriptionUrl")
            p.subscription = subscriptionFromJson(obj.optString("subscription"))
            p.nodes = nodesFromJson(obj.optString("nodes"))
            p.selectedId = obj.optString("selectedId").takeIf { it.isNotBlank() }
            p.rules = rulesFromJson(obj.optString("rules"))
            p.routingMode = obj.optString("routingMode", "rules")
            p.lanDirect = obj.optBoolean("lanDirect", true)
            val presets = JSONArray(obj.optString("activePresets", "[]"))
            p.activePresets = RoutingPresets.knownIds(
                (0 until presets.length()).map { presets.optString(it) }
            )
            p.splitEnabled = obj.optBoolean("splitEnabled", false)
            p.splitMode = obj.optString("splitMode", "include")
            val apps = JSONArray(obj.optString("splitApps", "[]"))
            p.splitApps = (0 until apps.length()).mapTo(HashSet()) { apps.optString(it) }
            p.theme = obj.optString("theme", "system")
            p.language = obj.optString("language", "system")
            p.updateIntervalHours = obj.optInt("updateIntervalHours", 12).coerceIn(1, 168)
            p.checkUrl = obj.optString("checkUrl", Prefs.DEFAULT_CHECK_URL)
            p.autoSwitch = if (obj.has("autoSwitch")) {
                obj.optBoolean("autoSwitch", true)
            } else true
        }
        if (restored.isFailure) {
            _state.update {
                it.copy(error = text(R.string.error_restore_failed, restored.exceptionOrNull()?.message ?: ""))
            }
            return false
        }
        val nodes = p.nodes
        val selected = p.selectedId?.takeIf { id -> nodes.any { it.id == id } }
            ?: nodes.firstOrNull()?.id
        p.selectedId = selected
        autoSwitchTriedIds.clear()
        _state.update {
            it.copy(
                nodes = nodes,
                selectedId = selected,
                subscriptionUrl = p.subscriptionUrl,
                subscription = p.subscription,
                rules = p.rules,
                routingMode = p.routingMode,
                lanDirect = p.lanDirect,
                activePresets = p.activePresets,
                splitEnabled = p.splitEnabled,
                splitMode = p.splitMode,
                splitApps = p.splitApps,
                checkUrl = p.checkUrl,
                autoSwitch = p.autoSwitch,
                theme = p.theme,
                language = p.language,
                latencies = emptyMap(),
                healthLatencies = emptyMap(),
                error = null,
                toast = text(R.string.toast_settings_restored)
            )
        }
        appContext?.let { AppLanguage.apply(it, p.language) }
        scheduleReconnect(0L)
        return true
    }

    // ---------------------------------------------------------------- routing

    fun saveRules(rules: List<RoutingRule>) {
        val p = requirePrefs()
        p.rules = rules
        _state.update { it.copy(rules = rules) }
        scheduleReconnect()
    }

    fun addRule(rule: RoutingRule) = saveRules(_state.value.rules + rule)

    fun deleteRule(id: String) = saveRules(_state.value.rules.filterNot { it.id == id })

    fun setRoutingMode(mode: String) {
        val p = requirePrefs()
        p.routingMode = mode
        _state.update { it.copy(routingMode = mode) }
        scheduleReconnect()
    }

    fun setLanDirect(enabled: Boolean) {
        val p = requirePrefs()
        p.lanDirect = enabled
        _state.update { it.copy(lanDirect = enabled) }
        scheduleReconnect()
    }

    /** Включить/выключить готовый набор правил. */
    fun setPreset(id: String, enabled: Boolean) {
        if (RoutingPresets.byId(id) == null) return
        val p = requirePrefs()
        p.activePresets = if (enabled) p.activePresets + id else p.activePresets - id
        _state.update { it.copy(activePresets = p.activePresets) }
        scheduleReconnect()
    }

    fun setSplitEnabled(enabled: Boolean) {
        val p = requirePrefs()
        p.splitEnabled = enabled
        _state.update { it.copy(splitEnabled = enabled) }
        scheduleReconnect(0L)
    }

    fun setSplitMode(mode: String) {
        val p = requirePrefs()
        p.splitMode = mode
        _state.update { it.copy(splitMode = p.splitMode) }
        scheduleReconnect(0L)
    }

    fun setSplitApps(apps: Set<String>) {
        val p = requirePrefs()
        p.splitApps = apps
        _state.update { it.copy(splitApps = apps) }
        // Не перезапускаем VPN на каждую галочку — ждём паузу в выборе.
        scheduleReconnect()
    }

    // --------------------------------------------------------------- settings

    fun setTheme(theme: String) {
        val p = requirePrefs()
        p.theme = theme
        _state.update { it.copy(theme = theme) }
    }

    fun setLanguage(language: String) {
        val p = requirePrefs()
        p.language = language
        appContext?.let { AppLanguage.apply(it, language) }
        _state.update { it.copy(language = language) }
    }

    fun readLogs(): String {
        val ctx = appContext ?: return ""
        return CoreRunner(ctx).logTail(200)
    }

    fun clearLogs() {
        val ctx = appContext ?: return
        CoreRunner(ctx).clearLog()
    }

    // --------------------------------------------------- сеть и спящий режим

    /**
     * Возвращает VPN после обрыва сети или выхода из Doze, только если его
     * включал пользователь. Ручной «Отключить» сбрасывает это намерение,
     * поэтому watchdog не будет включать трафик против воли пользователя.
     */
    private fun startNetworkWatchdog(context: Context) {
        if (watchdogStarted) return
        watchdogStarted = true
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                networkLost = true
            }

            override fun onAvailable(network: Network) {
                if (!networkLost) return
                networkLost = false
                maybeAutoReconnect()
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }

        val power = context.getSystemService(PowerManager::class.java)
        val idleReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (power?.isDeviceIdleMode == false) maybeAutoReconnect()
            }
        }
        runCatching {
            context.registerReceiver(
                idleReceiver,
                IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            )
        }
    }

    private fun maybeAutoReconnect() {
        if (!wantConnected) return
        val s = _state.value
        if (s.connected || s.connecting) return
        autoReconnectJob?.cancel()
        autoReconnectJob = scope.launch {
            delay(1_500)
            val current = _state.value
            if (!wantConnected || current.connected || current.connecting) return@launch
            _state.update { it.copy(toast = text(R.string.toast_network_restored)) }
            connect()
        }
    }

    // ------------------------------------------------------------------ misc

    /**
     * Просит систему перерисовать плитку в «шторке». Вызывается при смене
     * состояния, чтобы плитка не показывала устаревший статус, когда
     * панель быстрых настроек закрыта (сервис плитки в этот момент спит).
     */
    private fun refreshTile() {
        val ctx = appContext ?: return
        runCatching {
            TileService.requestListeningState(ctx, ComponentName(ctx, RknTileService::class.java))
        }
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
