package io.osrsx.plugins.skilling

import io.osrsx.api.ItemRef
import io.osrsx.api.Skill
import io.osrsx.config.PluginConfig
import io.osrsx.config.isFalse
import io.osrsx.config.isTrue
import io.osrsx.plugin.HasOverlay
import io.osrsx.plugin.Plugin
import io.osrsx.plugin.PluginDescriptor
import io.osrsx.plugin.ScriptGui

/**
 * Sample mining plugin: mine a configured rock, then drop or bank the ore when full. Mirrors
 * [WoodcutterPlugin] — the differences are the object name ("… rocks"), the "Mine" action and the ore.
 * Gears up with the best pickaxe ([ToolManager]), web-walks to the nearest rock cluster when none is in
 * scene, honours stop targets and shows a stats overlay.
 *
 * Rocks stay MANUAL (no "Auto"): an ore rock is often the generically-named "Rocks" object whose ore
 * depends on its id, so a level→ore table can't reliably name the scene object. A generic "Rocks" fallback
 * covers the case where the live object reports that instead of e.g. "Iron rocks".
 */
@PluginDescriptor(
    name = "Miner",
    description = "Mines a configured rock and drops or banks the ore.",
    author = "osrsx",
    tags = ["skilling", "mining", "gathering"],
)
class MinerPlugin : Plugin(), HasOverlay {

    object Config : PluginConfig("miner") {
        var rock by objectItem("rock", "Rock name", "Rocks", "Object to mine",
            filter = listOf("rocks", "rock", "ore vein"), browse = true, distinct = true)
        var ore by itemItem("ore", "Ore name", "Copper ore", "Item name of the ore produced")
        var bank by boolItem("bank", "Bank ore", false, "Bank the ore when full (else drop it)")
        var getBestPickaxe by boolItem("getBestPickaxe", "Get best pickaxe", true,
            "Before mining, equip the best pickaxe your level allows — from the bank, or bought if you own none",
            section = "Setup")
        var walk by boolItem("walk", "Walk to rocks", true,
            "When no rock is nearby, web-walk to the nearest catalogued cluster", section = "Setup")
        var home by stringItem("home", "Rock tile", "", "Optional 'x,y[,plane]' to walk back to after banking",
            visibleIf = isTrue("bank"))

        var minDrop by intItem("minDrop", "Min drop (ms)", 90, 20, 2000, "Fastest per-item pause when power-dropping", "Ore", visibleIf = isFalse("bank"))
        var maxDrop by intItem("maxDrop", "Max drop (ms)", 230, 20, 3000, "Slowest per-item pause when power-dropping", "Ore", visibleIf = isFalse("bank"))

        var lockInput by boolItem("lockInput", "Lock user input", false,
            "While running, ignore physical mouse/keyboard input so it can't disrupt the bot", section = "Antiban")

        var stopAtLevel by intItem("stopAtLevel", "Stop at level", 0, 0, 99, "Stop when Mining hits this level (0 = never)", "Stopping")
        var stopAtOre by intItem("stopAtOre", "Stop at ore", 0, 0, 1_000_000, "Stop after this much ore (0 = never)", "Stopping")
        var stopAtGp by intItem("stopAtGp", "Stop at GP", 0, 0, 2_000_000_000, "Stop once the ore is worth this many GP (0 = never)", "Stopping")
        var stopAfterMins by intItem("stopAfterMins", "Stop after (min)", 0, 0, 100_000, "Stop after this many minutes (0 = never)", "Stopping")
    }

    override fun config() = Config

    private val stats by lazy { SkillStats(ctx, Skill.MINING) }
    private val pickaxe by lazy { ToolManager(ctx, Skill.MINING, keepExtra = { setOf(Config.ore) }) }
    private val stops by lazy {
        StopTargets(stats,
            level = { Config.stopAtLevel }, count = { Config.stopAtOre },
            gp = { Config.stopAtGp }, minutes = { Config.stopAfterMins },
            gpEach = { prices.price(Config.ore) })
    }

    private val routine by lazy {
        Gatherer(
            ctx,
            // The live object sometimes reports the generic "Rocks" name — fall back to it.
            findResource = {
                objects.query().named(Config.rock).withAction("Mine").nearest()
                    ?: objects.query().named("Rocks").withAction("Mine").nearest()
            },
            action = { "Mine" },
            products = { listOf(ItemRef(Config.ore)) },
            onFull = { if (Config.bank) OnFull.BANK else OnFull.DROP },
            homeTile = { configuredTile(Config.home) },
            gearUp = { if (Config.getBestPickaxe) pickaxe.ensure() else null },
            resourceName = { Config.rock.takeIf { Config.walk && it.isNotBlank() } },
            resourceKind = ResourceKind.OBJECT,
            options = LoopOptions(
                lockInput = { Config.lockInput },
                stopReason = { stops.reason() },
                dropParams = { DropParams(Config.minDrop, Config.maxDrop, 5, 400, 900) },
            ),
            stats = stats,
        )
    }

    override fun onStart() {
        stats.start()
        stats.carried = { inventory.count(Config.ore) }
        syncOreToRock() // heal any saved rock/ore mismatch on start
    }

    /** Keep the "Ore name" in step with the chosen rock (e.g. "Iron rocks" → "Iron ore"). The generic
     *  "Rocks" object has no known product, so it's left as configured. */
    override fun onConfigChanged(key: String) { if (key == "rock") syncOreToRock() }

    private fun syncOreToRock() {
        val ore = drops.primary(Config.rock) ?: return
        if (ore != Config.ore) Config.ore = ore
    }

    override fun onStop() { if (input.isLocked()) input.unlock() }

    override fun onLoop(): Long = routine.tick()

    override fun overlayTitle() = "Mining"

    override fun onOverlay(gui: ScriptGui) {
        val worth = stats.output() * prices.price(Config.ore)
        SkillOverlay.render(gui, stats, listOf(
            "Target" to Config.rock,
            (if (Config.bank) "Ore banked" else "Ore dropped")
                to "${SkillOverlay.commas(stats.output())} (${SkillOverlay.compact(stats.perHour(worth))} gp/hr)",
        ))
    }
}
