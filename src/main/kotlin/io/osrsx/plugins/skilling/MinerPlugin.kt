package io.osrsx.plugins.skilling

import io.osrsx.api.profile
import io.osrsx.config.PluginConfig
import io.osrsx.config.and
import io.osrsx.config.eq
import io.osrsx.config.isFalse
import io.osrsx.config.isTrue
import io.osrsx.config.notEq
import io.osrsx.plugin.HasOverlay
import io.osrsx.plugin.Plugin
import io.osrsx.plugin.PluginDescriptor
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
class MinerPlugin : Plugin(), HasOverlay {

    object Config : PluginConfig("miner") {
        private const val MLM = "Motherlode"

        var ore by enumItem("ore", "Ore", Ore.COPPER.display, Ore.displays, "What to mine")

        var location by enumItem(
            "location", "Location",
            default = MineSites.BEST,
            description = "Where to mine — only sites your account qualifies for are shown",
            visibleIf = notEq("ore", MLM),
        ) { ctx -> MineSites.optionsFor(Ore.fromDisplay(ore), ctx) }

        var bank by boolItem("bank", "Bank ore", false, "Bank the ore when full (else power-drop it)",
            visibleIf = notEq("ore", MLM))

        var getBestPickaxe by boolItem("getBestPickaxe", "Get best pickaxe", true,
            "Before mining, wield the best pickaxe your level allows — withdrawn from the bank, or bought if you own none",
            section = "Setup")

        var mlmUpper by boolItem("mlmUpper", "Use upper level", false,
            "Motherlode: mine the upper level (57 Mining) — climbs the ladder up first",
            section = "Setup", visibleIf = eq("ore", MLM))

        var mlmRepair by boolItem("mlmRepair", "Repair water wheel", true,
            "Motherlode: fix broken water-wheel struts (needs a hammer) so the sack keeps filling",
            section = "Setup", visibleIf = eq("ore", MLM))

        var mlmKeepHammer by boolItem("mlmKeepHammer", "Keep a hammer", true,
            "Carry a hammer (grabbed while banking) for repairs; off = fetch one from the bank only when a strut breaks",
            section = "Setup", visibleIf = eq("ore", MLM) and isTrue("mlmRepair"))

        var minDrop by intItem("minDrop", "Min drop (ms)", 90, 20, 2000, "Fastest per-item pause when power-dropping",
            "Ore", visibleIf = isFalse("bank") and notEq("ore", MLM))
        var maxDrop by intItem("maxDrop", "Max drop (ms)", 230, 20, 3000, "Slowest per-item pause when power-dropping",
            "Ore", visibleIf = isFalse("bank") and notEq("ore", MLM))

        var lockInput by boolItem("lockInput", "Lock user input", false,
            "While running, ignore physical mouse/keyboard input so it can't disrupt the bot", section = "Antiban")

        var stopAtLevel by intItem("stopAtLevel", "Stop at level", 0, 0, 99, "Stop when Mining hits this level (0 = never)", "Stopping")
        var stopAtOre by intItem("stopAtOre", "Stop at ore", 0, 0, 1_000_000, "Stop after this much ore (0 = never)", "Stopping")
        var stopAtGp by intItem("stopAtGp", "Stop at GP", 0, 0, 2_000_000_000, "Stop once the ore is worth this many GP (0 = never)", "Stopping")
        var stopAfterMins by intItem("stopAfterMins", "Stop after (min)", 0, 0, 100_000, "Stop after this many minutes (0 = never)", "Stopping")

        val isMotherlode: Boolean get() = ore == MLM
    }

    override fun config() = Config

    private val stats by lazy { MinerStats(ctx) }
    private val pickaxe by lazy { Pickaxe(ctx) }
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
            stats = stats,
            lockInput = { Config.lockInput },
            stopReason = { stops.reason() },
        )
    }

    private val motherlode by lazy {
        MotherlodeRoutine(
            ctx,
            useUpper = { Config.mlmUpper },
            repairWheel = { Config.mlmRepair },
            keepHammer = { Config.mlmKeepHammer },
            gearUp = { gearUp() },
            stats = stats,
            lockInput = { Config.lockInput },
            stopReason = { stops.reason() },
        )
    }

    override fun onStart() {
        stats.start()
        stats.carried = {
            if (Config.isMotherlode) inventory.count("Pay-dirt") else inventory.count(currentOreName())
        }
    }

    /** Reset the location to "Auto — select best" when the ore changes — a label for the old ore is invalid. */
    override fun onConfigChanged(key: String) {
        if (key == "ore") Config.location = MineSites.BEST
    }

    override fun onStop() {
        normal.releaseInput()
        motherlode.releaseInput()
    }

    override fun onLoop(): Long =
        if (Config.isMotherlode) motherlode.tick() else normal.tick()

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
