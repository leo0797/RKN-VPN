package app.rkn.vpn.core

import app.rkn.vpn.R
import app.rkn.vpn.data.RoutingRule
import app.rkn.vpn.data.RuleTarget

/**
 * Готовые наборы правил. Списки встроены в APK намеренно: внешние rule-set
 * провайдеры и geoip-базы требуют загрузки по сети, а именно сеть здесь
 * часто и заблокирована. Каждый домен разворачивается в обычное DOMAIN-SUFFIX
 * правило — mihomo не нужно ни скачивать, ни парсить дополнительные файлы.
 */
data class RoutingPreset(
    val id: String,
    val nameRes: Int,
    val descriptionRes: Int,
    val domains: List<String>,
    val target: RuleTarget = RuleTarget.DIRECT
)

object RoutingPresets {

    private val RU_SERVICES = RoutingPreset(
        id = "ru_direct",
        nameRes = R.string.preset_ru_direct_title,
        descriptionRes = R.string.preset_ru_direct_desc,
        target = RuleTarget.DIRECT,
        domains = listOf(
            "yandex.ru", "ya.ru", "yandex.kz", "yandex.by", "yandex.com.tr",
            "vk.com", "vk.ru", "vkmail.ru", "ok.ru",
            "mail.ru", "rambler.ru", "list.ru", "bk.ru",
            "gosuslugi.ru", "mos.ru", "sberbank.ru", "sber.ru", "sbermarket.ru",
            "vtb.ru", "tinkoff.ru", "alfabank.ru", "raiffeisen.ru",
            "ozon.ru", "wildberries.ru", "avito.ru", "kinopoisk.ru", "rutube.ru"
        )
    )

    private val SYSTEM_UPDATES = RoutingPreset(
        id = "updates_direct",
        nameRes = R.string.preset_updates_direct_title,
        descriptionRes = R.string.preset_updates_direct_desc,
        target = RuleTarget.DIRECT,
        domains = listOf(
            "windowsupdate.microsoft.com", "update.microsoft.com",
            "download.windowsupdate.com", "windowsupdate.com",
            "dl.google.com", "play.google.com",
            "apps.apple.com", "itunes.apple.com", "updates-http.cdn-apple.com",
            "archive.ubuntu.com", "security.ubuntu.com", "deb.debian.org"
        )
    )

    val ALL: List<RoutingPreset> = listOf(RU_SERVICES, SYSTEM_UPDATES)

    fun byId(id: String): RoutingPreset? = ALL.firstOrNull { it.id == id }

    /** Отсекаем неизвестные id — например, из старого или биного бэкапа. */
    fun knownIds(ids: Collection<String>): Set<String> =
        ids.filter { id -> byId(id) != null }.toSet()

    fun rulesFor(ids: Set<String>): List<RoutingRule> {
        if (ids.isEmpty()) return emptyList()
        return ALL.filter { it.id in ids }.flatMap { preset ->
            preset.domains.mapIndexed { index, domain ->
                RoutingRule(
                    id = "${preset.id}-$index",
                    type = "DOMAIN-SUFFIX",
                    value = domain,
                    target = preset.target
                )
            }
        }
    }
}
