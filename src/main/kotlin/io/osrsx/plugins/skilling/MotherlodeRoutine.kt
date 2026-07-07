package io.osrsx.plugins.skilling

import io.osrsx.api.BankingService
import io.osrsx.api.PluginContext
import io.osrsx.api.SceneEntity
import io.osrsx.api.Tile
import io.osrsx.api.get
import io.osrsx.api.section
import io.osrsx.util.Rng

/**
 * The Motherlode Mine routine — the full pay-dirt cycle the ordinary rock loop can't model:
 *
 *  1. **Mine** ore veins for Pay-dirt (climbing to the upper level first when configured).
 *  2. **Deposit** the Pay-dirt into the hopper to be washed once the inventory is full.
 *  3. **Collect** the cleaned ore + golden nuggets from the sack when the sack fills (read live from the
 *     sack varbit), then **bank** them.
 *  4. **Repair** a broken water-wheel strut (needs a hammer) so the wheel keeps carrying washed ore to the
 *     sack.
 *
 * Objects are matched by their `net.runelite.api.gameval.ObjectID`s (robust across name localisation) and
 * interacted with in-scene, so the same code works on either level. Banking always applies for MLM (the
 * washed ore must be banked), so there is no drop mode. Every step is timed under a `miner/mlm-*` span.
 */
class MotherlodeRoutine(
    private val ctx: PluginContext,
    private val useUpper: () -> Boolean,
    private val gearUp: () -> Long?,
    private val stats: MinerStats,
    lockInput: () -> Boolean,
    stopReason: () -> String?,
) {
    private val loop = MinerLoop(ctx, lockInput, stopReason) { step() }

    fun tick(): Long = loop.tick()
    fun releaseInput() = loop.releaseInput()

    private fun step(): Long {
        stats.status = "gearing up"
        gearUp()?.let { return it }

        // Keep the wheel running: repair a broken strut whenever one is reachable and we hold a hammer.
        if (haveHammer() && strut() != null) return repair()

        val payDirt = ctx.inventory().count(PAY_DIRT)
        val haveOre = MLM_ORES.any { ctx.inventory().contains(it) } || ctx.inventory().contains(NUGGET)

        // Once the sack is (nearly) full, DRAIN it completely — keep collecting + banking until the sack
        // varbit reads empty — rather than emptying a single inventory-load and going back to mining.
        val sackSize = ctx.varps().varbit(SACK_TRANSMIT)
        if (sackSize == 0) draining = false else if (sackNearFull()) draining = true

        return when {
            // Cleaned ore from a sack collect is waiting → bank it.
            haveOre -> bankOre()
            // Draining: clear any leftover pay-dirt into the hopper first, then empty the sack load by load.
            draining && payDirt > 0 -> deposit()
            draining -> collect()
            // Inventory full of pay-dirt → feed the hopper.
            ctx.inventory().isFull() && payDirt > 0 -> deposit()
            // Otherwise mine (climbing up first if the upper level is requested).
            else -> mine()
        }
    }

    /** True while we're emptying a full sack — set when it fills, cleared only when it reads empty. */
    private var draining = false

    // ---- Steps ---------------------------------------------------------------------------------------

    private fun mine(): Long = ctx.profiler().section("miner/mlm-mine") {
        // Navigate to the central vein anchor FIRST — the web-walker handles the rockfall obstacles and the
        // route into the mine on its own; we only start looking for veins once we've arrived there.
        val me = ctx.players().localPlayer()?.tile()
        if (me == null || me.distanceTo(ANCHOR) > ARRIVE_RADIUS) {
            stats.status = "walking"
            ctx.webWalking().walkTo(ANCHOR)
            return@section Rng.uniform(800, 1400)
        }
        if (loop.isAnimating()) { stats.status = "mining"; return@section Rng.uniform(600, 1100) }

        // Upper level requested and we're still on the lower floor (a Climb-up ladder is in reach) → climb.
        if (useUpper()) {
            climbUp()?.let {
                stats.status = "climbing"
                it.interact("Climb-up")
                return@section Rng.uniform(1500, 2500)
            }
        }

        // At the anchor: mine the nearest ore vein. We deliberately do NOT gate on canReachToInteract — MLM
        // veins sit flush in the rock face so the reachability probe false-negatives on them; interact() walks
        // the last tile itself.
        val vein = vein()
        if (vein != null && vein.distance() <= INTERACT_RANGE) {
            stats.status = "mining"
            vein.interact("Mine")
            return@section Rng.uniform(1500, 2500)
        }
        stats.status = "waiting" // veins respawning
        Rng.uniform(1200, 2400)
    }

    private fun deposit(): Long = ctx.profiler().section("miner/mlm-deposit") {
        val hopper = hopper() ?: run { stats.status = "walking"; ctx.webWalking().walkTo(ANCHOR); return@section Rng.uniform(800, 1400) }
        stats.status = "depositing"
        hopper.interact("Deposit")
        Rng.uniform(1000, 1600)
    }

    private fun collect(): Long = ctx.profiler().section("miner/mlm-collect") {
        val sack = sack() ?: run { stats.status = "walking"; ctx.webWalking().walkTo(ANCHOR); return@section Rng.uniform(800, 1400) }
        stats.status = "collecting"
        sack.interact("Search")
        Rng.uniform(1000, 1600)
    }

    private fun bankOre(): Long = ctx.profiler().section("miner/mlm-bank") {
        val banking = ctx.services().get<BankingService>() ?: return@section Rng.uniform(1000, 1600)
        stats.status = "banking"
        if (!banking.isOpen()) {
            return@section if (banking.openNearest()) Rng.uniform(500, 900) else Rng.uniform(1000, 1600)
        }
        val before = countAll(MLM_ORES)
        // The single "Deposit inventory" button — banks the cleaned ore, nuggets and any clutter in one click.
        // The equipped pickaxe (weapon slot) is untouched; a pickaxe left in the inventory is re-withdrawn on
        // the next loop by gearUp().
        banking.depositAll()
        banking.close()
        stats.addProduced((before - countAll(MLM_ORES)).coerceAtLeast(0))
        Rng.uniform(600, 1000)
    }

    private fun repair(): Long = ctx.profiler().section("miner/mlm-repair") {
        val strut = strut() ?: return@section Rng.uniform(600, 1000)
        stats.status = "repairing strut"
        strut.interactAny(listOf("Repair", "Fix", "Hammer"))
        Rng.uniform(1500, 2500)
    }

    // ---- Scene queries -------------------------------------------------------------------------------

    /** The closest ore vein by LOCAL-PATHFINDER distance (not Chebyshev) — inherently reachable, so it never
     *  targets a vein walled off behind a rockfall (the cause of the "mines 14 then stalls" hang). */
    private fun vein(): SceneEntity? =
        ctx.objects().query().id(*ORE_VEINS).withAction("Mine").nearestByPath()

    private fun hopper(): SceneEntity? = ctx.objects().query().id(HOPPER).nearest()
    private fun sack(): SceneEntity? = ctx.objects().query().id(SACK).nearest()
    private fun strut(): SceneEntity? = ctx.objects().query().id(STRUT_BROKEN).nearest()
    private fun climbUp(): SceneEntity? = ctx.objects().query().withAction("Climb-up").nearest()

    // ---- Sack fill (read from the game varbit) -------------------------------------------------------

    /** The sack is (almost) full — collect before the next deposit overflows it. */
    private fun sackNearFull(): Boolean {
        val size = ctx.varps().varbit(SACK_TRANSMIT)
        val capacity = if (ctx.varps().varbit(BIGGER_SACK) == 1) SACK_LARGE else SACK_SMALL
        return capacity - size < INV_MARGIN
    }

    private fun haveHammer(): Boolean = ctx.inventory().contains("Hammer") || ctx.inventory().contains("Imcando hammer")

    private fun countAll(names: List<String>): Int = names.sumOf { ctx.inventory().count(it) }

    private companion object {
        /** The central lower-level vein tile the web-walker routes to (it handles the rockfall obstacles and
         *  the route into the mine itself). Only once we're here do we start looking for veins. */
        val ANCHOR = Tile(3731, 5668, 0)

        /** How close to [ANCHOR] counts as "arrived and ready to mine". */
        const val ARRIVE_RADIUS = 5

        // net.runelite.api.gameval.ObjectID
        val ORE_VEINS = intArrayOf(26661, 26662, 26663, 26664) // MOTHERLODE_ORE_SINGLE/LEFT/MIDDLE/RIGHT
        const val STRUT_BROKEN = 26670                          // MOTHERLODE_WHEEL_STRUT_BROKEN
        const val HOPPER = 26674                                // MOTHERLODE_HOPPER
        const val SACK = 26688                                  // MOTHERLODE_SACK (searchable)

        // net.runelite.api.gameval.VarbitID
        const val SACK_TRANSMIT = 5558 // current pay-dirt/ore count in the sack
        const val BIGGER_SACK = 5556   // 1 once the 100-nugget sack upgrade is bought
        const val SACK_SMALL = 108
        const val SACK_LARGE = 189

        /** Collect once the sack has fewer than an inventory's worth of free slots left. */
        const val INV_MARGIN = 28

        const val INTERACT_RANGE = 15

        const val PAY_DIRT = "Pay-dirt"
        const val NUGGET = "Golden nugget"
        val MLM_ORES = listOf(
            "Runite ore", "Adamantite ore", "Mithril ore", "Gold ore", "Coal", "Silver ore", "Iron ore",
        )
    }
}
