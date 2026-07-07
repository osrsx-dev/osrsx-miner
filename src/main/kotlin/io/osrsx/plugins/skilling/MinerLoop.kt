package io.osrsx.plugins.skilling

import io.osrsx.api.BreakManager
import io.osrsx.api.PluginContext
import io.osrsx.api.SceneEntity
import io.osrsx.api.get
import io.osrsx.api.section
import io.osrsx.plugin.Plugin
import io.osrsx.plugin.PluginLog
import io.osrsx.util.Rng

/**
 * The shared per-loop scaffolding the miner needs on every tick — login, coordination yield, stop targets,
 * input lock, account-wide break checks, run management, auto-dialogue and a light antiban idle — wrapped
 * around a routine-supplied [step]. Self-contained (no skilling-lib): the normal-ore and Motherlode routines
 * each HOLD one of these and drive it from `onLoop`.
 *
 * The whole tick is timed under `miner/loop` via the plugin [Profiler]; when profiling is off that is a
 * single volatile read (zero overhead), so instrumentation is left in freely.
 */
class MinerLoop(
    private val ctx: PluginContext,
    private val lockInput: () -> Boolean,
    private val stopReason: () -> String?,
    private val step: () -> Long,
) {
    private val breaks: BreakManager? get() = ctx.services().get<BreakManager>()

    /** Run one loop iteration. Returns ms until the next call, or [Plugin.NO_LOOP] to stop. */
    fun tick(): Long = ctx.profiler().section("miner/loop") {
        if (!ctx.login().isLoggedIn()) { ctx.login().login(); return@section 1500 }
        if (ctx.coordination().shouldYield()) return@section Rng.uniform(1200, 2000)
        stopReason()?.let { reason ->
            PluginLog("miner").i("stopping — $reason"); releaseInput(); return@section Plugin.NO_LOOP
        }
        applyInputLock()
        breaks?.let { if (it.onBreak()) return@section Rng.uniform(2000, 5000) }
        ctx.walking().manageRun()
        if (ctx.dialogues().inDialogue()) { ctx.dialogues().continueAuto(); return@section Rng.uniform(600, 1000) }
        if (Rng.chance(IDLE_CHANCE)) return@section Rng.uniform(IDLE_MIN_MS, IDLE_MAX_MS)
        step()
    }

    /** Release the input lock — call from the plugin's `onStop`. */
    fun releaseInput() { if (ctx.input().isLocked()) ctx.input().unlock() }

    private fun applyInputLock() {
        val want = lockInput()
        if (want && !ctx.input().isLocked()) ctx.input().lock()
        else if (!want && ctx.input().isLocked()) ctx.input().unlock()
    }

    fun isAnimating(): Boolean = (ctx.players().localPlayer()?.animation ?: IDLE) != IDLE

    /** Can we stand next to [entity] and interact with it? */
    fun canReach(entity: SceneEntity): Boolean {
        val tile = entity.tile() ?: return false
        return ctx.walking().canReachToInteract(tile)
    }

    private companion object {
        const val IDLE = -1
        const val IDLE_CHANCE = 0.03
        const val IDLE_MIN_MS = 1500L
        const val IDLE_MAX_MS = 4000L
    }
}
