package io.osrsx.plugins.skilling

import io.osrsx.api.profile
import io.osrsx.plugin.HasOverlay
import io.osrsx.plugin.PluginDescriptor
import io.osrsx.plugin.RoutinePlugin
import io.osrsx.plugin.routine
import io.osrsx.plugin.ScriptGui

/**
 * Location-aware mining plugin. Pick an **ore** (Copper/Tin/Iron/Coal/Motherlode) and a **location**; the
 * location dropdown lists only the catalogued sites your account qualifies for (see [MineSites]) — sites you
 * can't use (members, combat, Mining level) are hidden — plus an "Auto — select best" entry that picks the
 * eligible site nearest the bank (when banking) or nearest you (when dropping).
 *
 * Selecting **Motherlode** hides the location and bank options (its banking is mandatory) and switches to the
 * dedicated [MotherlodeRoutine]; every other ore runs the catalogue-driven [NormalMiner]. Gears the best
 * pickaxe via the Loadout API ([Pickaxe]) and shows a live stats overlay. Every loop is profiled under
 * `miner/…` spans (zero-overhead when profiling is off).
 */
@PluginDescriptor(
    name = "Miner",
    description = "Mines a chosen ore at a chosen location, with the right requirements per site.",
    author = "osrsx",
    tags = ["skilling", "mining", "gathering"],
)
class MinerPlugin : RoutinePlugin(), HasOverlay {

    override fun config() = Config

    private val stats by lazy { MinerStats(ctx) }
    // For Motherlode with repair on, gearing also keeps a hammer (to fix the water wheel without a bank trip).
    private val pickaxe by lazy {
        Pickaxe(
            ctx,
            wantHammer = { Config.isMotherlode && Config.mlmRepair },
            wearProspector = { Config.wearProspector },
        )
    }
    private val stops by lazy {
        StopTargets(stats,
            level = { Config.stopAtLevel }, count = { Config.stopAtOre },
            gp = { Config.stopAtGp }, minutes = { Config.stopAfterMins },
            gpEach = { prices.price(currentOreName()) })
    }

    private fun currentOre(): Ore = Ore.fromDisplay(Config.ore)
    private fun currentOreName(): String = currentOre().product ?: ""

    /** The chosen site for the current ore, resolved live (honours "Auto — select best"). */
    private fun currentSite(): MineSite? =
        MineSites.siteFor(currentOre(), ctx, Config.location, banking = Config.bank)

    private fun gearUp(): Long? = if (Config.getBestPickaxe) pickaxe.ensure() else null

    private val normal by lazy {
        NormalMiner(
            ctx,
            site = { currentSite() },
            rockName = { currentOre().rockName ?: "Rocks" },
            oreName = { currentOreName() },
            bank = { Config.bank },
            gearUp = { gearUp() },
            dropPace = { Config.minDrop to Config.maxDrop },
            dropGems = { Config.dropGems },
            stats = stats,
        )
    }

    private val motherlode by lazy {
        MotherlodeRoutine(
            ctx,
            useUpper = { Config.mlmUpper },
            repairWheel = { Config.mlmRepair },
            gearUp = { gearUp() },
            dropGems = { Config.dropGems },
            highlight = { Config.highlightObjects },
            stats = stats,
        )
    }

    /**
     * The plugin's single **core** routine — the whole loop. It owns the shared prologue (login/yield/stop/
     * break/dialogue/idle guards + input-lock/run upkeep via [minerPrologue]), its own start/stop lifecycle
     * (stats init; input release), and delegates each tick to the normal or Motherlode sub-routine. The
     * [RoutinePlugin] base drives start/loop/stop — there is deliberately no onLoop/onStart/onStop here.
     */
    private val core by lazy {
        routine(ctx.profiler(), "miner", status = { stats.status = it }) {
            minerPrologue(ctx, { Config.lockInput }, { stops.reason() })
            onStart {
                stats.start()
                stats.carried = {
                    if (Config.isMotherlode) inventory.count("Pay-dirt") else inventory.count(currentOreName())
                }
            }
            onStop { if (ctx.input().isLocked()) ctx.input().unlock() }
            subroutine("motherlode", { Config.isMotherlode }, motherlode.routine)
            subroutine("normal", { true }, normal.routine)
        }
    }

    override fun routine() = core

    /** Reset the location to "Auto — select best" when the ore changes — a label for the old ore is invalid. */
    override fun onConfigChanged(key: String) {
        if (key == "ore") Config.location = MineSites.BEST
    }

    override fun overlayTitle() = "Mining"

    override fun onOverlay(gui: ScriptGui) = profile("miner/overlay") {
        val target = if (Config.isMotherlode) "Motherlode" else "${currentOre().display} — ${Config.location}"
        val rows = if (Config.isMotherlode) {
            listOf("Target" to target, "Ore banked" to MinerOverlay.commas(stats.output()))
        } else {
            val worth = stats.output() * prices.price(currentOreName())
            listOf(
                "Target" to target,
                (if (Config.bank) "Ore banked" else "Ore dropped")
                    to "${MinerOverlay.commas(stats.output())} (${MinerOverlay.compact(stats.perHour(worth))} gp/hr)",
            )
        }
        MinerOverlay.render(gui, stats, rows)
    }
}
