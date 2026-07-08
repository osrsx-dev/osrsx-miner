package io.osrsx.plugins.skilling

import io.osrsx.api.BankingService
import io.osrsx.api.ItemRef
import io.osrsx.api.PluginContext
import io.osrsx.api.get
import io.osrsx.api.section
import io.osrsx.plugin.Routine
import io.osrsx.plugin.routine

/**
 * The routine for the ordinary rock ores (copper/tin/iron/coal): web-walk to the chosen catalogued [MineSite]
 * anchor, mine the nearest reachable rock there, then bank or power-drop the ore when the inventory fills.
 *
 * There is NO nearest-cluster discovery — the site is chosen explicitly from [MineSites] (by the player or
 * "Auto — select best"), and a rock is only mined once we're AT that site, so the bot can't drift to a stray
 * rock it passes en route. Every logical step is timed under a `miner/…` span via the plugin [Profiler].
 */
class NormalMiner(
    private val ctx: PluginContext,
    private val site: () -> MineSite?,
    private val rockName: () -> String,
    private val oreName: () -> String,
    private val bank: () -> Boolean,
    private val gearUp: () -> Long?,
    private val dropPace: () -> Pair<Int, Int>,
    private val dropGems: () -> Boolean,
    private val stats: MinerStats,
    lockInput: () -> Boolean,
    stopReason: () -> String?,
) {
    /**
     * One coherent read per tick, shared by every step (see [io.osrsx.plugin.Routine]). [gear] is the
     * gear-up delay when the pickaxe still needs sorting — sensed FIRST (before any inventory read), exactly
     * as the old top-of-loop `gearUp()?.let { return it }` did, so a gearing tick never touches the inventory.
     */
    private data class Snap(val gear: Long?, val invNames: List<String>, val full: Boolean)

    private val gate = IdleGate(ctx)

    /** The decision ladder: the shared [minerPrologue] guards (login/yield/stop/break/dialogue/idle) run first,
     *  then this priority list of named steps — the first whose guard holds runs, and the [io.osrsx.plugin.Routine]
     *  labels the status + times the step under `miner/<name>`. A [io.osrsx.plugin.RoutinePlugin] drives it. */
    val routine: Routine<*> = routine<Snap>(
        profiler = ctx.profiler(),
        spanPrefix = "miner",
        status = { stats.status = it },
        sense = {
            val gear = gearUp()
            if (gear != null) {
                Snap(gear, emptyList(), false)
            } else {
                val names = ctx.inventory().items().mapNotNull { it.name }
                Snap(null, names, ctx.inventory().isFull())
            }
        },
    ) {
        minerPrologue(ctx, lockInput, stopReason)
        step("gearing up", { gear != null }) { gear!! }
        step("dropping gems", { dropGems() && hasUncutGem(invNames) }) { dropGemsNow() }
        step("mining", { !full }) { mine() }
        step("banking", { bank() }) { bankOre() }
        step("dropping", { true }) { dropOre() }
    }

    /** Power-drop every uncut gem we're carrying. */
    private fun dropGemsNow(): Long = ctx.profiler().section("miner/drop-gems") {
        stats.status = "dropping gems"
        for (gem in UNCUT_GEMS) if (ctx.inventory().count(gem) > 0) ctx.inventory().drop(gem, -1, 90, 230, 5, 400, 900)
        snap(200, 500)
    }

    private fun mine(): Long = ctx.profiler().section("miner/mine") {
        // Debounced idle: a mining swing dips to idle between hits, so only a sustained idle means the rock's
        // depleted (avoids re-clicking / hopping rocks mid-mine).
        if (gate.stillBusy()) { stats.status = "mining"; return@section snap(250, 900) }

        val target = site() ?: run { stats.status = "no location"; return@section snap(1200, 2500) }

        // Not at the chosen site yet → web-walk to its anchor.
        val me = ctx.players().localPlayer()?.tile()
        if (me == null || me.distanceTo(target.tile) > ARRIVE_RADIUS) {
            stats.status = "walking"
            ctx.webWalking().walkTo(target.tile)
            return@section snap(300, 1100)
        }

        // At the site → mine the nearest reachable, close-enough rock.
        val rock = ctx.objects().query().named(rockName()).withAction("Mine").nearest()
        if (rock != null && rock.distance() <= INTERACT_RANGE && ctx.canReach(rock)) {
            stats.status = "mining"
            // Prefer a plain left-click when "Mine" is the rock's default, else fall back to the menu.
            if (!rock.leftClickIfDefault("Mine")) rock.interact("Mine")
            return@section snap(400, 1800)
        }
        stats.status = "waiting" // rocks respawning
        snap(600, 2000)
    }

    private fun bankOre(): Long = ctx.profiler().section("miner/bank") {
        val banking = ctx.services().get<BankingService>() ?: return@section dropOre()
        stats.status = "banking"
        if (!banking.isOpen()) {
            return@section if (banking.openNearest()) snap(400, 900) else snap(700, 1500)
        }
        val name = oreName()
        val before = ctx.inventory().count(name)
        banking.deposit(ItemRef.ByName(name))
        banking.close()
        stats.addProduced((before - ctx.inventory().count(name)).coerceAtLeast(0))
        snap(400, 1000)
    }

    private fun dropOre(): Long = ctx.profiler().section("miner/drop") {
        stats.status = "dropping"
        val (min, max) = dropPace()
        val name = oreName()
        val before = ctx.inventory().count(name)
        ctx.inventory().drop(name, -1, min, max, 5, 400, 900)
        stats.addProduced((before - ctx.inventory().count(name)).coerceAtLeast(0))
        snap(300, 800)
    }

    private companion object {
        /** Distance to the site anchor at/under which we're "there" and start mining in-scene rocks. */
        const val ARRIVE_RADIUS = 10

        /** Max tiles a rock may be to attempt interaction — beyond this it's off-screen with no clickbox. */
        const val INTERACT_RANGE = 15
    }
}
