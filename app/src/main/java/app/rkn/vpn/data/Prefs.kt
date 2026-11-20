package app.rkn.vpn.data

import android.content.Context
import android.content.SharedPreferences
import app.rkn.vpn.core.RoutingPresets
import java.security.SecureRandom

/**
 * Локальное хранилище настроек. Сознательно без DataStore/Room:
 * приложению достаточно SharedPreferences, а зависимостей меньше.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // Токен подписки и raw-JSON узлов содержат пароли и UUID — держим их в Keystore.
    private val secure = SecureStorage(sp)

    // ---------- подписка ----------

    var subscriptionUrl: String
        get() = secure.get(KEY_SUB_URL) ?: ""
        set(value) = secure.put(KEY_SUB_URL, value.trim())

    var subscription: SubscriptionInfo
        get() = subscriptionFromJson(sp.getString(KEY_SUB_INFO, null))
        set(value) = sp.edit().putString(KEY_SUB_INFO, subscriptionToJson(value)).apply()

    var nodes: List<ProxyNode>
        get() = nodesFromJson(secure.get(KEY_NODES))
        set(value) = secure.put(KEY_NODES, nodesToJson(value))

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

    /** Включённые пресеты маршрутизации (см. RoutingPresets). */
    var activePresets: Set<String>
        get() = RoutingPresets.knownIds(
            sp.getStringSet(KEY_ACTIVE_PRESETS, emptySet()) ?: emptySet()
        )
        set(value) = sp.edit().putStringSet(
            KEY_ACTIVE_PRESETS, RoutingPresets.knownIds(value)
        ).apply()

    // ---------- split tunneling ----------

    var splitEnabled: Boolean
        get() = sp.getBoolean(KEY_SPLIT_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_SPLIT_ENABLED, value).apply()

    /**
     * "include" — через VPN только выбранные приложения (как было раньше),
     * "exclude" — через VPN всё, кроме выбранных.
     */
    var splitMode: String
        get() = if (sp.getString(KEY_SPLIT_MODE, "include") == "exclude") "exclude" else "include"
        set(value) = sp.edit().putString(
            KEY_SPLIT_MODE, if (value == "exclude") "exclude" else "include"
        ).apply()

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
     * DNS-режим: auto, cloudflare, google, custom.
     * auto — DoH + policy для ru/su/рф и fallback на system.
     */
    var dnsMode: String
        get() = when (sp.getString(KEY_DNS_MODE, DNS_AUTO)) {
            DNS_CLOUDFLARE, DNS_GOOGLE, DNS_CUSTOM -> sp.getString(KEY_DNS_MODE, DNS_AUTO)!!
            else -> DNS_AUTO
        }
        set(value) = sp.edit().putString(
            KEY_DNS_MODE,
            if (value in ALLOWED_DNS_MODES) value else DNS_AUTO
        ).apply()

    var dnsCustom: String
        get() = sp.getString(KEY_DNS_CUSTOM, "")?.trim().orEmpty()
        set(value) = sp.edit().putString(KEY_DNS_CUSTOM, value.trim()).apply()

    /**
     * Если выбранный узел не отвечает, попробовать переключиться на другой
     * рабочий узел вместо того, чтобы оставлять подключение мёртвым.
     */
    var autoSwitch: Boolean
        get() = sp.getBoolean(KEY_AUTO_SWITCH, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_SWITCH, value).apply()

    /** Первый запуск: объяснение разрешений и локального хранения данных. */
    var onboardingShown: Boolean
        get() = sp.getBoolean(KEY_ONBOARDING_SHOWN, false)
        set(value) = sp.edit().putBoolean(KEY_ONBOARDING_SHOWN, value).apply()

    /** Однократное напоминание про системный Always-on VPN. */
    var killSwitchReminderShown: Boolean
        get() = sp.getBoolean(KEY_KILL_SWITCH_REMINDER, false)
        set(value) = sp.edit().putBoolean(KEY_KILL_SWITCH_REMINDER, value).apply()

    /** Карточка статуса kill switch на главном экране (можно скрыть и вернуть в настройках). */
    var showKillSwitchStatus: Boolean
        get() = sp.getBoolean(KEY_SHOW_KILL_SWITCH_STATUS, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_KILL_SWITCH_STATUS, value).apply()

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
        private const val KEY_ACTIVE_PRESETS = "active_presets"
        private const val KEY_SPLIT_ENABLED = "split_enabled"
        private const val KEY_SPLIT_MODE = "split_mode"
        private const val KEY_SPLIT_APPS = "split_apps"
        private const val KEY_THEME = "theme"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_UPDATE_INTERVAL = "update_interval"
        private const val KEY_CHECK_URL = "check_url"
        const val DEFAULT_CHECK_URL = "https://www.gstatic.com/generate_204"
        const val DNS_AUTO = "auto"
        const val DNS_CLOUDFLARE = "cloudflare"
        const val DNS_GOOGLE = "google"
        const val DNS_CUSTOM = "custom"
        private val ALLOWED_DNS_MODES = setOf(DNS_AUTO, DNS_CLOUDFLARE, DNS_GOOGLE, DNS_CUSTOM)
        private const val KEY_DNS_MODE = "dns_mode"
        private const val KEY_DNS_CUSTOM = "dns_custom"
        private const val KEY_AUTO_SWITCH = "auto_switch"
        private const val KEY_ONBOARDING_SHOWN = "onboarding_shown"
        private const val KEY_KILL_SWITCH_REMINDER = "kill_switch_reminder"
        private const val KEY_SHOW_KILL_SWITCH_STATUS = "show_kill_switch_status"
        private const val KEY_NOTIFICATION_PERMISSION = "notification_permission_requested"
        private const val KEY_VPN_WANTED = "vpn_wanted"
        private const val KEY_HWID = "hwid"
        private const val KEY_LAST_REFRESH = "last_refresh"
        private val HWID_REGEX = Regex("^[a-zA-Z0-9=-]{10,64}$")
    }
}
