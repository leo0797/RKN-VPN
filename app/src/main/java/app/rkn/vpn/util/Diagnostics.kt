package app.rkn.vpn.util

import app.rkn.vpn.data.AppState

/**
 * Текстовый отчёт для поддержки. Сознательно НЕ содержит ссылку на подписку,
 * HWID и пароли: их достаточно для кражи доступа к аккаунту. Адреса узлов
 * остаются только в логе ядра, который пользователь решает отправлять сам.
 */
object Diagnostics {

    fun buildReport(
        state: AppState,
        logs: String,
        appVersion: String,
        androidVersion: String,
        device: String
    ): String = buildString {
        appendLine("RKN VPN $appVersion")
        appendLine("Android $androidVersion / $device")
        appendLine()
        appendLine("VPN: ${when {
            state.connected -> "connected"
            state.connecting -> "connecting"
            state.reconnecting -> "reconnecting"
            else -> "disconnected"
        }}")
        appendLine("Node: ${state.selectedNode?.name ?: "none"} (of ${state.nodes.size})")
        appendLine("Node reachable: ${state.nodeReachable?.toString() ?: "unknown"}")
        appendLine("Last error: ${state.error ?: "none"}")
        appendLine("Subscription: ${if (state.subscriptionUrl.isBlank()) "not set" else "set"}")
        if (state.subscription.hasExpire) appendLine("Expires: ${Format.date(state.subscription.expire)}")
        if (state.subscription.hasTraffic) {
            appendLine("Traffic used: ${Format.bytes(state.subscription.used)} / ${Format.bytes(state.subscription.total)}")
        }
        appendLine("Routing: ${state.routingMode}")
        appendLine("LAN direct: ${state.lanDirect}")
        appendLine("Presets: ${state.activePresets.sorted().joinToString().ifEmpty { "none" }}")
        appendLine("Custom rules: ${state.rules.size}")
        appendLine("Split apps: ${if (state.splitEnabled) state.splitApps.size.toString() else "off"}")
        appendLine()
        appendLine("--- core log (tail) ---")
        appendLine(logs.takeLast(4_000).ifBlank { "empty" })
    }
}
