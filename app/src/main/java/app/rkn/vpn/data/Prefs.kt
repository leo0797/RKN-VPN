package app.rkn.vpn.data

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * Локальное хранилище настроек. Сознательно без DataStore/Room:
 * приложению достаточно SharedPreferences, а зависимостей меньше.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ---------- подписка ----------

    var subscriptionUrl: String
        get() = sp.getString(KEY_SUB_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_SUB_URL, value.trim()).apply()

    var subscription: SubscriptionInfo
        get() = subscriptionFromJson(sp.getString(KEY_SUB_INFO, null))
        set(value) = sp.edit().putString(KEY_SUB_INFO, subscriptionToJson(value)).apply()

    var nodes: List<ProxyNode>
        get() = nodesFromJson(sp.getString(KEY_NODES, null))
        set(value) = sp.edit().putString(KEY_NODES, nodesToJson(value)).apply()

    var selectedId: String?
        get() = sp.getString(KEY_SELECTED, null)
        set(value) = sp.edit().putString(KEY_SELECTED, value).apply()

    fun selectedNode(): ProxyNode? {
        val id = selectedId ?: return nodes.firstOrNull()
        return nodes.firstOrNull { it.id == id } ?: nodes.firstOrNull()
    }

    // ---------- маршрутизация ----------

    var rules: List<RoutingRule>
        get() = rulesFromJson(sp.getString(KEY_RULES, null))
        set(value) = sp.edit().putString(KEY_RULES, rulesToJson(value)).apply()

    /** "rules" — по правилам, "global" — весь трафик через VPN. */
    var routingMode: String
        get() = sp.getString(KEY_MODE, "rules") ?: "rules"
        set(value) = sp.edit().putString(KEY_MODE, value).apply()

    var lanDirect: Boolean
        get() = sp.getBoolean(KEY_LAN_DIRECT, true)
        set(value) = sp.edit().putBoolean(KEY_LAN_DIRECT, value).apply()

    // ---------- split tunneling ----------

    var splitEnabled: Boolean
        get() = sp.getBoolean(KEY_SPLIT_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_SPLIT_ENABLED, value).apply()

    var splitApps: Set<String>
        // SharedPreferences возвращает внутренний Set, который нельзя менять;
        // UI строит новые наборы через +/-, поэтому отдаём и храним копию.
        get() = sp.getStringSet(KEY_SPLIT_APPS, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_SPLIT_APPS, value.toSet()).apply()

    // ---------- прочее ----------

    var theme: String
        get() = sp.getString(KEY_THEME, "system") ?: "system"
        set(value) = sp.edit().putString(KEY_THEME, value).apply()

    /** "system" — язык системы, иначе "ru" или "en". */
    var language: String
        get() = sp.getString(KEY_LANGUAGE, "system") ?: "system"
        set(value) = sp.edit().putString(KEY_LANGUAGE, value).apply()

    var updateIntervalHours: Int
        get() = sp.getInt(KEY_UPDATE_INTERVAL, 12)
        set(value) = sp.edit().putInt(KEY_UPDATE_INTERVAL, value).apply()

    /** URL, через который mihomo проверяет доступность outbound'ов. */
    var checkUrl: String
        get() = sp.getString(KEY_CHECK_URL, DEFAULT_CHECK_URL)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_CHECK_URL
        set(value) = sp.edit().putString(
            KEY_CHECK_URL, value.trim().takeIf { it.isNotBlank() } ?: DEFAULT_CHECK_URL
        ).apply()

    /**
     * Если выбранный узел не отвечает, попробовать переключиться на другой
     * рабочий узел вместо того, чтобы оставлять подключение мёртвым.
     */
    var autoSwitch: Boolean
        get() = sp.getBoolean(KEY_AUTO_SWITCH, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_SWITCH, value).apply()

    /** Однократное напоминание про системный Always-on VPN. */
    var killSwitchReminderShown: Boolean
        get() = sp.getBoolean(KEY_KILL_SWITCH_REMINDER, false)
        set(value) = sp.edit().putBoolean(KEY_KILL_SWITCH_REMINDER, value).apply()

    /**
     * Диалог уведомлений показывается после первого успешного подключения,
     * а не при каждом старте приложения. Флаг не даёт спрашивать повторно.
     */
    var notificationPermissionRequested: Boolean
        get() = sp.getBoolean(KEY_NOTIFICATION_PERMISSION, false)
        set(value) = sp.edit().putBoolean(KEY_NOTIFICATION_PERMISSION, value).apply()

    /**
     * Намерение пользователя держать VPN включённым. Хранится на диске, чтобы
     * START_STICKY после смерти процесса не включал трафик против воли пользователя
     * и, наоборот, мог восстановить подключение, которое он включал.
     */
    var vpnWanted: Boolean
        get() = sp.getBoolean(KEY_VPN_WANTED, false)
        set(value) = sp.edit().putBoolean(KEY_VPN_WANTED, value).apply()

    /** Стабильный HWID для Remnawave (10–64 символа, [a-zA-Z0-9=-]). */
    val hwid: String
        get() {
            val existing = sp.getString(KEY_HWID, null)
            if (existing != null && existing.matches(HWID_REGEX)) return existing
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val rnd = SecureRandom()
            val generated = buildString {
                repeat(16) { append(alphabet[rnd.nextInt(alphabet.length)]) }
            }
            sp.edit().putString(KEY_HWID, generated).apply()
            return generated
        }

    fun saveLastRefresh(time: Long = System.currentTimeMillis()) {
        sp.edit().putLong(KEY_LAST_REFRESH, time).apply()
    }

    val lastRefresh: Long
        get() = sp.getLong(KEY_LAST_REFRESH, 0L)

    companion object {
        private const val NAME = "rkn_vpn_prefs"
        private const val KEY_SUB_URL = "sub_url"
        private const val KEY_SUB_INFO = "sub_info"
        private const val KEY_NODES = "nodes"
        private const val KEY_SELECTED = "selected"
        private const val KEY_RULES = "rules"
        private const val KEY_MODE = "routing_mode"
        private const val KEY_LAN_DIRECT = "lan_direct"
        private const val KEY_SPLIT_ENABLED = "split_enabled"
        private const val KEY_SPLIT_APPS = "split_apps"
        private const val KEY_THEME = "theme"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_UPDATE_INTERVAL = "update_interval"
        private const val KEY_CHECK_URL = "check_url"
        const val DEFAULT_CHECK_URL = "https://www.gstatic.com/generate_204"
        private const val KEY_AUTO_SWITCH = "auto_switch"
        private const val KEY_KILL_SWITCH_REMINDER = "kill_switch_reminder"
        private const val KEY_NOTIFICATION_PERMISSION = "notification_permission_requested"
        private const val KEY_VPN_WANTED = "vpn_wanted"
        private const val KEY_HWID = "hwid"
        private const val KEY_LAST_REFRESH = "last_refresh"
        private val HWID_REGEX = Regex("^[a-zA-Z0-9=-]{10,64}$")
    }
}
