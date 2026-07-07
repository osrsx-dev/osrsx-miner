package io.osrsx.plugins.skilling

import io.osrsx.api.BankingService
import io.osrsx.api.ItemRef
import io.osrsx.api.PluginContext
import io.osrsx.api.SceneEntity
import io.osrsx.api.Tile
import io.osrsx.api.get
import io.osrsx.api.section

/**
 * The Motherlode Mine routine — the full pay-dirt cycle the ordinary rock loop can't model:
 *
 *  1. **Mine** ore veins for Pay-dirt at the central vein cluster (climbing to the upper level if configured).
 *  2. **Deposit** each FULL inventory of Pay-dirt into the hopper to be washed.
 *  3. Once we've fed a whole sack's worth, **drain the sack completely** — collect + bank back-to-back until
 *     it's empty — clicking the bank chest straight after searching the sack.
 *  4. **Repair** the water wheel (the "Hammer" action) only when the inventory is full AND *both* struts are
 *     broken (the wheel still runs on one), so mining is never interrupted for it.
 *
 * Objects are matched by `net.runelite.api.gameval.ObjectID`. Banking always applies for MLM. Loop delays are
 * short and low-biased ([snap]). Every step is timed under a `miner/mlm-*` span.
 */
class MotherlodeRoutine(
    private val ctx: PluginContext,
    private val useUpper: () -> Boolean,
    private val repairWheel: () -> Boolean,
    private val gearUp: () -> Long?,
    private val stats: MinerStats,
    lockInput: () -> Boolean,
    stopReason: () -> String?,
) {
    private val loop = MinerLoop(ctx, lockInput, stopReason) { step() }

    /** True while emptying a full sack — set once the sack is full, cleared once it's empty. */
    private var draining = false

    /** Set when we deposit the load that TOPS OFF the sack, so we drain immediately instead of waiting ~10s
     *  for the sack varbit to catch up to full. */
    private var toppedOff = false

    /** For a robust drain EXIT: only stop once the sack has actually been emptied (guards the wash-lag "0"). */
    private var collectedAny = false
    private var emptyStreak = 0

    /** After finishing a drain (at the bank), walk back to the vein cluster ONCE before mining again. */
    private var returnToAnchor = false

    /** The vein tile we're currently working — kept until it depletes so we don't hop veins mid-mine. */
    private var currentVein: Tile? = null

    /** Interaction latches — set when a click actually LANDS ([SceneEntity.leftClickIfDefault] returns true)
     *  and cleared by the deterministic world-state change that click causes (bank opens / sack count drops /
     *  held pay-dirt drops). So we never re-issue an action we've already triggered — no timers, no polling. */
    private var searching = false
    private var sackAtSearch = 0
    private var depositing = false
    private var payDirtAtDeposit = 0
    private var bankOpening = false

    /** When our deposits first stopped going through (the wheel is stopped / machine backed up), or 0 when the
     *  last deposit worked. If we can't deposit for [DEPOSIT_STUCK_MS], we fix the strut ourselves. */
    private var depositStuckSince = 0L

    fun tick(): Long = loop.tick()
    fun releaseInput() = loop.releaseInput()

    private fun step(): Long {
        // Gearing is BLOCKED during a sack→bank drain: depositAll can bank a non-wielded pickaxe, and re-gearing
        // it mid-drain would stall the collect cycle at the bank. We re-gear only once the sack is empty.
        if (!draining) {
            stats.status = "gearing up"
            gearUp()?.let { return it }
        }

        // ONE inventory snapshot per loop — scan it locally rather than a contains()/count() per item (each of
        // which was a separate client-thread hop; this is the bulk of the per-loop marshalling).
        val names = ctx.inventory().items().mapNotNull { it.name }
        val payDirt = names.count { it == PAY_DIRT }
        val haveOre = names.any { it in MLM_ORE_SET }
        val haveHammer = names.any { it == "Hammer" || it == "Imcando hammer" }
        val full = names.size >= INV_SLOTS

        val capacity = sackCapacity()
        val sackNow = ctx.varps().varbit(SACK_TRANSMIT)
        val spaceLeft = (capacity - sackNow).coerceAtLeast(0) // EXACT room left in the sack (live varbit)

        // Enter drain the instant the sack is FULL — even if we're still holding pay-dirt. A full sack can't
        // accept a deposit (the hopper rejects it), so we must empty it first; the held pay-dirt is kept aside
        // (bankOre keeps it) and deposited afterwards. `toppedOff` also enters drain right after the load that
        // fills it, so we don't wait ~10s for the varbit to catch up.
        if (!draining && (spaceLeft == 0 || toppedOff)) {
            draining = true; toppedOff = false; collectedAny = false; emptyStreak = 0
        }
        // Exit once the sack is confirmed empty for a couple of loops AFTER we've actually collected (guards the
        // wash-lag "0"). Doesn't require pay-dirt to be 0 — we may be holding a load to deposit after draining.
        if (draining) {
            if (sackNow > 0) emptyStreak = 0 else if (collectedAny && !haveOre) emptyStreak++
            if (emptyStreak >= 2) { draining = false; collectedAny = false; emptyStreak = 0; returnToAnchor = true }
        }

        // Wheel repair is a last resort: normally we assume deposits work and someone else fixes a broken
        // strut. But if our own deposits have been stuck for over DEPOSIT_STUCK_MS (the wheel's clearly down),
        // fix it ourselves — we always carry a hammer for exactly this.
        if (repairWheel() && haveHammer && !draining && depositStuckSince != 0L &&
            System.currentTimeMillis() - depositStuckSince > DEPOSIT_STUCK_MS && brokenStruts() >= 2
        ) return repair()

        return when {
            // The sack + bank are on the LOWER floor — climb down first if we mined up top.
            draining && onUpperFloor() -> descend()
            draining && haveOre -> bankOre()   // bank each collected load first (frees inventory)
            // Still holding pay-dirt with room in the sack → deposit it now so the inventory has slots to
            // collect INTO (a full pay-dirt inventory can't receive any sack ore).
            draining && payDirt > 0 && spaceLeft > 0 -> deposit()
            draining -> collect()              // ...and keep collecting until the sack is empty
            // We hold enough pay-dirt to top the sack off AND it still has room → deposit now (fills it, then
            // drains). A FULL sack (spaceLeft 0) is handled by the drain-enter above, not here — depositing
            // into a full sack is rejected by the game.
            payDirt > 0 && spaceLeft in 1..payDirt -> { toppedOff = true; deposit() }
            // Otherwise deposit a full load whenever the inventory fills.
            full && payDirt > 0 -> deposit()
            else -> mine()
        }
    }

    // ---- Steps ---------------------------------------------------------------------------------------

    private fun mine(): Long = ctx.profiler().section("miner/mlm-mine") {
        // Debounced: a mining swing dips to idle briefly between hits, so only a SUSTAINED idle means the vein
        // is done — otherwise we'd hop veins mid-mine.
        if (loop.stillMining()) { stats.status = "mining"; return@section snap(250, 900) }
        closeStrayBank()?.let { return@section it }

        // Upper level preferred + accessible → climb the ladder up before mining (optional: falls back to the
        // lower floor if we can't reach the ladder).
        if (useUpper() && !onUpperFloor()) {
            val ladder = ladder()
            if (ladder != null && ladder.distance() <= INTERACT_RANGE) {
                stats.status = "climbing up"
                leftOrMenu(ladder, "Climb")
                return@section snap(1500, 2800)
            }
            stats.status = "walking"
            ctx.webWalking().walkTo(LADDER_TILE)
            return@section snap(300, 1200)
        }

        val anchor = if (onUpperFloor()) UPPER_ANCHOR else ANCHOR
        // Right after a bank trip, walk back to the vein cluster ONCE before resuming (one-shot, not a per-loop
        // gate — which would fight the small drift of walking onto each vein).
        val me = ctx.players().localPlayer()?.tile()
        if (returnToAnchor) {
            if (me != null && me.distanceTo(anchor) <= ARRIVE_RADIUS) returnToAnchor = false
            else { stats.status = "walking"; ctx.webWalking().walkTo(anchor); return@section snap(300, 1200) }
        }

        // STICK with the vein we're already mining until it's depleted — MLM veins yield several pay-dirt
        // before collapsing, so re-picking the nearest each loop (they're often equidistant) would hop off a
        // still-productive vein and waste the walk. Only choose a fresh vein once the current one is gone.
        val vein = currentVein?.let { veinAt(it) } ?: floorVein()
        if (vein != null && vein.distance() <= INTERACT_RANGE) {
            currentVein = vein.tile()
            stats.status = "mining"
            leftOrMenu(vein, "Mine")
            return@section snap(400, 1800)
        }
        currentVein = null
        stats.status = "walking"
        ctx.webWalking().walkTo(anchor)
        snap(300, 1200)
    }

    private fun deposit(): Long = ctx.profiler().section("miner/mlm-deposit") {
        closeStrayBank()?.let { return@section it }
        val anchor = if (onUpperFloor()) UPPER_ANCHOR else ANCHOR
        val hopper = hopper() ?: run { stats.status = "walking"; ctx.webWalking().walkTo(anchor); return@section snap(300, 1200) }
        val payDirt = ctx.inventory().count(PAY_DIRT)
        // A click landed last loop; judge the outcome now: pay-dirt dropped ⇒ it went through (clear the stuck
        // clock); unchanged ⇒ the deposit was rejected (wheel down) ⇒ start/keep the stuck clock running.
        if (depositing) {
            depositing = false
            if (payDirt < payDirtAtDeposit) depositStuckSince = 0L
            else if (depositStuckSince == 0L) depositStuckSince = System.currentTimeMillis()
            return@section snap(250, 700)
        }
        stats.status = "depositing"
        payDirtAtDeposit = payDirt
        if (hopper.leftClickIfDefault("Deposit")) depositing = true // the click LANDED → judge the outcome next loop
        snap(500, 1400)
    }

    private fun collect(): Long = ctx.profiler().section("miner/mlm-collect") {
        closeStrayBank()?.let { return@section it }
        val sack = sack() ?: run { stats.status = "walking"; ctx.webWalking().walkTo(SACK_ANCHOR); return@section snap(300, 1200) }
        val sackNow = ctx.varps().varbit(SACK_TRANSMIT)
        // We already clicked Search and the sack hasn't drained yet → the action is in flight, don't re-click.
        if (searching && sackNow >= sackAtSearch) return@section snap(250, 700)
        searching = false
        stats.status = "collecting"
        sackAtSearch = sackNow
        if (sack.leftClickIfDefault("Search")) searching = true // the click LANDED → latch until the sack drops
        snap(500, 1300)
    }

    private fun bankOre(): Long = ctx.profiler().section("miner/mlm-bank") {
        val banking = ctx.services().get<BankingService>() ?: return@section snap(700, 1600)
        stats.status = "banking"
        if (!banking.isOpen()) return@section openBank(banking)
        bankOpening = false
        val before = countAll(MLM_ORES)
        if (before > 0) collectedAny = true
        // Bank ONLY the cleaned ore + nuggets — keep the pickaxe, hammer and any held pay-dirt (so we never
        // bank the tools and have to re-gear, and the hammer stays on us for wheel repairs).
        banking.deposit(*ORE_REFS)
        banking.close()
        stats.addProduced((before - countAll(MLM_ORES)).coerceAtLeast(0))
        snap(400, 1000)
    }

    /** Open the sack-side bank chest with a single left-click ("Use" is its default) and WAIT for the bank to
     *  open — we latch on the landed click so we never re-click the chest once it's already opening/open (which
     *  is what caused a stray right-click landing inside the open bank UI). openNearest is only a last resort. */
    private fun openBank(banking: BankingService): Long {
        if (bankOpening) return snap(300, 900) // already clicked Use — wait for it, don't click again
        val chest = bankChest()
        if (chest != null) {
            if (chest.leftClickIfDefault("Use")) bankOpening = true
            return snap(400, 1000)
        }
        return if (banking.openNearest()) snap(400, 900) else snap(700, 1500)
    }

    /** Climb the ladder DOWN to the lower floor (the sack + bank live there) before draining. */
    private fun descend(): Long = ctx.profiler().section("miner/mlm-descend") {
        val ladder = ladder()
        if (ladder != null && ladder.distance() <= INTERACT_RANGE) {
            stats.status = "climbing down"
            leftOrMenu(ladder, "Climb")
            return@section snap(1500, 2800)
        }
        stats.status = "walking"
        ctx.webWalking().walkTo(LADDER_TILE)
        snap(300, 1200)
    }

    private fun repair(): Long = ctx.profiler().section("miner/mlm-repair") {
        closeStrayBank()?.let { return@section it }
        // Already fixed (maybe by another player) → the broken-strut object is gone, so there's nothing to do.
        val strut = strut() ?: run { depositStuckSince = 0L; return@section snap(400, 900) }
        depositStuckSince = 0L // we're acting on it — restart the stuck clock; deposits should resume after
        stats.status = "repairing wheel"
        // "Hammer" is the broken strut's DEFAULT action → left-click it. Using leftClickIfDefault (not the
        // right-click menu) means that if it just got fixed, this is a clean no-op instead of a ~10s
        // right-click-retry loop hunting for a "Hammer" option that no longer exists.
        strut.leftClickIfDefault("Hammer")
        snap(800, 2000)
    }

    /** If a bank window is still open, close it and wait — a leftover open bank (right after withdrawing a
     *  hammer, or finishing gear-up) would otherwise make us click the strut/vein/hopper "through" the UI. */
    private fun closeStrayBank(): Long? {
        val banking = ctx.services().get<BankingService>() ?: return null
        if (!banking.isOpen()) return null
        banking.close()
        return snap(300, 700)
    }

    /** Prefer a plain LEFT-click when [action] is the entity's live default (veins→Mine, hopper→Deposit,
     *  sack→Search, bank chest→Use all default to their action), falling back to the right-click menu only if
     *  it genuinely isn't the top entry. Avoids the needless right-click-and-select on every interaction. */
    private fun leftOrMenu(entity: SceneEntity, action: String) {
        if (!entity.leftClickIfDefault(action)) entity.interact(action)
    }

    // ---- Scene queries -------------------------------------------------------------------------------

    /** The hopper ON OUR FLOOR (nearest). MLM stacks both floors' objects at the same world tile/plane, so we
     *  pick by tile HEIGHT — otherwise upstairs we'd try to deposit in the unreachable lower hopper. */
    private fun hopper(): SceneEntity? = nearestOnFloor(HOPPER)
    private fun sack(): SceneEntity? = ctx.objects().query().id(SACK).nearest() // sack is lower-floor only
    private fun bankChest(): SceneEntity? = ctx.objects().query().id(BANK_CHEST).nearest()
    private fun strut(): SceneEntity? = ctx.objects().query().id(STRUT_BROKEN).nearest()
    /** How many wheel struts are broken (the wheel stops only when BOTH are). */
    private fun brokenStruts(): Int = ctx.objects().query().id(STRUT_BROKEN).count()
    /** The ladder between floors (action "Climb", up from the bottom / down from the top). Matched by NAME +
     *  the live "Climb" action so it works regardless of which ladder object id the floor uses. */
    private fun ladder(): SceneEntity? = ctx.objects().query().named("Ladder").withAction("Climb").nearest()

    /** The nearest ore vein on OUR floor that we can actually REACH — filtered by local scene-collision
     *  ([Walking.canReachToInteract]) so a vein walled off by a rockfall (common up top) is skipped for the
     *  next reachable one, and by tile height so it's never a vein on the other floor stacked at the same tile. */
    private fun floorVein(): SceneEntity? {
        val me = ctx.players().localPlayer()?.tileHeight() ?: return null
        return ctx.objects().query().id(*ORE_VEINS).withAction("Mine").within(INTERACT_RANGE).sortNearest().list()
            .firstOrNull { v ->
                val t = v.tile() ?: return@firstOrNull false
                (v.tileHeight() < UPPER_FLOOR_HEIGHT) == (me < UPPER_FLOOR_HEIGHT) && ctx.walking().canReachToInteract(t)
            }
    }

    /** The still-minable ore vein AT tile [t] on our floor, or null once it's depleted (no longer an "Ore vein"
     *  with a Mine action there). Lets us keep working one vein until it collapses. */
    private fun veinAt(t: Tile): SceneEntity? {
        val me = ctx.players().localPlayer()?.tileHeight() ?: return null
        return ctx.objects().query().id(*ORE_VEINS).withAction("Mine").within(t, 0).list()
            .firstOrNull { (it.tileHeight() < UPPER_FLOOR_HEIGHT) == (me < UPPER_FLOOR_HEIGHT) }
    }

    // ---- Floors (Motherlode overlays its two levels at the same tile+plane; only tile HEIGHT differs) -----

    private fun myHeight(): Int = ctx.players().localPlayer()?.tileHeight() ?: 0
    private fun onUpperFloor(): Boolean = myHeight() < UPPER_FLOOR_HEIGHT
    private fun sameFloor(h: Int): Boolean = (h < UPPER_FLOOR_HEIGHT) == (myHeight() < UPPER_FLOOR_HEIGHT)

    /** Nearest object of [ids] that's on the SAME floor as us. Candidates are limited to nearby tiles first
     *  (cheap) and only those are height-checked, so it's a handful of client hops, not one per scene object. */
    private fun nearestOnFloor(ids: IntArray): SceneEntity? {
        val me = ctx.players().localPlayer()?.tileHeight() ?: return null
        return ctx.objects().query().id(*ids).within(INTERACT_RANGE).sortNearest().list()
            .firstOrNull { (it.tileHeight() < UPPER_FLOOR_HEIGHT) == (me < UPPER_FLOOR_HEIGHT) }
    }
    private fun nearestOnFloor(id: Int): SceneEntity? = nearestOnFloor(intArrayOf(id))

    // ---- Sack fill -----------------------------------------------------------------------------------

    private fun sackCapacity(): Int = if (ctx.varps().varbit(BIGGER_SACK) == 1) SACK_LARGE else SACK_SMALL

    private fun countAll(names: List<String>): Int = names.sumOf { ctx.inventory().count(it) }

    private companion object {
        /** The central lower-level vein tile — mining prefers the veins nearest here, and we route back to it
         *  after banking (the walker handles the rockfalls and the route into the mine itself). */
        val ANCHOR = Tile(3731, 5668, 0)

        /** Fallback stand tile by the sack (right next to the bank) when the sack is momentarily out of scene. */
        val SACK_ANCHOR = Tile(3748, 5659, 0)

        /** The upper level's veins sit at a lower rendered tile height than the lower level's (RuneLite's
         *  own Motherlode plugin uses the same threshold). Height < this ⇒ we're on the upper floor. */
        const val UPPER_FLOOR_HEIGHT = -490

        /** Where to mine on the upper floor (by the top of the ladder / upper vein cluster), and the ladder. */
        val UPPER_ANCHOR = Tile(3755, 5679, 0)
        val LADDER_TILE = Tile(3755, 5673, 0)

        // net.runelite.api.gameval.ObjectID
        val ORE_VEINS = intArrayOf(26661, 26662, 26663, 26664) // MOTHERLODE_ORE_SINGLE/LEFT/MIDDLE/RIGHT
        const val STRUT_BROKEN = 26670                          // MOTHERLODE_WHEEL_STRUT_BROKEN ("Hammer" action)
        const val HOPPER = 26674                                // MOTHERLODE_HOPPER
        const val SACK = 26688                                  // MOTHERLODE_SACK (searchable)
        const val BANK_CHEST = 26707                            // MLM Bank chest ("Use")

        // net.runelite.api.gameval.VarbitID
        const val SACK_TRANSMIT = 5558 // current ore count in the sack
        const val BIGGER_SACK = 5556   // 1 once the 100-nugget sack upgrade is bought
        const val SACK_SMALL = 108
        const val SACK_LARGE = 189

        const val INTERACT_RANGE = 15
        const val ARRIVE_RADIUS = 5 // "arrived" at the anchor when this close
        const val DEPOSIT_STUCK_MS = 5000L // deposits blocked this long ⇒ the wheel's down, fix the strut
        const val INV_SLOTS = 28 // a full inventory (items() returns filled slots)

        const val PAY_DIRT = "Pay-dirt"
        const val NUGGET = "Golden nugget"
        val MLM_ORES = listOf(
            "Runite ore", "Adamantite ore", "Mithril ore", "Gold ore", "Coal", "Silver ore", "Iron ore",
        )

        /** Everything the sack yields (ores + nuggets) — the "we have something to bank" set. */
        val MLM_ORE_SET = (MLM_ORES + NUGGET).toHashSet()

        /** Refs to bank (ore + nuggets only) — deposited so the pickaxe, hammer and pay-dirt are kept. */
        val ORE_REFS = (MLM_ORES + NUGGET).map { ItemRef.ByName(it) }.toTypedArray()
    }
}
