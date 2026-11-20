package app.rkn.vpn.util

import android.content.Context
import android.provider.Settings

/**
 * Статус системной защиты от утечек (Always-on VPN + блокировка без VPN).
 *
 * Прямое чтение скрытого ключа Settings.Secure на Android 12 (S+) запрещено:
 * Settings.Secure.getString("always_on_vpn_app") бросает SecurityException,
 * из-за чего приложение падало сразу после онбординга. Публичного API для
 * чтения чужого always-on VPN нет: VpnService.isAlwaysOn() — экземплярный
 * метод запущенного сервиса. Поэтому читаем настройки напрямую, но обязательно
 * через runCatching: при ограниченном доступе статус считается неизвестным
 * (выключенным), а экран не падает.
 */
object SystemKillSwitch {

    /** Абстракция над чтением системных настроек, чтобы логику можно было тестировать на JVM. */
    fun interface SettingReader {
        fun get(key: String): String?
    }

    /**
     * Чистая логика статуса. Любая ошибка чтения (в том числе SecurityException)
     * означает «статус неизвестен» и трактуется как выключенная защита — без вылета.
     */
    fun isOn(reader: SettingReader, packageName: String): Boolean = runCatching {
        reader.get("always_on_vpn_app") == packageName &&
            reader.get("always_on_vpn_lockdown")?.toIntOrNull() == 1
    }.getOrDefault(false)

    fun isOn(context: Context): Boolean {
        val resolver = context.contentResolver
        return isOn(
            SettingReader { key -> Settings.Secure.getString(resolver, key) },
            context.packageName
        )
    }
}
