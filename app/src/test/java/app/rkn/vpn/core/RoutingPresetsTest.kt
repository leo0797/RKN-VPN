package app.rkn.vpn.core

import app.rkn.vpn.data.RuleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingPresetsTest {

    @Test
    fun expandsPresetIntoDomainSuffixRules() {
        val rules = RoutingPresets.rulesFor(setOf("ru_direct"))
        assertEquals(RoutingPresets.byId("ru_direct")!!.domains.size, rules.size)
        assertTrue(rules.all { it.type == "DOMAIN-SUFFIX" })
        assertTrue(rules.all { it.target == RuleTarget.DIRECT })
        assertTrue(rules.any { it.value == "yandex.ru" })
        assertTrue(rules.any { it.value == "gosuslugi.ru" })
    }

    @Test
    fun ignoresUnknownPresetIds() {
        assertEquals(setOf("ru_direct"), RoutingPresets.knownIds(listOf("ru_direct", "bogus")))
        assertTrue(RoutingPresets.rulesFor(setOf("bogus")).isEmpty())
        assertTrue(RoutingPresets.byId("bogus") == null)
    }

    @Test
    fun allPresetDomainsAreValidClashValues() {
        RoutingPresets.ALL.forEach { preset ->
            preset.domains.forEach { domain ->
                assertTrue("$domain has whitespace", domain.none { it.isWhitespace() })
                assertTrue("$domain has a leading dot", !domain.startsWith("."))
                assertTrue("$domain has a trailing dot", !domain.endsWith("."))
            }
        }
    }
}
