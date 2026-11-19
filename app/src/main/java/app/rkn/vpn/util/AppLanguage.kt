package app.rkn.vpn.util

import android.content.Context
import java.util.Locale

/** Применение языка приложения. "system" возвращает системную локаль. */
object AppLanguage {

    private var systemLocale: Locale? = null

    fun apply(context: Context, language: String) {
        if (systemLocale == null) systemLocale = Locale.getDefault()
        val locale = when (language) {
            "en" -> Locale.ENGLISH
            "ru" -> Locale("ru")
            "system" -> systemLocale ?: Locale.getDefault()
            else -> return
        }
        Locale.setDefault(locale)
        val config = context.resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }
}
