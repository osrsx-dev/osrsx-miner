package io.osrsx.plugins.skilling

import io.osrsx.api.PluginContext
import io.osrsx.api.Skill
import io.osrsx.plugin.ScriptGui

/**
 * Per-run bookkeeping for the miner — elapsed time, Mining XP + rates, a live status word and a tally of ore
 * that has left the inventory (dropped or banked). Self-contained (no skilling-lib): everything the overlay
 * ([MinerOverlay]) and the stop targets ([StopTargets]) read.
 */
class MinerStats(private val ctx: PluginContext) {

    /** Live status word shown in the overlay (e.g. "mining", "walking", "banking"). */
    @Volatile var status: String = "starting"

    /** Ore still in the inventory — set by the plugin so [produced] counts carried + removed. */
    var carried: () -> Int = { 0 }

    private var startXp = -1
    private var startMs = 0L
    private var removed = 0 // ore dropped or banked this run (left the inventory)

    /** Capture the baseline XP and clock. Call from `onStart`. */
    fun start() {
        startXp = ctx.skills().experience(Skill.MINING)
        startMs = System.currentTimeMillis()
        removed = 0
    }

    /** Add [n] ore that just left the inventory (dropped, banked, or fed to the MLM hopper). */
    fun addProduced(n: Int) { if (n > 0) removed += n }

    /** Ore that has actually LEFT the inventory as output. */
    fun output(): Int = removed

    /** Total ore gathered this run: output plus what's still carried. Used by stop targets. */
    fun produced(): Int = removed + carried()

    fun level(): Int = ctx.skills().real(Skill.MINING)

    fun xpGained(): Int = if (startXp < 0) 0 else (ctx.skills().experience(Skill.MINING) - startXp).coerceAtLeast(0)

    fun elapsedMs(): Long = if (startMs == 0L) 0 else System.currentTimeMillis() - startMs

    /** [total] projected to an hourly rate over the elapsed run (0 until at least a second has passed). */
    fun perHour(total: Int): Int {
        val e = elapsedMs()
        return if (e >= 1000) (total.toLong() * 3_600_000L / e).toInt() else 0
    }
}

/**
 * The first met stop target, or null to keep running. Each target is off when its config supplier is 0.
 */
class StopTargets(
    private val stats: MinerStats,
    private val level: () -> Int,
    private val count: () -> Int,
    private val gp: () -> Int,
    private val minutes: () -> Int,
    /** GP per produced ore (live price), for the [gp] target; 0 disables the money calc. */
    private val gpEach: () -> Int = { 0 },
) {
    fun reason(): String? {
        val lvl = level()
        if (lvl in 1..99 && stats.level() >= lvl) return "level $lvl reached"
        val n = count()
        if (n > 0 && stats.produced() >= n) return "${stats.produced()} mined"
        val gpTarget = gp()
        if (gpTarget > 0 && stats.produced() * gpEach() >= gpTarget) return "${stats.produced() * gpEach()} gp"
        val mins = minutes()
        if (mins > 0 && stats.elapsedMs() >= mins * 60_000L) return "$mins min elapsed"
        return null
    }
}

/**
 * Renders the miner's live stats into the engine-managed, alt-drag-movable ImGui overlay. The box, title,
 * border and position persistence are handled by the engine — this only emits the rows.
 */
object MinerOverlay {

    fun render(gui: ScriptGui, stats: MinerStats, rows: List<Pair<String, String>> = emptyList()) {
        row(gui, "Status", stats.status)
        row(gui, "Level", stats.level().toString())
        row(gui, "XP", "${compact(stats.xpGained())} (${compact(stats.perHour(stats.xpGained()))}/hr)")
        rows.forEach { (label, value) -> row(gui, label, value) }
        row(gui, "Runtime", elapsed(stats.elapsedMs()))
    }

    private fun row(gui: ScriptGui, label: String, value: String) {
        gui.textColored(0.58f, 0.60f, 0.70f, 1f, "$label:")
        gui.sameLine()
        gui.textColored(0.92f, 0.94f, 0.98f, 1f, value)
    }

    /** 950 -> "950", 12400 -> "12.4k", 3_500_000 -> "3.5m" (trailing ".0" dropped). */
    fun compact(n: Int): String {
        if (n < 1000) return n.toString()
        val (value, suffix) = if (n < 1_000_000) n / 1000.0 to "k" else n / 1_000_000.0 to "m"
        val tenths = kotlin.math.round(value * 10).toInt()
        return if (tenths % 10 == 0) "${tenths / 10}$suffix" else "${tenths / 10}.${tenths % 10}$suffix"
    }

    /** 12345 -> "12,345". */
    fun commas(n: Int): String {
        val s = kotlin.math.abs(n).toString()
        val sb = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(c)
        }
        return if (n < 0) "-$sb" else sb.toString()
    }

    /** ms -> "h:mm:ss" / "m:ss". */
    fun elapsed(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
