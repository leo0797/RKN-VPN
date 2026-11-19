package app.rkn.vpn.core

import app.rkn.vpn.data.ProxyNode

/**
 * Порядок узлов для автопереключения. Сначала идут узлы с известной низкой
 * задержкой, затем — с высокой, в конце — ещё не проверенные. Текущий узел
 * исключается: мы уже знаем, что он не отвечает.
 */
object NodeSelector {

    fun fallbackCandidates(
        nodes: List<ProxyNode>,
        selectedId: String?,
        latencies: Map<String, Int?>,
        limit: Int = 3
    ): List<ProxyNode> = nodes
        .asSequence()
        .filter { it.id != selectedId }
        .sortedWith(
            compareBy<ProxyNode> { latencies[it.id] == null }
                .thenBy { latencies[it.id] ?: Int.MAX_VALUE }
        )
        .take(limit)
        .toList()
}
