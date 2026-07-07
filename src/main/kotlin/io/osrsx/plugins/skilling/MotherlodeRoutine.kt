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
 *  1. **Mine** ore veins for Pay-dirt (climbing to the upper level first when configured).
 *  2. **Deposit** the Pay-dirt into the hopper to be washed once the inventory is full.
 *  3. **Collect** the cleaned ore + golden nuggets from the sack once it's full, then **bank** the lot with
 *     the single "Deposit inventory" button — draining the sack COMPLETELY, load by load.
 *  4. **Repair** a broken water-wheel strut (the "Hammer" action, needs a hammer) so the wheel keeps
 *     carrying washed ore to the sack.
 *
 * Objects are matched by their `net.runelite.api.gameval.ObjectID`s (robust across name localisation). Banking
 * always applies for MLM (the washed ore must be banked), so there is no drop mode. Loop delays are short and
 * low-biased ([snap]). Every step is timed under a `miner/mlm-*` span.
 */
class MotherlodeRoutine(
    private val ctx: PluginContext,
    private val useUpper: () -> Boolean,
    private val repairWheel: () -> Boolean,
    /** true = keep a hammer in the inventory (grabbed while banking); false = fetch one from the bank only
     *  on-demand when a strut actually breaks. */
    private val keepHammer: () -> Boolean,
    private val gearUp: () -> Long?,
    private val stats: MinerStats,
    lockInput: () -> Boolean,
    stopReason: () -> String?,
) {
    private val loop = MinerLoop(ctx, lockInput, stopReason) { step() }

    /** True while emptying a full sack — set when it fills, cleared only when it reads empty. */
    private var draining = false

    /** Pay-dirt count last loop + total deposited since the last full drain — lets us know the sack is full
     *  from what we PUT IN, instead of waiting ~10s for the sack varbit to catch up after the last deposit. */
    private var lastPayDirt = 0
    private var depositedTotal = 0

    /** Debounce so we don't fire the "Search"/"Deposit" interaction twice while the first click is still
     *  walking us to the sack / hopper. */
    private var lastSearchMs = 0L
    private var lastDepositMs = 0L

    /** Consecutive hammer-fetch attempts that didn't yield one — stops retrying if the bank has no hammer. */
    private var hammerFetchTries = 0

    fun tick(): Long = loop.tick()
    fun releaseInput() = loop.releaseInput()

    private fun step(): Long {
        stats.status = "gearing up"
        gearUp()?.let { return it }

        // Track pay-dirt deposits so we can judge the sack's fullness ourselves (the varbit lags the last
        // deposit by ~10s). A drop in held pay-dirt with no mining means we just fed the hopper.
        val payDirt = ctx.inventory().count(PAY_DIRT)
        if (payDirt < lastPayDirt) depositedTotal += (lastPayDirt - payDirt)
        lastPayDirt = payDirt

        val haveOre = MLM_ORES.any { ctx.inventory().contains(it) } || ctx.inventory().contains(NUGGET)

        // Drain the sack completely once it fills — keep collecting + banking until it reads empty. Only reset
        // the deposit tally when a drain actually FINISHES (not during the ~10s wash lag while accumulating,
        // when the sack varbit is still 0 even though we've deposited a lot).
        if (draining && ctx.varps().varbit(SACK_TRANSMIT) == 0 && !haveOre) { draining = false; depositedTotal = 0 }
        else if (sackFull()) draining = true

        return when {
            haveOre -> bankOre()                              // bank what we just collected
            draining && payDirt > 0 -> deposit()              // clear leftover pay-dirt, then empty the sack
            draining -> collect()
            ctx.inventory().isFull() && payDirt > 0 -> whenFull()
            else -> mine()
        }
    }

    /** Inventory is full of pay-dirt. Normally deposit it — but if the wheel is stopped (BOTH struts broken)
     *  a deposit wouldn't wash, so fix the struts first (this is the only point we repair, so mining is never
     *  interrupted for it). Falls back to depositing if we can't get a hammer. */
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

        // Upper level requested and a Climb-up ladder is in reach (we're on the lower floor) → climb.
        if (useUpper()) {
            climbUp()?.let {
                stats.status = "climbing"
                it.interact("Climb-up")
                return@section snap(1000, 2200)
            }
        }

        // Mine the nearest ore vein by LOCAL-PATHFINDER distance (never one walled off behind a rockfall). We
        // do NOT gate on canReachToInteract — MLM veins sit flush in the rock face so that probe false-negatives.
        // Mining any in-range vein (rather than forcing a return to the exact anchor each loop) avoids the
        // anchor↔vein oscillation, since interact() walks the last few tiles to the vein.
        val vein = vein()
        if (vein != null && vein.distance() <= INTERACT_RANGE) {
            stats.status = "mining"
            vein.interact("Mine")
            return@section snap(400, 1800)
        }
        // No vein within reach → travel to the central vein cluster (the walker handles the rockfalls and the
        // route into the mine itself).
        stats.status = "walking"
        ctx.webWalking().walkTo(ANCHOR)
        snap(300, 1200)
    }

    private fun deposit(): Long = ctx.profiler().section("miner/mlm-deposit") {
        val hopper = hopper() ?: run { stats.status = "walking"; ctx.webWalking().walkTo(ANCHOR); return@section snap(300, 1200) }
        // Debounce: don't re-issue "Deposit" while the previous click is still walking us to the hopper.
        val now = System.currentTimeMillis()
        if (now - lastDepositMs < DEPOSIT_DEBOUNCE_MS) return@section snap(250, 700)
        stats.status = "depositing"
        hopper.interact("Deposit")
        lastDepositMs = now
        snap(500, 1400)
    }

    private fun collect(): Long = ctx.profiler().section("miner/mlm-collect") {
        val sack = sack() ?: run { stats.status = "walking"; ctx.webWalking().walkTo(SACK_ANCHOR); return@section snap(300, 1200) }
        // Debounce: don't re-issue "Search" while the previous click is still walking us to the sack.
        val now = System.currentTimeMillis()
        if (now - lastSearchMs < SEARCH_DEBOUNCE_MS) return@section snap(250, 700)
        stats.status = "collecting"
        sack.interact("Search")
        lastSearchMs = now
        snap(500, 1300)
    }

    private fun bankOre(): Long = ctx.profiler().section("miner/mlm-bank") {
        val banking = ctx.services().get<BankingService>() ?: return@section snap(700, 1600)
        stats.status = "banking"
        if (!banking.isOpen()) {
            return@section if (banking.openNearest()) snap(400, 900) else snap(700, 1500)
        }
        val before = countAll(MLM_ORES)
        // The single "Deposit inventory" button — banks the cleaned ore, nuggets and any clutter in one click.
        // The equipped pickaxe (weapon slot) is untouched; a pickaxe left in the inventory is re-withdrawn by
        // gearUp() next loop.
        banking.depositAll()
        // While we're at the bank, top up a hammer so wheel repairs never need a dedicated trip.
        if (repairWheel() && keepHammer() && !haveHammer()) ctx.bank().withdraw("Hammer", 1)
        banking.close()
        stats.addProduced((before - countAll(MLM_ORES)).coerceAtLeast(0))
        snap(400, 1000)
    }

    /** Fetch a hammer from the bank (on-demand repair path). Gives up after [MAX_HAMMER_TRIES] if none found. */
    private fun fetchHammer(): Long = ctx.profiler().section("miner/mlm-hammer") {
        val banking = ctx.services().get<BankingService>() ?: return@section snap(700, 1600)
        stats.status = "getting hammer"
        if (!banking.isOpen()) {
            return@section if (banking.openNearest()) snap(400, 900) else snap(700, 1500)
        }
        if (haveHammer()) { banking.close(); hammerFetchTries = 0; return@section snap(400, 900) }
        ctx.bank().withdraw("Hammer", 1)
        hammerFetchTries++
        snap(400, 900)
    }

    private fun repair(): Long = ctx.profiler().section("miner/mlm-repair") {
        val strut = strut() ?: return@section snap(400, 900)
        stats.status = "repairing wheel"
        strut.interactAny(listOf("Hammer", "Fix", "Repair")) // confirmed action: "Hammer"
        snap(800, 2000)
    }

    // ---- Scene queries -------------------------------------------------------------------------------

    /** The closest ore vein by LOCAL-PATHFINDER distance (not Chebyshev) — inherently reachable, so it never
     *  targets a vein walled off behind a rockfall (the cause of the "mines 14 then stalls" hang). */
    private fun vein(): SceneEntity? =
        ctx.objects().query().id(*ORE_VEINS).withAction("Mine").nearestByPath()

    private fun hopper(): SceneEntity? = ctx.objects().query().id(HOPPER).nearest()
    private fun sack(): SceneEntity? = ctx.objects().query().id(SACK).nearest()
    private fun strut(): SceneEntity? = ctx.objects().query().id(STRUT_BROKEN).nearest()
    /** How many of the wheel's struts are currently broken (the wheel stops only when BOTH are). */
    private fun brokenStruts(): Int = ctx.objects().query().id(STRUT_BROKEN).count()
    private fun climbUp(): SceneEntity? = ctx.objects().query().withAction("Climb-up").nearest()

    // ---- Sack fill -----------------------------------------------------------------------------------

    private fun sackCapacity(): Int = if (ctx.varps().varbit(BIGGER_SACK) == 1) SACK_LARGE else SACK_SMALL

    /** The sack is (going to be) full — either the live varbit says so, OR we've already deposited a sack's
     *  worth of pay-dirt (so we start collecting immediately without waiting for the laggy varbit update). */
    private fun sackFull(): Boolean {
        val cap = sackCapacity()
        return (cap - ctx.varps().varbit(SACK_TRANSMIT) < INV_MARGIN) || (depositedTotal >= cap - INV_MARGIN)
    }

    private fun haveHammer(): Boolean = ctx.inventory().contains("Hammer") || ctx.inventory().contains("Imcando hammer")

    private fun countAll(names: List<String>): Int = names.sumOf { ctx.inventory().count(it) }

    private companion object {
        /** The central lower-level vein tile the web-walker routes to (it handles the rockfall obstacles and
         *  the route into the mine itself). Only once we're here do we start looking for veins. */
        val ANCHOR = Tile(3731, 5668, 0)

        /** Where to stand to reach the sack (and, right beside it, the bank) — used while draining so we head
         *  straight between sack and bank instead of detouring back to the vein [ANCHOR]. */
        val SACK_ANCHOR = Tile(3748, 5659, 0)

        // net.runelite.api.gameval.ObjectID
        val ORE_VEINS = intArrayOf(26661, 26662, 26663, 26664) // MOTHERLODE_ORE_SINGLE/LEFT/MIDDLE/RIGHT
        const val STRUT_BROKEN = 26670                          // MOTHERLODE_WHEEL_STRUT_BROKEN ("Hammer" action)
        const val HOPPER = 26674                                // MOTHERLODE_HOPPER
        const val SACK = 26688                                  // MOTHERLODE_SACK (searchable)

        // net.runelite.api.gameval.VarbitID
        const val SACK_TRANSMIT = 5558 // current ore count in the sack
        const val BIGGER_SACK = 5556   // 1 once the 100-nugget sack upgrade is bought
        const val SACK_SMALL = 108
        const val SACK_LARGE = 189

        /** Start collecting once fewer than an inventory's worth of space is left in the sack. */
        const val INV_MARGIN = 28

        const val INTERACT_RANGE = 15
        const val SEARCH_DEBOUNCE_MS = 2500L
        const val DEPOSIT_DEBOUNCE_MS = 2000L
        const val MAX_HAMMER_TRIES = 4

        const val PAY_DIRT = "Pay-dirt"
        const val NUGGET = "Golden nugget"
        val MLM_ORES = listOf(
            "Runite ore", "Adamantite ore", "Mithril ore", "Gold ore", "Coal", "Silver ore", "Iron ore",
        )
    }
}
