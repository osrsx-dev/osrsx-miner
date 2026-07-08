package io.osrsx.plugins.skilling

import io.osrsx.config.PluginConfig
import io.osrsx.config.and
import io.osrsx.config.eq
import io.osrsx.config.isFalse
import io.osrsx.config.notEq

/**
 * The miner's user-facing configuration.
 *
 * By convention (not a requirement) the config lives in its own file rather than nested inside
 * [MinerPlugin], so the plugin file stays about *behaviour* and this file is the single place that
 * describes the *option surface*. It's a top-level `object` in the plugin's package; [MinerPlugin]
 * references it as `Config` and hands it back from `config()`.
 */
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

    var dropGems by boolItem("dropGems", "Drop gems", true,
        "Power-drop any uncut gems as they're mined instead of keeping them", section = "Setup")

    var wearProspector by boolItem("wearProspector", "Wear prospector outfit", true,
        "Equip the Prospector outfit (Mining XP boost) from your bank if you own it", section = "Setup")

    var highlightObjects by boolItem("highlightObjects", "Highlight targets", true,
        "Outline the object the bot is currently acting on (vein, hopper, sack, bank, ladder)", section = "Setup")

    var mlmUpper by boolItem("mlmUpper", "Use upper level", false,
        "Motherlode: mine the upper level (57 Mining) — climbs the ladder up first",
        section = "Setup", visibleIf = eq("ore", MLM))

    var mlmRepair by boolItem("mlmRepair", "Repair water wheel", true,
        "Motherlode: carry a hammer and fix the water wheel yourself if deposits stay blocked for 5s (a stopped wheel)",
        section = "Setup", visibleIf = eq("ore", MLM))

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
