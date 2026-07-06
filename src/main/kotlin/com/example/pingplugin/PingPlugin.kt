package com.example.pingplugin

import io.osrsx.config.PluginConfig
import io.osrsx.plugin.Plugin
import io.osrsx.plugin.PluginDescriptor

/**
 * The canonical "copy me and start editing" osrsx plugin.
 *
 * It does nothing harmful — on each loop it logs whether you are logged in and, if so, your player's
 * tile — so you can watch a plugin load, enable, hot-reload and run without touching the game. Replace
 * the body of [onLoop] with your own automation.
 *
 * The three things every plugin shows:
 *   1. [PluginDescriptor] — metadata the host's `PluginManager` discovers the class by (classpath scan).
 *   2. Extending [Plugin] — the lifecycle base ([onStart] / [onLoop] / [onStop]).
 *   3. [ctx] — the [io.osrsx.api.PluginContext] game API, wired by the manager before [onStart]. Every
 *      game read/action goes through it (`ctx.players()`, `ctx.inventory()`, `ctx.webWalking()`, …).
 *
 * Per-plugin settings are optional — see [Config] below and delete it if you don't need any.
 */
@PluginDescriptor(
    name = "Ping",
    description = "Template starter plugin: logs login state and player tile each loop.",
    author = "your-name-here",
    version = "1.0",
    tags = ["example", "template"],
    // enabledByDefault = false — leave off so the user enables it from the Plugin Manager.
)
class PingPlugin : Plugin() {

    // `log` is the per-plugin scoped logger provided by the SDK (Plugin/PluginApi) — writes to the
    // in-game Logs panel + ~/.osrsx/logs. No need to create your own.

    /**
     * Optional per-plugin settings, persisted under the config group "ping". Delete this whole object
     * (and [config]) if your plugin has no settings.
     */
    object Config : PluginConfig("ping") {
        var loopMs by intItem("loopMs", "Loop interval (ms)", default = 5000, min = 500, max = 60_000)
        var announce by boolItem("announce", "Log a line each loop", default = true)
    }

    override fun config(): PluginConfig = Config

    override fun onStart() {
        log.i("[ping] started — enable/disable me from the Plugin Manager.")
    }

    override fun onLoop(): Long {
        if (!Config.announce) return Config.loopMs.toLong()

        if (!ctx.login().isLoggedIn()) {
            log.i("[ping] not logged in.")
            return Config.loopMs.toLong()
        }

        val tile = ctx.players().localPlayer()?.tile()
        log.i("[ping] logged in — player tile: $tile")

        // ---- your automation goes here ----
        // e.g. ctx.inventory().contains("Logs"), ctx.webWalking().walkTo(...), ctx.objects().closest(...)

        return Config.loopMs.toLong()
    }

    override fun onStop() {
        log.i("[ping] stopped.")
    }
}
