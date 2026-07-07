package io.osrsx.plugins.skilling

import io.osrsx.api.BankingService
import io.osrsx.api.ItemRef
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

        return when {
            // Cleaned ore from a sack collect is waiting → bank it.
            haveOre -> bankOre()
            // Inventory full of pay-dirt → feed the hopper.
            ctx.inventory().isFull() && payDirt > 0 -> deposit()
            // Sack has filled up → collect it (only when not still holding pay-dirt to deposit first).
            payDirt == 0 && sackNearFull() -> collect()
            // Otherwise mine (climbing up first if the upper level is requested).
            else -> mine()
        }
    }

    // ---- Steps ---------------------------------------------------------------------------------------

    private fun mine(): Long = ctx.profiler().section("miner/mlm-mine") {
        // Reach the mine first if no MLM object is loaded around us.
        if (!inMotherlode()) {
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

        val vein = vein()
        if (vein != null && vein.distance() <= INTERACT_RANGE && loop.canReach(vein)) {
            stats.status = "mining"
            vein.interact("Mine")
            return@section Rng.uniform(1500, 2500)
        }
        // No reachable vein — clear a rockfall blocking the way if one is adjacent, else wait for respawns.
        rockfall()?.takeIf { it.distance() <= INTERACT_RANGE && loop.canReach(it) }?.let {
            stats.status = "clearing rockfall"
            it.interact("Mine")
            return@section Rng.uniform(1200, 2000)
        }
        stats.status = "waiting"
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
        val ores = (MLM_ORES + NUGGET).map { ItemRef.ByName(it) }.toTypedArray()
        val before = countAll(MLM_ORES)
        banking.deposit(*ores)
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

    private fun vein(): SceneEntity? =
        ctx.objects().query().id(*ORE_VEINS).withAction("Mine").nearest()

    private fun hopper(): SceneEntity? = ctx.objects().query().id(HOPPER).nearest()
    private fun sack(): SceneEntity? = ctx.objects().query().id(SACK).nearest()
    private fun strut(): SceneEntity? = ctx.objects().query().id(STRUT_BROKEN).nearest()
    private fun rockfall(): SceneEntity? = ctx.objects().query().id(*ROCKFALLS).withAction("Mine").nearest()
    private fun climbUp(): SceneEntity? = ctx.objects().query().withAction("Climb-up").nearest()

    /** Any MLM object loaded nearby means we're in the mine (used to decide whether to travel there). */
    private fun inMotherlode(): Boolean = vein() != null || hopper() != null || sack() != null || strut() != null

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
        /** The MLM hopper (DB tile) — where we web-walk to reach the mine when nothing MLM is in scene. */
        val ANCHOR = Tile(3748, 5672, 0)

        // net.runelite.api.gameval.ObjectID
        val ORE_VEINS = intArrayOf(26661, 26662, 26663, 26664) // MOTHERLODE_ORE_SINGLE/LEFT/MIDDLE/RIGHT
        val ROCKFALLS = intArrayOf(26679, 26680)               // MOTHERLODE_ROCKFALL_1/2
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
