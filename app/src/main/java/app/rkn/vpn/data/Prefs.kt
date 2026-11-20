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

    init {
        migrateProfilesIfNeeded()
    }

    // ---------- профили подписок ----------

    var profiles: List<SubscriptionProfile>
        get() = profilesFromJson(sp.getString(KEY_PROFILES, null))
        set(value) {
            sp.edit().putString(KEY_PROFILES, profilesToJson(value)).apply()
            val active = sp.getString(KEY_ACTIVE_PROFILE, null)
            if (active == null || value.none { it.id == active }) {
                activeProfileId = value.firstOrNull()?.id
            }
        }

    var activeProfileId: String?
        get() {
            val stored = sp.getString(KEY_ACTIVE_PROFILE, null)
            val list = profiles
            return stored?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
        }
        set(value) {
            val resolved = value?.takeIf { id -> profiles.any { it.id == id } }
                ?: profiles.firstOrNull()?.id
            sp.edit().putString(KEY_ACTIVE_PROFILE, resolved).apply()
        }

    fun activeProfile(): SubscriptionProfile? =
        profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()

    fun profileUrl(id: String): String = secure.get(profileUrlKey(id)) ?: ""

    fun setProfileUrl(id: String, url: String) {
        secure.put(profileUrlKey(id), url.trim())
    }

    fun updateProfile(updated: SubscriptionProfile) {
        val list = profiles.toMutableList()
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) list[idx] = updated else list.add(updated)
        profiles = list
    }

    fun removeProfile(id: String) {
        val remaining = profiles.filterNot { it.id == id }
        secure.put(profileUrlKey(id), null)
        profiles = remaining
        if (sp.getString(KEY_ACTIVE_PROFILE, null) == id) {
            activeProfileId = remaining.firstOrNull()?.id
        }
    }

    fun addProfile(profile: SubscriptionProfile, url: String): SubscriptionProfile {
        val list = profiles + profile
        secure.put(profileUrlKey(profile.id), url.trim())
        profiles = list
        if (sp.getString(KEY_ACTIVE_PROFILE, null) == null) activeProfileId = profile.id
        return profile
    }

    fun createProfile(name: String = "", url: String = ""): SubscriptionProfile {
        val profile = SubscriptionProfile(id = java.util.UUID.randomUUID().toString(), name = name)
        return addProfile(profile, url)
    }

    private fun migrateProfilesIfNeeded() {
        if (sp.contains(KEY_PROFILES)) return
        val legacyUrl = secure.get(KEY_SUB_URL) ?: ""
        val legacyInfo = subscriptionFromJson(sp.getString(KEY_SUB_INFO, null))
        val legacyNodes = nodesFromJson(secure.get(KEY_NODES))
        val legacySelected = sp.getString(KEY_SELECTED, null)
            ?.takeIf { id -> legacyNodes.any { it.id == id } }
            ?: legacyNodes.firstOrNull()?.id
        val profile = SubscriptionProfile(
            id = DEFAULT_PROFILE_ID,
            name = "",
            subscription = legacyInfo,
            nodes = legacyNodes,
            selectedId = legacySelected
        )
        sp.edit().putString(KEY_PROFILES, profilesToJson(listOf(profile))).apply()
        sp.edit().putString(KEY_ACTIVE_PROFILE, profile.id).apply()
        if (legacyUrl.isNotBlank()) {
            secure.put(profileUrlKey(profile.id), legacyUrl)
        }
        secure.put(KEY_SUB_URL, null)
        secure.put(KEY_NODES, null)
        sp.edit().remove(KEY_SUB_INFO).remove(KEY_SELECTED).apply()
    }

    // ---------- подписка (активный профиль, совместимость) ----------

    var subscriptionUrl: String
        get() = activeProfile()?.let { profileUrl(it.id) } ?: ""
        set(value) {
            val id = activeProfileId ?: ensureDefaultProfile()
            setProfileUrl(id, value)
        }

    var subscription: SubscriptionInfo
        get() = activeProfile()?.subscription ?: SubscriptionInfo()
        set(value) {
            val profile = activeProfile() ?: ensureDefaultProfileObject()
            updateProfile(profile.copy(subscription = value))
        }

    var nodes: List<ProxyNode>
        get() = activeProfile()?.nodes ?: emptyList()
        set(value) {
            val profile = activeProfile() ?: ensureDefaultProfileObject()
            val selected = profile.selectedId?.takeIf { sid -> value.any { it.id == sid } }
                ?: value.firstOrNull()?.id
            updateProfile(profile.copy(nodes = value, selectedId = selected))
        }

    var selectedId: String?
        get() = activeProfile()?.selectedId
        set(value) {
            val profile = activeProfile() ?: return
            val resolved = value?.takeIf { sid -> profile.nodes.any { it.id == sid } }
                ?: profile.nodes.firstOrNull()?.id
            updateProfile(profile.copy(selectedId = resolved))
        }

    fun selectedNode(): ProxyNode? = activeProfile()?.selectedNode()

    private fun ensureDefaultProfile(): String {
        val id = DEFAULT_PROFILE_ID
        if (profiles.none { it.id == id }) {
            profiles = listOf(SubscriptionProfile(id = id))
        }
        activeProfileId = id
        return id
    }

    private fun ensureDefaultProfileObject(): SubscriptionProfile {
        val id = ensureDefaultProfile()
        return profiles.first { it.id == id }
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
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE_PROFILE = "active_profile_id"
        const val DEFAULT_PROFILE_ID = "profile_default"
        private fun profileUrlKey(id: String) = "sub_url_$id"
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
