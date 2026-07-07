package io.osrsx.plugins.skilling

import io.osrsx.api.BankingService
import io.osrsx.api.ItemRef
import io.osrsx.api.PluginContext
import io.osrsx.api.get
import io.osrsx.api.section
import io.osrsx.util.Rng

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
        return when {
            !ctx.inventory().isFull() -> mine()
            bank() -> bankOre()
            else -> dropOre()
        }
    }

    private fun mine(): Long = ctx.profiler().section("miner/mine") {
        if (loop.isAnimating()) { stats.status = "mining"; return@section Rng.uniform(600, 1100) }

        val target = site() ?: run { stats.status = "no location"; return@section Rng.uniform(1500, 2500) }

        // Not at the chosen site yet → web-walk to its anchor.
        val me = ctx.players().localPlayer()?.tile()
        if (me == null || me.distanceTo(target.tile) > ARRIVE_RADIUS) {
            stats.status = "walking"
            ctx.webWalking().walkTo(target.tile)
            return@section Rng.uniform(600, 1000)
        }

        // At the site → mine the nearest reachable, close-enough rock.
        val rock = ctx.objects().query().named(rockName()).withAction("Mine").nearest()
        if (rock != null && rock.distance() <= INTERACT_RANGE && loop.canReach(rock)) {
            stats.status = "mining"
            rock.interact("Mine")
            return@section Rng.uniform(1200, 2000)
        }
        stats.status = "waiting" // rocks respawning
        Rng.uniform(1200, 2400)
    }

    private fun bankOre(): Long = ctx.profiler().section("miner/bank") {
        val banking = ctx.services().get<BankingService>() ?: return@section dropOre()
        stats.status = "banking"
        if (!banking.isOpen()) {
            return@section if (banking.openNearest()) Rng.uniform(500, 900) else Rng.uniform(1000, 1600)
        }
        val name = oreName()
        val before = ctx.inventory().count(name)
        banking.deposit(ItemRef.ByName(name))
        banking.close()
        stats.addProduced((before - ctx.inventory().count(name)).coerceAtLeast(0))
        Rng.uniform(600, 1000)
    }

    private fun dropOre(): Long = ctx.profiler().section("miner/drop") {
        stats.status = "dropping"
        val (min, max) = dropPace()
        val name = oreName()
        val before = ctx.inventory().count(name)
        ctx.inventory().drop(name, -1, min, max, 5, 400, 900)
        stats.addProduced((before - ctx.inventory().count(name)).coerceAtLeast(0))
        Rng.uniform(400, 800)
    }

    private companion object {
        /** Distance to the site anchor at/under which we're "there" and start mining in-scene rocks. */
        const val ARRIVE_RADIUS = 10

        /** Max tiles a rock may be to attempt interaction — beyond this it's off-screen with no clickbox. */
        const val INTERACT_RANGE = 15
    }
}
