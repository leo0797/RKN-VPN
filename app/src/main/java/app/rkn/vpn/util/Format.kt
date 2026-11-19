package app.rkn.vpn.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Format {

    private val russian: Boolean get() = Locale.getDefault().language == "ru"

    fun bytes(value: Long): String {
        val units = if (russian) {
            arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        } else {
            arrayOf("B", "KB", "MB", "GB", "TB")
        }
        if (value <= 0L) return "0 ${units[0]}"
        var v = value.toDouble()
        var index = 0
        while (v >= 1024 && index < units.lastIndex) {
            v /= 1024.0
            index++
        }
        val pattern = if (v >= 100 || index == 0) "%.0f" else "%.1f"
        return String.format(Locale.getDefault(), "$pattern %s", v, units[index])
    }

    fun speed(value: Long): String = "${bytes(value)}${if (russian) "/с" else "/s"}"

    fun duration(millis: Long): String {
        val safe = millis.coerceAtLeast(0)
        val hours = TimeUnit.MILLISECONDS.toHours(safe)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(safe) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(safe) % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    fun date(epochSeconds: Long): String {
        if (epochSeconds <= 0) return "—"
        val fmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        return fmt.format(Date(epochSeconds * 1000L))
    }

    fun daysLeft(epochSeconds: Long): Long {
        if (epochSeconds <= 0) return 0
        val diff = epochSeconds * 1000L - System.currentTimeMillis()
        return TimeUnit.MILLISECONDS.toDays(diff).coerceAtLeast(0)
    }

    fun ping(value: Int?): String = when {
        value == null -> "—"
        value < 0 -> "—"
        else -> "$value ${if (russian) "мс" else "ms"}"
    }
}
