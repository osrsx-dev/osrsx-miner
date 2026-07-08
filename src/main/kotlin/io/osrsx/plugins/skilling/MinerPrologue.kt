package io.osrsx.plugins.skilling

import io.osrsx.api.BreakManager
import io.osrsx.api.PluginContext
import io.osrsx.api.SceneEntity
import io.osrsx.api.get
import io.osrsx.plugin.Plugin
import io.osrsx.plugin.PluginLog
import io.osrsx.plugin.RoutineBuilder
import io.osrsx.util.Rng

/**
 * The shared per-tick scaffolding every miner routine needs, expressed as [io.osrsx.plugin.Routine] guards +
 * a before-tick side effect instead of a hand-rolled loop — this REPLACES the old `MinerLoop`.
 *
 * Guards run in priority order BEFORE the routine senses, so a heavy / side-effecting `sense()` (an inventory
 * read, a `gearUp()` that banks a pickaxe) never fires while logged out or on a break:
 *
 *   login → coordination-yield → stop-target → break → auto-dialogue → antiban-idle
 *
 * The always-run upkeep — input-lock maintenance + run-energy management — is the [RoutineBuilder.beforeEach]
 * hook, gated on being logged in. Input-lock is plugin *policy* (a config toggle), so it lives HERE in the
 * plugin, not in the SDK base.
 */
fun <C> RoutineBuilder<C>.minerPrologue(
    ctx: PluginContext,
    lockInput: () -> Boolean,
    stopReason: () -> String?,
) {
    beforeEach {
        if (ctx.login().isLoggedIn()) {
            val want = lockInput()
            if (want && !ctx.input().isLocked()) ctx.input().lock()
            else if (!want && ctx.input().isLocked()) ctx.input().unlock()
            ctx.walking().manageRun()
        }
    }
    guard("login", { !ctx.login().isLoggedIn() }) { ctx.login().login(); 1500 }
    guard("yielding", { ctx.coordination().shouldYield() }) { Rng.uniform(1200, 2000) }
    guard("stopping", { stopReason() != null }) {
        PluginLog("miner").i("stopping — ${stopReason()}")
        if (ctx.input().isLocked()) ctx.input().unlock()
        Plugin.NO_LOOP
    }
    guard("break", { ctx.services().get<BreakManager>()?.onBreak() == true }) { Rng.uniform(2000, 5000) }
    guard("dialogue", { ctx.dialogues().inDialogue() }) { ctx.dialogues().continueAuto(); Rng.uniform(600, 1000) }
    guard("idle", { Rng.chance(IDLE_CHANCE) }) { Rng.uniform(IDLE_MIN_MS, IDLE_MAX_MS) }
}

private const val IDLE_CHANCE = 0.03
private const val IDLE_MIN_MS = 1500L
private const val IDLE_MAX_MS = 4000L

/**
 * Debounced "still busy at the rock" gate (was `MinerLoop.stillMining`): a mining swing dips to idle for a beat
 * between hits, so a bare not-animating would look like mining stopped and make the routine hop rocks / re-click
 * mid-mine. Reads busy while animating, and for up to [debounceMs] after idle first appears — only a sustained
 * idle (rock actually depleted) reads as not-mining.
 */
class IdleGate(private val ctx: PluginContext, private val defaultDebounceMs: Long = 600L) {
    private var idleSinceMs = 0L

    fun isAnimating(): Boolean = (ctx.players().localPlayer()?.animation ?: IDLE) != IDLE

    fun stillBusy(debounceMs: Long = defaultDebounceMs): Boolean {
        if (isAnimating()) { idleSinceMs = 0L; return true }
        if (idleSinceMs == 0L) idleSinceMs = System.currentTimeMillis()
        return System.currentTimeMillis() - idleSinceMs < debounceMs
    }

    private companion object { const val IDLE = -1 }
}

/** Can we stand next to [entity] and interact with it? (was `MinerLoop.canReach`.) */
fun PluginContext.canReach(entity: SceneEntity): Boolean {
    val tile = entity.tile() ?: return false
    return walking().canReachToInteract(tile)
}
