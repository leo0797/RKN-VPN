package app.rkn.vpn.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import app.rkn.vpn.data.Prefs
import java.util.Locale

/**
 * Язык интерфейса. Значение хранится в [Prefs], но важно, чтобы локаль была
 * применена именно к Resources Activity: обновление конфигурации только на
 * application context не влияет на уже созданные Activity (особенно на API 33+),
 * поэтому после смены языка recreate() перезагружал те же русские строки.
 */
object AppLanguage {

    /** Системная локаль: [Resources.getSystem] не зависит от наших переопределений. */
    private val deviceLocale: Locale
        get() {
            val locales = Resources.getSystem().configuration.locales
            return if (locales.isEmpty) Locale.getDefault() else locales[0]
        }

    private fun localeFor(language: String): Locale = when (language) {
        "en" -> Locale.ENGLISH
        "ru" -> Locale("ru")
        else -> deviceLocale
    }

    /**
     * Применяет язык на уровне процесса: Locale.getDefault() используется
     * форматированием дат/размеров в [Format], а обновлённые ресурсы —
     * сервисам и уведомлениям.
     */
    fun apply(context: Context, language: String) {
        val locale = localeFor(language)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    /**
     * Возвращает контекст Activity с нужной локалью. Вызывается из
     * attachBaseContext(), чтобы строки интерфейса брались из правильного
     * values-en/values после recreate().
     */
    fun wrap(context: Context): Context {
        val locale = localeFor(Prefs(context).language)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        Locale.setDefault(locale)
        return context.createConfigurationContext(config)
    }
}
