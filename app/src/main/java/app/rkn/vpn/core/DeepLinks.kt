package app.rkn.vpn.core

import android.net.Uri
import java.util.Locale

/**
 * Разобранная входящая ссылка. [value] намеренно не показывается в UI:
 * подписка может содержать токен, поэтому диалог отображает только [source].
 *
 * Для share-ссылок число узлов известно локально. Для адреса подписки оно
 * остаётся null: загружать подписку до подтверждения пользователя нельзя.
 */
data class DeepLinkTarget(
    val value: String,
    val source: String,
    val isSubscriptionUrl: Boolean,
    val nodeCount: Int?,
    val insecureHttp: Boolean
)

object DeepLinkParser {

    private val SHARE_SCHEMES = setOf(
        "vless", "vmess", "trojan", "ss", "ssr", "hysteria2", "hy2",
        "tuic", "anytls", "wireguard", "wg"
    )

    fun parse(data: Uri): DeepLinkTarget? {
        val scheme = data.scheme?.lowercase(Locale.ROOT).orEmpty()
        val raw = when (scheme) {
            in SHARE_SCHEMES -> data.toString()
            "clash" -> data.getQueryParameter("url")
                ?: data.getQueryParameter("config")
            "happ" -> data.getQueryParameter("url")
                ?: data.getQueryParameter("add")
                ?: data.path?.removePrefix("/")?.removePrefix("add/")?.let { Uri.decode(it) }
            "rknvpn" -> data.getQueryParameter("url")
                ?: data.getQueryParameter("text")
                ?: data.path?.removePrefix("/")?.removePrefix("add/")?.let { Uri.decode(it) }
            else -> null
        } ?: return null

        val value = raw.trim()
        if (value.isEmpty()) return null

        val isSubscription = value.startsWith("http://", true) ||
            value.startsWith("https://", true)
        // До подтверждения пользователя сеть не трогаем: адрес может быть
        // подпиской с токеном, присланной неизвестным приложением.
        val nodeCount = if (isSubscription) {
            null
        } else {
            ConfigImporter.import(value).size.takeIf { it > 0 } ?: return null
        }

        return DeepLinkTarget(
            value = value,
            source = sourceLabel(scheme),
            isSubscriptionUrl = isSubscription,
            nodeCount = nodeCount,
            insecureHttp = value.startsWith("http://", true)
        )
    }

    private fun sourceLabel(scheme: String): String = when (scheme) {
        "rknvpn" -> "RKN VPN"
        else -> scheme.uppercase(Locale.ROOT)
    }
}

/**
 * Входящая ссылка НИКОГДА не применяется сразу: сначала запрашивается
 * подтверждение, и только после нажатия «Добавить» вызывается импорт.
 */
class DeepLinkCoordinator(
    private val onConfirmationRequired: (DeepLinkTarget) -> Unit,
    private val profileName: (source: String) -> String,
    private val addNodes: (value: String, name: String) -> Boolean,
    private val addSubscription: suspend (url: String, name: String) -> Boolean
) {

    private var pending: DeepLinkTarget? = null

    /** @return true, если ссылка распознана и ожидает подтверждения. */
    fun handleDeepLink(data: Uri): Boolean {
        val target = DeepLinkParser.parse(data) ?: return false
        pending = target
        onConfirmationRequired(target)
        return true
    }

    fun cancel() {
        pending = null
    }

    suspend fun confirm(): Boolean {
        val target = pending ?: return false
        pending = null
        val name = profileName(target.source)
        return if (target.isSubscriptionUrl) {
            addSubscription(target.value, name)
        } else {
            addNodes(target.value, name)
        }
    }
}
