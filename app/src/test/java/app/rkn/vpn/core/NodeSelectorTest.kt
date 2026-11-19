package app.rkn.vpn.core

import app.rkn.vpn.data.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NodeSelectorTest {

    private fun node(id: String) = ProxyNode(
        id = id,
        name = id,
        protocol = "vless",
        server = "example.com",
        port = 443,
        raw = "{}"
    )

    @Test
    fun putsFastestKnownNodeFirstAndSkipsSelected() {
        val nodes = listOf(node("dead"), node("slow"), node("fast"), node("unknown"))
        val candidates = NodeSelector.fallbackCandidates(
            nodes,
            "dead",
            mapOf("slow" to 500, "fast" to 40)
        )
        assertEquals(listOf("fast", "slow", "unknown"), candidates.map { it.id })
    }

    @Test
    fun respectsLimitAndSkipsUncheckedNodes() {
        val nodes = listOf(node("dead"), node("unknown"), node("ok"), node("nice"))
        val candidates = NodeSelector.fallbackCandidates(
            nodes,
            "dead",
            mapOf("ok" to 120, "nice" to 60),
            limit = 2
        )
        assertEquals(listOf("nice", "ok"), candidates.map { it.id })
        assertFalse(candidates.any { it.id == "dead" || it.id == "unknown" })
    }
}
