package io.osrsx.plugins.skilling

import io.osrsx.api.BankingService
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
    /** true = keep a hammer in the inventory (grabbed while banking); false = fetch one from the bank only
     *  on-demand when the wheel is actually stopped. */
    private val keepHammer: () -> Boolean,
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

    /** Interaction latches — set when a click actually LANDS ([SceneEntity.leftClickIfDefault] returns true)
     *  and cleared by the deterministic world-state change that click causes (bank opens / sack count drops /
     *  held pay-dirt drops). So we never re-issue an action we've already triggered — no timers, no polling. */
    private var searching = false
    private var sackAtSearch = 0
    private var depositing = false
    private var payDirtAtDeposit = 0
    private var bankOpening = false

    /** Consecutive on-demand hammer-fetch attempts that didn't yield one — stops retrying if the bank has none. */
    private var hammerFetchTries = 0

    fun tick(): Long = loop.tick()
    fun releaseInput() = loop.releaseInput()

    private fun step(): Long {
        stats.status = "gearing up"
        gearUp()?.let { return it }

        val payDirt = ctx.inventory().count(PAY_DIRT)
        val haveOre = MLM_ORES.any { ctx.inventory().contains(it) } || ctx.inventory().contains(NUGGET)
        val capacity = sackCapacity()
        val sackNow = ctx.varps().varbit(SACK_TRANSMIT)
        val spaceLeft = (capacity - sackNow).coerceAtLeast(0) // EXACT room left in the sack (live varbit)

        // Enter drain the instant the sack is full: the varbit shows no space, OR we just deposited the load
        // that tops it off (so we don't wait ~10s for the varbit to catch up).
        if (!draining && payDirt == 0 && (spaceLeft == 0 || toppedOff)) {
            draining = true; toppedOff = false; collectedAny = false; emptyStreak = 0
        }
        // Exit once the sack is confirmed empty for a couple of loops AFTER we've actually collected (guards the
        // wash-lag "0" right after topping off).
        if (draining) {
            if (sackNow > 0) emptyStreak = 0 else if (collectedAny && !haveOre && payDirt == 0) emptyStreak++
            if (emptyStreak >= 2) { draining = false; collectedAny = false; emptyStreak = 0 }
        }

        return when {
            draining && haveOre -> bankOre()   // bank each collected load...
            draining -> collect()              // ...and keep collecting until the sack is empty
            // We hold enough pay-dirt to fill the sack → stop mining and deposit it now (tops the sack off).
            payDirt > 0 && spaceLeft in 1..payDirt -> { toppedOff = true; whenFull() }
            // Otherwise deposit a full load whenever the inventory fills.
            ctx.inventory().isFull() && payDirt > 0 -> whenFull()
            else -> mine()
        }
    }

    /** Inventory full of pay-dirt: deposit it — unless BOTH struts are broken (the wheel is stopped, so a
     *  deposit wouldn't wash), in which case fix them first. Only point we ever repair, so mining isn't
     *  interrupted. Falls back to depositing if no hammer can be obtained. */
    private fun whenFull(): Long {
        if (repairWheel() && brokenStruts() >= 2) {
            if (haveHammer()) return repair()
            if (hammerFetchTries < MAX_HAMMER_TRIES) return fetchHammer()
        }
        return deposit()
    }

    // ---- Steps ---------------------------------------------------------------------------------------

    private fun mine(): Long = ctx.profiler().section("miner/mlm-mine") {
        if (loop.isAnimating()) { stats.status = "mining"; return@section snap(250, 900) }

        if (useUpper()) {
            climbUp()?.let {
                stats.status = "climbing"
                it.interact("Climb-up")
                return@section snap(1000, 2200)
            }
        }

        // Prefer the ore vein nearest the ANCHOR (the cluster), NOT the one nearest the player — otherwise
        // after a bank trip it would mine sub-optimal veins by the bank instead of returning to the cluster.
        val vein = anchorVein()
        if (vein != null && vein.distance() <= INTERACT_RANGE) {
            stats.status = "mining"
            leftOrMenu(vein, "Mine")
            return@section snap(400, 1800)
        }
        // The cluster's veins are out of reach (we're away, e.g. just banked) → travel back to the anchor.
        stats.status = "walking"
        ctx.webWalking().walkTo(ANCHOR)
        snap(300, 1200)
    }

    private fun deposit(): Long = ctx.profiler().section("miner/mlm-deposit") {
        val hopper = hopper() ?: run { stats.status = "walking"; ctx.webWalking().walkTo(ANCHOR); return@section snap(300, 1200) }
        val payDirt = ctx.inventory().count(PAY_DIRT)
        // We already clicked Deposit and the pay-dirt hasn't left yet → the action is in flight, don't re-click.
        if (depositing && payDirt >= payDirtAtDeposit) return@section snap(250, 700)
        depositing = false
        stats.status = "depositing"
        payDirtAtDeposit = payDirt
        if (hopper.leftClickIfDefault("Deposit")) depositing = true // the click LANDED → latch until pay-dirt drops
        snap(500, 1400)
    }

    private fun collect(): Long = ctx.profiler().section("miner/mlm-collect") {
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
        // Single "Deposit inventory" button — banks ore, nuggets and clutter; the equipped pickaxe stays.
        banking.depositAll()
        // Top up a hammer while we're here so repairs never need a dedicated trip.
        if (repairWheel() && keepHammer() && !haveHammer()) ctx.bank().withdraw("Hammer", 1)
        banking.close()
        stats.addProduced((before - countAll(MLM_ORES)).coerceAtLeast(0))
        snap(400, 1000)
    }

    /** Fetch a hammer from the bank (on-demand repair path). Gives up after [MAX_HAMMER_TRIES] if none found. */
    private fun fetchHammer(): Long = ctx.profiler().section("miner/mlm-hammer") {
        val banking = ctx.services().get<BankingService>() ?: return@section snap(700, 1600)
        stats.status = "getting hammer"
        if (!banking.isOpen()) return@section openBank(banking)
        bankOpening = false
        if (haveHammer()) { banking.close(); hammerFetchTries = 0; return@section snap(400, 900) }
        ctx.bank().withdraw("Hammer", 1)
        hammerFetchTries++
        snap(400, 900)
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

    private fun repair(): Long = ctx.profiler().section("miner/mlm-repair") {
        val strut = strut() ?: return@section snap(400, 900)
        stats.status = "repairing wheel"
        strut.interactAny(listOf("Hammer", "Fix", "Repair")) // confirmed action: "Hammer"
        snap(800, 2000)
    }

    /** Prefer a plain LEFT-click when [action] is the entity's live default (veins→Mine, hopper→Deposit,
     *  sack→Search, bank chest→Use all default to their action), falling back to the right-click menu only if
     *  it genuinely isn't the top entry. Avoids the needless right-click-and-select on every interaction. */
    private fun leftOrMenu(entity: SceneEntity, action: String) {
        if (!entity.leftClickIfDefault(action)) entity.interact(action)
    }

    // ---- Scene queries -------------------------------------------------------------------------------

    /** The ore vein nearest the ANCHOR (keeps mining in the cluster regardless of where we currently stand).
     *  We do NOT gate on canReachToInteract — MLM veins sit flush in the rock face so that probe false-negatives. */
    private fun anchorVein(): SceneEntity? =
        ctx.objects().query().id(*ORE_VEINS).withAction("Mine").list()
            .minByOrNull { it.tile()?.distanceTo(ANCHOR) ?: Int.MAX_VALUE }

    private fun hopper(): SceneEntity? = ctx.objects().query().id(HOPPER).nearest()
    private fun sack(): SceneEntity? = ctx.objects().query().id(SACK).nearest()
    private fun bankChest(): SceneEntity? = ctx.objects().query().id(BANK_CHEST).nearest()
    private fun strut(): SceneEntity? = ctx.objects().query().id(STRUT_BROKEN).nearest()
    /** How many wheel struts are broken (the wheel stops only when BOTH are). */
    private fun brokenStruts(): Int = ctx.objects().query().id(STRUT_BROKEN).count()
    private fun climbUp(): SceneEntity? = ctx.objects().query().withAction("Climb-up").nearest()

    // ---- Sack fill -----------------------------------------------------------------------------------

    private fun sackCapacity(): Int = if (ctx.varps().varbit(BIGGER_SACK) == 1) SACK_LARGE else SACK_SMALL

    private fun haveHammer(): Boolean = ctx.inventory().contains("Hammer") || ctx.inventory().contains("Imcando hammer")

    private fun countAll(names: List<String>): Int = names.sumOf { ctx.inventory().count(it) }

    private companion object {
        /** The central lower-level vein tile — mining prefers the veins nearest here, and we route back to it
         *  after banking (the walker handles the rockfalls and the route into the mine itself). */
        val ANCHOR = Tile(3731, 5668, 0)

        /** Fallback stand tile by the sack (right next to the bank) when the sack is momentarily out of scene. */
        val SACK_ANCHOR = Tile(3748, 5659, 0)

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
        const val MAX_HAMMER_TRIES = 4

        const val PAY_DIRT = "Pay-dirt"
        const val NUGGET = "Golden nugget"
        val MLM_ORES = listOf(
            "Runite ore", "Adamantite ore", "Mithril ore", "Gold ore", "Coal", "Silver ore", "Iron ore",
        )
    }
}
