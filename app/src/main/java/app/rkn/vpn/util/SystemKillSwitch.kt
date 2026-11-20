package app.rkn.vpn.util

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Статус системной защиты от утечек (Always-on VPN + блокировка без VPN).
 *
 * Точно определить состояние можно только из запущенного VpnService
 * (isAlwaysOn/isLockdownEnabled, API 29+). Чтение скрытых ключей Settings.Secure
 * на Android 12+ может быть запрещено (SecurityException), поэтому при ошибке
 * или недоступности данных возвращаем UNKNOWN вместо ложного OFF.
 */
object SystemKillSwitch {

    enum class Status { ON, OFF, UNKNOWN }

    /** Абстракция над чтением системных настроек, чтобы логику можно было тестировать на JVM. */
    fun interface SettingReader {
        fun get(key: String): String?
    }

    /**
     * Статус по данным Always-on/lockdown. Любая ошибка чтения (в том числе
     * SecurityException) означает, что определить состояние нельзя.
     */
    fun status(alwaysOnPackage: String?, lockdown: String?, packageName: String): Status {
        if (alwaysOnPackage == null && lockdown == null) return Status.UNKNOWN
        return if (alwaysOnPackage == packageName && lockdown?.toIntOrNull() == 1) {
            Status.ON
        } else {
            Status.OFF
        }
    }

    /** Чтение из системных настроек; при ограниченном доступе — UNKNOWN. */
    fun status(reader: SettingReader, packageName: String): Status = runCatching {
        status(reader.get("always_on_vpn_app"), reader.get("always_on_vpn_lockdown"), packageName)
    }.getOrDefault(Status.UNKNOWN)

    fun status(context: Context): Status {
        val resolver = context.contentResolver
        return status(
            SettingReader { key -> Settings.Secure.getString(resolver, key) },
            context.packageName
        )
    }

    /** Статус, сообщённый запущенным сервисом (API 29+). */
    fun serviceStatus(isAlwaysOn: Boolean, isLockdownEnabled: Boolean): Status =
        if (isAlwaysOn && isLockdownEnabled) Status.ON else Status.OFF

    @Deprecated("Use status()")
    fun isOn(reader: SettingReader, packageName: String): Boolean =
        status(reader, packageName) == Status.ON

    @Deprecated("Use status()")
    fun isOn(context: Context): Boolean = status(context) == Status.ON

    fun supportsServiceStatus(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
}
