package io.osrsx.plugins.skilling

import io.osrsx.api.Player
import io.osrsx.api.Skill
import io.osrsx.api.WorldInfo
import io.osrsx.testkit.TestContext
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MineSitesTest {

    /** A headless context reporting a fixed membership / combat / Mining level for eligibility checks. */
    private fun ctx(members: Boolean, combat: Int, mining: Int): TestContext {
        val ctx = TestContext()
        whenever(ctx.worlds.current()).thenReturn(301)
        whenever(ctx.worlds.list())
            .thenReturn(listOf(WorldInfo(301, 100, 0, members, false, false, emptySet())))
        val player: Player = mock()
        whenever(player.combatLevel).thenReturn(combat)
        whenever(ctx.players.localPlayer()).thenReturn(player)
        whenever(ctx.skills.real(Skill.MINING)).thenReturn(mining)
        whenever(ctx.webWalking.nearestBank()).thenReturn(null)
        return ctx
    }

    @Test
    fun `ore display round-trips`() {
        Ore.entries.forEach { assertEquals(it, Ore.fromDisplay(it.display)) }
    }

    @Test
    fun `every rock ore has at least one f2p site`() {
        listOf(Ore.COPPER, Ore.TIN, Ore.IRON, Ore.COAL).forEach { ore ->
            assertTrue(MineSites.SITES.any { it.ore == ore && !it.members }, "no F2P site for $ore")
        }
    }

    @Test
    fun `labels are unique within each ore`() {
        Ore.entries.forEach { ore ->
            val labels = MineSites.SITES.filter { it.ore == ore }.map { it.label() }
            assertEquals(labels.size, labels.toSet().size, "duplicate labels for $ore")
        }
    }

    @Test
    fun `label carries the requirement suffix`() {
        fun site(ore: Ore, id: String) = MineSites.SITES.first { it.ore == ore && it.id == id }
        assertEquals("Rimmington", site(Ore.COPPER, "Rimmington").label())
        assertEquals("VarrockSE (13 cmb)", site(Ore.IRON, "VarrockSE").label())
        assertEquals("ArdyE (P2P, 43 cmb)", site(Ore.IRON, "ArdyE").label())
        assertEquals("GuildP2P (P2P, 60 mining)", site(Ore.IRON, "GuildP2P").label())
    }

    @Test
    fun `f2p low-level account hides members, high-combat and high-mining iron sites`() {
        val options = MineSites.optionsFor(Ore.IRON, ctx(members = false, combat = 20, mining = 50))
        assertEquals(MineSites.BEST, options.last(), "BEST is always the last option")
        assertTrue(options.any { it.startsWith("VarrockSE") }, "13 cmb F2P site should show")
        assertFalse(options.any { it.startsWith("ArdyE") }, "members site should be hidden")
        assertFalse(options.any { it.startsWith("Desert1") }, "29 cmb site hidden at combat 20")
        assertFalse(options.any { it.startsWith("Dwarven") }, "65 cmb site hidden at combat 20")
        assertFalse(options.any { it.startsWith("Guild") }, "60 mining sites hidden at mining 50")
    }

    @Test
    fun `high-level members account sees every iron site`() {
        val options = MineSites.optionsFor(Ore.IRON, ctx(members = true, combat = 99, mining = 99))
        val ironSites = MineSites.SITES.count { it.ore == Ore.IRON }
        assertEquals(ironSites + 1, options.size) // every site + BEST
    }

    @Test
    fun `siteFor resolves BEST and a specific label`() {
        val ctx = ctx(members = true, combat = 99, mining = 99)
        assertNotNull(MineSites.siteFor(Ore.COAL, ctx, MineSites.BEST, banking = false))
        assertEquals("VarrockSE", MineSites.siteFor(Ore.IRON, ctx, "VarrockSE (13 cmb)", banking = false)?.id)
    }
}
