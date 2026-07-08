package io.osrsx.plugins.skilling

import io.osrsx.api.BankingService
import io.osrsx.api.HIGHLIGHT_FOREVER
import io.osrsx.api.Highlight
import io.osrsx.api.HighlightStyle
import io.osrsx.api.ItemRef
import io.osrsx.api.PluginContext
import io.osrsx.api.SceneEntity
import io.osrsx.api.Tile
import io.osrsx.api.facingTile
import io.osrsx.api.get
import io.osrsx.api.section
import io.osrsx.plugin.Routine
import io.osrsx.plugin.routine
import java.awt.Color

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
    private val dropGems: () -> Boolean,
    private val highlight: () -> Boolean,
    private val stats: MinerStats,
) {
    private val gate = IdleGate(ctx)

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

    /** Latched true once we've stood EXACTLY on [ANCHOR] (arrived in the main area, past the entrance
     *  rockfalls the web-walker cleared). Until then we only TRAVEL; no MLM-specific logic runs. Re-armed to
     *  false only if we later end up past [MINE_ENTRY_RADIUS] from the anchor (genuinely left the mine). */
    private var atMine = false

    /** The vein tile we're currently working — kept until it depletes so we don't hop veins mid-mine. */
    private var currentVein: Tile? = null

    /** Interaction latches — set when a click actually LANDS ([SceneEntity.leftClickIfDefault] returns true)
     *  and cleared by the deterministic world-state change that click causes (bank opens / sack count drops /
     *  held pay-dirt drops). So we never re-issue an action we've already triggered — no timers, no polling. */
    private var searching = false
    private var sackAtSearch = 0
    private var searchClickedAt = 0L
    private var depositing = false
    private var payDirtAtDeposit = 0
    private var bankOpening = false
    private var bankClickedAt = 0L

    /** Pay-dirt we've deposited into the hopper that HASN'T washed into the sack yet. The sack varbit lags the
     *  hopper (the wheel washes pay-dirt in gradually), so this is the only way to know the true remaining sack
     *  room — otherwise we'd deposit a fresh load on top of pending wash and overflow. Incremented when a
     *  deposit lands, decremented as the sack count climbs. Self-heals (a stale value just delays/advances the
     *  next deposit slightly). [lastSackSeen] is the previous loop's sack count, to measure each loop's wash. */
    private var hopperPending = 0
    private var lastSackSeen = -1

    /** The last time the sack count climbed (a wash happened) OR a deposit landed — our reference for "the wheel
     *  should have washed by now". If we have pay-dirt PENDING in the hopper but the sack hasn't climbed for
     *  [DEPOSIT_STUCK_MS], the wheel is stopped (both struts broken) and we fix it ourselves. 0 until the first
     *  deposit, so we never repair before there's anything to wash. */
    private var lastWashMs = 0L

    /** Climb latch — set when we issue a ladder Climb, cleared only once our FLOOR actually flips. Stops us
     *  re-clicking the ladder on arrival (which climbs straight back up, bouncing between floors). */
    private var climbing = false
    private var climbFromUpper = false
    private var climbClickedAt = 0L

    /** Consecutive loops the anchor has looked unreachable — debounces the trapped-pocket check so a transient
     *  pathfinder miss doesn't send us mining a rockfall when we aren't actually walled in. */
    private var trappedStreak = 0

    /** Consecutive mining-phase loops we've been fully idle (not animating AND not walking). If a target vein
     *  is mined out by another player while we approach it, we can end up committed to a dead tile and just
     *  stand there — this counter forces a re-pick once we've clearly stalled. */
    private var idleStreak = 0

    /** The single live target highlight — re-pointed as the current object changes (so highlights don't stack);
     *  null when nothing is marked. Auto-cleared when the plugin stops. */
    private var targetHl: Highlight? = null
    private var targetHlKey: String? = null

    /** What [senseMlm] pre-decided this tick: the early-exit branches (gear/escape/repair) as a discriminant,
     *  else [NORMAL] with the pay-dirt-cycle values the domain steps dispatch on. */
    private enum class Act { GEAR, TRAVEL, ESCAPE, REPAIR, DESCEND_REPAIR, NORMAL }

    /** ONE coherent snapshot per tick — the sack/drain state machine advanced + the old `when`-ladder inputs,
     *  so every step sees the same view (see [io.osrsx.plugin.Routine]). Fields shadow the routine's like-named
     *  state deliberately: a step reads the value sensed at tick start, not a mid-tick mutation. */
    private data class Snap(
        val act: Act,
        val gear: Long? = null,
        val haveGems: Boolean = false,
        val haveOre: Boolean = false,
        val onUpper: Boolean = false,
        val draining: Boolean = false,
        val payDirt: Int = 0,
        val spaceLeft: Int = 0,
        val hopperPending: Int = 0,
        val full: Boolean = false,
        val canDeclutter: Boolean = false,
    )

    /** The Motherlode decision ladder. [minerPrologue] guards run first; then — because the pay-dirt cycle is a
     *  compute-state-then-dispatch machine, not independent rules — [senseMlm] senses one snapshot (advancing
     *  the sack/drain state + pre-deciding the early exits) and these steps dispatch on it. A
     *  [io.osrsx.plugin.RoutinePlugin] drives it. */
    val routine: Routine<*> = routine<Snap>(
        profiler = ctx.profiler(),
        spanPrefix = "miner",
        status = { stats.status = it },
        sense = ::senseMlm,
    ) {
        onStop { clearMark() }
        step("gearing up", { act == Act.GEAR }) { gear!! }
        // The ONLY global-walker use in the MLM routine: get us to the mine initially. All in-mine navigation
        // (return-to-cluster, sack, hopper, ladders) stays local — see the routine's action methods.
        step("travelling", { act == Act.TRAVEL }) { ctx.webWalking().walkTo(ANCHOR); snap(300, 1100) }
        step("escaping", { act == Act.ESCAPE }) { escapePocket() }
        step("repairing", { act == Act.REPAIR }) { repair() }
        step("descending", { act == Act.DESCEND_REPAIR }) { descend() }
        // Bank the trip's leftovers (transport item, displaced graceful) before mining — gated so we hold no
        // pay-dirt/ore/gems, so it can never bank the good stuff. Fires once at startup, then never again.
        step("depositing junk", { act == Act.NORMAL && canDeclutter }) { declutter() }
        step("dropping gems", { act == Act.NORMAL && haveGems && !draining }) { dropGemsNow() }
        step("descending", { act == Act.NORMAL && haveOre && onUpper }) { descend() }
        step("banking", { act == Act.NORMAL && haveOre }) { bankOre() }
        step("descending", { act == Act.NORMAL && draining && onUpper }) { descend() }
        step("depositing", { act == Act.NORMAL && draining && payDirt > 0 && spaceLeft > 0 && hopperPending == 0 }) { deposit() }
        step("collecting", { act == Act.NORMAL && draining }) { collect() }
        step("waiting for wash", { act == Act.NORMAL && (full || spaceLeft in 1..payDirt) && payDirt > 0 && hopperPending > 0 }) { waitForWash() }
        step("depositing", { act == Act.NORMAL && payDirt > 0 && spaceLeft in 1..payDirt }) { toppedOff = true; deposit() }
        step("depositing", { act == Act.NORMAL && full && payDirt > 0 }) { deposit() }
        step("mining", { act == Act.NORMAL }) { mine() }
    }

    /** Outline [entity] as the CURRENT target, colour-coded per action, reusing one handle so highlights never
     *  stack. Passing null (or with the option off) clears it. The highlight tracks the entity as it moves. */
    private fun mark(entity: SceneEntity?, color: Color, label: String) {
        if (!highlight() || entity == null) { clearMark(); return }
        val key = "${entity.tile()}|$label"
        if (key == targetHlKey && targetHl?.active == true) return // already marking this exact target
        targetHl?.remove()
        targetHl = ctx.highlights().highlight(entity, HighlightStyle(color = color, label = label), HIGHLIGHT_FOREVER)
        targetHlKey = key
    }

    private fun clearMark() { targetHl?.remove(); targetHl = null; targetHlKey = null }

    private fun senseMlm(): Snap {
        // Gearing is BLOCKED during a sack→bank drain: depositAll can bank a non-wielded pickaxe, and re-gearing
        // it mid-drain would stall the collect cycle at the bank. We re-gear only once the sack is empty.
        if (!draining) {
            gearUp()?.let { return Snap(Act.GEAR, gear = it) }
        }

        // Travel to the mine anchor FIRST, and stay in TRAVEL until we're standing EXACTLY on it. The
        // web-walker mines through the entrance rockfalls itself (they're `take[rockfall (Mine)]` transports on
        // the route to ANCHOR) — that is NOT our MLM rockfall management, which must stay OFF until we're there.
        // Latch on arrival so mining movement never sends us back out; only genuinely leaving the mine (back
        // past MINE_ENTRY_RADIUS) re-arms travel. Nothing below — the sack state machine, trapped-pocket
        // recovery, mining — runs until we're standing on the anchor.
        val me = ctx.players().localPlayer()?.tile()
        if (me == null || me.distanceTo(ANCHOR) > MINE_ENTRY_RADIUS) atMine = false
        if (!atMine) {
            if (me != null && me.distanceTo(ANCHOR) == 0) atMine = true else return Snap(Act.TRAVEL)
        }

        // ONE inventory snapshot per loop — scan it locally rather than a contains()/count() per item (each of
        // which was a separate client-thread hop; this is the bulk of the per-loop marshalling).
        val names = ctx.inventory().items().mapNotNull { it.name }
        val payDirt = names.count { it == PAY_DIRT }
        val haveOre = names.any { it in MLM_ORE_SET }
        val haveGems = dropGems() && hasUncutGem(names)
        val haveHammer = names.any { it == "Hammer" || it == "Imcando hammer" }
        val full = names.size >= INV_SLOTS

        val now = System.currentTimeMillis()
        val capacity = sackCapacity()
        val sackNow = ctx.varps().varbit(SACK_TRANSMIT)
        // Each loop the sack count climbs, that much pending pay-dirt has washed in from the hopper → retire it,
        // and note that the wheel IS washing (resets the wheel-down clock).
        if (lastSackSeen in 0 until sackNow) {
            hopperPending = (hopperPending - (sackNow - lastSackSeen)).coerceAtLeast(0)
            lastWashMs = now
        }
        lastSackSeen = sackNow
        // True room left in the sack = capacity − what's IN it − what's already committed (pending in the hopper),
        // minus one reserved slot. The hopper deposits the whole inventory at once, so if a load doesn't fully
        // fit it leaves the remainder behind (the "1 pay-dirt left over" bug); reserving one makes the top-off
        // load always fit, and subtracting `hopperPending` stops us over-committing on top of an unwashed load.
        val spaceLeft = (capacity - sackNow - hopperPending - SACK_RESERVE).coerceAtLeast(0)

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

        // Trapped-pocket recovery (high priority — before we try to deposit/collect/mine). Another player
        // clears a rockfall, our nearest-reachable vein pick wanders into the opened pocket, then the rockfall
        // RESPAWNS and walls us off from the anchor: every subsequent walk (deposit, collect, return) fails to
        // route and we stall. If our floor's anchor is no longer LOCALLY reachable we're boxed in — mine our way
        // out through the blocking rockfall. Debounced so a one-loop pathfinder miss can't yank us off a vein.
        val homeAnchor = if (onUpperFloor()) UPPER_ANCHOR else ANCHOR
        if (!ctx.walking().canReachToInteract(homeAnchor)) {
            if (++trappedStreak >= TRAP_CONFIRM) return Snap(Act.ESCAPE)
        } else trappedStreak = 0

        // Wash-stall handling. When we hold pay-dirt PENDING in the hopper but the sack hasn't climbed for
        // DEPOSIT_STUCK_MS, something's wrong. Two cases, checked only when we actually suspect a stall (so the
        // scene query for struts isn't run every loop):
        //   1. Both wheel struts broken → the wheel is stopped → fix it ourselves (we carry a hammer). Firing
        //      BEFORE the wait-for-wash branch stops a deadlock: a stopped wheel never washes.
        //   2. Otherwise the wheel is fine and our PENDING estimate is just stale (the wash finished and we
        //      mis-counted, or it washed while we weren't sampling). Clear it — else `spaceLeft` stays wrongly
        //      small and `waitForWash` waits forever for a wash that isn't coming, which STALLED mining.
        if (!draining && hopperPending > 0 && lastWashMs != 0L && now - lastWashMs > DEPOSIT_STUCK_MS) {
            val bothStrutsBroken = repairWheel() && brokenStruts() >= 2
            if (bothStrutsBroken && haveHammer) return Snap(if (onUpperFloor()) Act.DESCEND_REPAIR else Act.REPAIR)
            if (now - lastWashMs > WASH_STALE_MS) hopperPending = 0 // stale estimate → trust the real sack level
        }

        // Startup clean-up flag: we've arrived with no pay-dirt/ore/gems held, but the trip left FOREIGN items
        // in the inventory — a transport item the web-walker used, graceful pieces the prospector swap
        // displaced — anything that isn't a tool. Guarded by the empty-of-valuables state so decluttering can
        // never bank pay-dirt/ore/gems, and it won't fire once mining is under way.
        val canDeclutter = !draining && payDirt == 0 && !haveOre && !hasUncutGem(names) &&
            names.any { it != "Hammer" && it != "Imcando hammer" && !it.contains("pickaxe", ignoreCase = true) }

        // Everything above matches the old `step()` exactly; the `when` that dispatched here is now the domain
        // step list in `routine`, keyed off this snapshot. The step comments live there.
        return Snap(
            act = Act.NORMAL,
            haveGems = haveGems,
            haveOre = haveOre,
            onUpper = onUpperFloor(),
            draining = draining,
            payDirt = payDirt,
            spaceLeft = spaceLeft,
            hopperPending = hopperPending,
            full = full,
            canDeclutter = canDeclutter,
        )
    }

    // ---- Steps ---------------------------------------------------------------------------------------

    private fun mine(): Long = ctx.profiler().section("miner/mlm-mine") {
        closeStrayBank()?.let { return@section it }

        // Stall recovery: if we're fully idle (not mining AND not walking) for several loops, our target vein
        // was likely mined out by someone else mid-approach (or the click never took), leaving us committed to a
        // dead tile and standing still. Drop it so the pick below re-selects a fresh, live vein. Walking or
        // animating resets the streak, so normal mining/travel never trips this.
        val moving = ctx.players().localPlayer()?.isMoving ?: false
        idleStreak = if (!gate.isAnimating() && !moving) idleStreak + 1 else 0
        if (idleStreak >= STUCK_LOOPS) { idleStreak = 0; currentVein = null; clearMark() }

        // Stay on the CURRENT vein until its object is GONE (depleted) — MLM veins yield several pay-dirt before
        // collapsing, so we switch on the object disappearing, NOT on the idle animation (which dips between
        // swings and made us hop veins early). While it's still there, keep working it.
        val current = currentVein?.let { veinAt(it) }
        if (current != null && current.distance() <= INTERACT_RANGE) {
            // CONFIRM against reality: once we're actually mining (animating), track + highlight the vein we're
            // truly standing beside — a click can land on an overlapping neighbour, so the planned tile isn't a
            // guarantee. [workedVein] is the minable vein orthogonally adjacent to us (null → keep the plan).
            val actual = if (gate.isAnimating()) (workedVein() ?: current) else current
            currentVein = actual.tile()
            return@section mineVein(actual)
        }
        currentVein = null

        // Upper level preferred + accessible → climb the ladder up before picking a vein (optional: falls back
        // to the lower floor if we can't reach the ladder). Latched so we don't bounce back down.
        if (useUpper() && !onUpperFloor()) return@section climb("climbing up")

        val anchor = if (onUpperFloor()) UPPER_ANCHOR else ANCHOR
        // Right after a bank trip, walk back to the vein cluster ONCE before resuming (one-shot, not a per-loop
        // gate — which would fight the small drift of walking onto each vein).
        val me = ctx.players().localPlayer()?.tile()
        if (returnToAnchor) {
            if (me != null && me.distanceTo(anchor) <= ARRIVE_RADIUS) returnToAnchor = false
            else { stats.status = "walking"; walkNear(anchor); return@section snap(300, 1200) }
        }

        // Current vein depleted / gone → pick the nearest REACHABLE vein on our floor and commit to it.
        val vein = floorVein()
        if (vein != null && vein.distance() <= INTERACT_RANGE) {
            currentVein = vein.tile()
            return@section mineVein(vein)
        }
        currentVein = null
        stats.status = "walking"
        walkNear(anchor)
        snap(300, 1200)
    }

    /** Work [vein]. It is already confirmed LOCALLY reachable — [floorVein] gates every candidate on the local
     *  scene-collision pathfinder ([Walking.canReachToInteract]), which is rockfall/wall-aware — so we do NOT
     *  global-walk to it: the global walker can't route through a dynamic rockfall and just stalls (that was the
     *  "fails to route between mining" bug). Instead we simply CLICK it; the game's built-in walk-to-interact
     *  paths there and mines. Click ONCE and let OSRS keep mining until it depletes; only re-issue on a SUSTAINED
     *  idle (debounced), never on the brief dip between swings. */
    /** Power-drop every uncut gem we're carrying (bank must be closed for a drop to register). */
    private fun dropGemsNow(): Long = ctx.profiler().section("miner/mlm-drop-gems") {
        closeStrayBank()?.let { return@section it }
        stats.status = "dropping gems"
        for (gem in UNCUT_GEMS) if (ctx.inventory().count(gem) > 0) ctx.inventory().drop(gem, -1, 90, 230, 5, 400, 900)
        snap(200, 500)
    }

    private fun mineVein(vein: SceneEntity): Long {
        mark(vein, Color.GREEN, "Mine")
        stats.status = "mining"
        if (gate.stillBusy(VEIN_IDLE_DEBOUNCE_MS)) return snap(400, 1000) // already mining — don't re-issue

        // VERIFY the cursor is over OUR vein before committing. A plain left-click only checks the menu by NAME
        // ("Mine Ore vein"), and even a precise click can land on a DIFFERENT vein that shares this one's screen
        // clickbox across a wall. So hover, read what's actually under the cursor, and if it isn't our target
        // tile, rotate our vein into a clean top-down view (clearing the wall's occlusion) and retry next loop.
        val vt = vein.tile()
        vein.hoverPrecise()
        val hovered = ctx.menu().hoveredTarget()?.tile
        if (vt != null && hovered != null && hovered != vt) {
            stats.status = "adjusting view"
            ctx.camera().rotateToObject(vein)
            return snap(500, 1100)
        }
        // Right vein under the cursor (or no hover info on an older client) → precise click on its footprint.
        vein.interactPrecise("Mine")
        return snap(400, 1000)
    }

    /** Walled into a pocket (anchor unreachable) → mine the nearest REACHABLE rockfall to reopen the corridor
     *  out. Rockfalls are ordinary "Mine" objects; clearing the one adjacent to our pocket restores the path. */
    private fun escapePocket(): Long = ctx.profiler().section("miner/mlm-escape") {
        closeStrayBank()?.let { return@section it }
        currentVein = null // abandon whatever pocket vein we were working
        stats.status = "clearing rockfall"
        val fall = ctx.objects().query().id(*ROCKFALL).withAction("Mine").within(INTERACT_RANGE).sortNearest().list()
            .firstOrNull { r ->
                val t = r.tile() ?: return@firstOrNull false
                (r.tileHeight() < UPPER_FLOOR_HEIGHT) == onUpperFloor() && ctx.walking().canReachToInteract(t)
            }
        if (fall != null) {
            mark(fall, Color.RED, "Clear rockfall")
            if (!gate.stillBusy(VEIN_IDLE_DEBOUNCE_MS)) leftOrMenu(fall, "Mine")
            return@section snap(400, 1000)
        }
        // No rockfall we can stand next to yet — edge locally toward the nearest one until it's in reach.
        ctx.objects().query().id(*ROCKFALL).withAction("Mine").sortNearest().nearest()?.tile()
            ?.let { ctx.walking().walkTowards(it); return@section snap(300, 1000) }
        snap(600, 1500)
    }

    private fun deposit(): Long = ctx.profiler().section("miner/mlm-deposit") {
        stats.status = "depositing" // set up-front so the latch-judging loop below doesn't leave a stale status
        closeStrayBank()?.let { return@section it }
        // Walk toward the HOPPER's own tile when it's out of scene/range — not the vein anchor, which sits ~17
        // tiles away (just beyond INTERACT_RANGE) and left us oscillating with a full inventory, never arriving.
        val hopperTile = if (onUpperFloor()) UPPER_HOPPER_TILE else HOPPER_TILE
        val hopper = hopper() ?: run { stats.status = "walking"; walkNear(hopperTile); return@section snap(300, 1200) }
        mark(hopper, Color.ORANGE, "Deposit")
        val payDirt = ctx.inventory().count(PAY_DIRT)
        // A click landed last loop; judge the outcome now. Pay-dirt dropped ⇒ it went INTO the hopper: count it
        // as pending wash and (re)start the wheel-down clock from now, so we give the wheel DEPOSIT_STUCK_MS to
        // wash it before deciding the wheel is stopped. (Note: reaching the hopper does NOT mean it washed — the
        // sack varbit climbing is the only proof of that, handled in step().)
        if (depositing) {
            depositing = false
            val deposited = payDirtAtDeposit - payDirt
            if (deposited > 0) { hopperPending += deposited; lastWashMs = System.currentTimeMillis() }
            return@section snap(250, 700)
        }
        approach(hopper, hopperTile)?.let { return@section it }
        payDirtAtDeposit = payDirt
        if (hopper.leftClickIfDefault("Deposit")) depositing = true // the click LANDED → judge the outcome next loop
        snap(500, 1400)
    }

    /** Nothing to do but let the wheel wash the hopper's pending pay-dirt into the sack — a short beat so our
     *  next deposit's space math is exact (see [hopperPending]). */
    private fun waitForWash(): Long = ctx.profiler().section("miner/mlm-wash") {
        stats.status = "washing"
        snap(700, 1600)
    }

    private fun collect(): Long = ctx.profiler().section("miner/mlm-collect") {
        stats.status = "collecting" // set up-front so the in-flight early-return below doesn't leave a stale status
        closeStrayBank()?.let { return@section it }
        val sack = sack() ?: run { stats.status = "walking"; walkNear(SACK_ANCHOR); return@section snap(300, 1200) }
        mark(sack, Color.YELLOW, "Search")
        val sackNow = ctx.varps().varbit(SACK_TRANSMIT)
        // We already clicked Search and the sack hasn't drained yet → the action is in flight. Keep waiting while
        // we're still WALKING to it (a direct click from across the room walks-to-interact, which can outlast the
        // retry timeout), and otherwise until ACTION_RETRY_MS — past which, stopped and undrained, the click
        // missed, so fall through and retry rather than waiting forever.
        val moving = ctx.players().localPlayer()?.isMoving ?: false
        if (searching && sackNow >= sackAtSearch && (moving || System.currentTimeMillis() - searchClickedAt < ACTION_RETRY_MS)) {
            return@section snap(250, 700)
        }
        searching = false
        // DIRECT click first: if the sack is on-screen, one left-click makes the game walk-to-interact — no need
        // to trudge to its anchor. Latch on the landed click; drained → re-search next load, missed → retry.
        if (sack.leftClickIfDefault("Search")) {
            searching = true; searchClickedAt = System.currentTimeMillis(); sackAtSearch = sackNow
            return@section snap(500, 1300)
        }
        reveal(sack, SACK_ANCHOR) // couldn't land a direct click (culled/occluded) → close in + face it, retry next loop
    }

    private fun bankOre(): Long = ctx.profiler().section("miner/mlm-bank") {
        val banking = ctx.services().get<BankingService>() ?: return@section snap(700, 1600)
        stats.status = "banking"
        if (!banking.isOpen()) return@section openBank(banking)
        bankOpening = false
        val before = countAll(MLM_ORES)
        if (before > 0) collectedAny = true
        // Menu injection: make "Deposit-All" the DEFAULT left-click for our ore + nuggets for the duration of
        // this deposit, so each drops with a single natural left-click instead of a right-click-and-select, then
        // clear the rules the instant we're done so the swap never leaks to any other left-click.
        val depositRules = MLM_ORE_NAMES.map { ctx.menu().setDefault("Deposit-All", it) }
        try {
            // Bank ONLY the cleaned ore + nuggets — keep the pickaxe, hammer and any held pay-dirt (so we never
            // bank the tools and have to re-gear, and the hammer stays on us for wheel repairs).
            banking.deposit(*ORE_REFS)
        } finally {
            depositRules.forEach { ctx.menu().remove(it) }
        }
        banking.close()
        stats.addProduced((before - countAll(MLM_ORES)).coerceAtLeast(0))
        snap(400, 1000)
    }

    /** One-time startup clean-up: bank whatever the trip left in the inventory that isn't a tool — a transport
     *  item the web-walker used to get here, graceful pieces the prospector swap displaced. Keeps the hammer
     *  (wheel repairs) and any pickaxe (normally wielded, so not here — kept by name if it is). Guarded by
     *  [Snap.canDeclutter] so it only runs before mining and can never bank pay-dirt / ore / gems. Reuses
     *  [openBank] to walk to + open the sack-side chest. */
    private fun declutter(): Long = ctx.profiler().section("miner/mlm-declutter") {
        val banking = ctx.services().get<BankingService>() ?: return@section snap(700, 1600)
        stats.status = "banking junk"
        if (!banking.isOpen()) return@section openBank(banking)
        val keep = buildList {
            add(ItemRef.ByName("Hammer"))
            add(ItemRef.ByName("Imcando hammer"))
            ctx.inventory().items().mapNotNull { it.name }
                .filter { it.contains("pickaxe", ignoreCase = true) }
                .distinct()
                .forEach { add(ItemRef.ByName(it)) }
        }
        banking.depositAllExcept(*keep.toTypedArray())
        banking.close()
        snap(400, 1000)
    }

    /** Open the sack-side bank chest with a single left-click ("Use" is its default). We latch on the landed
     *  click so we don't re-click a chest that's already opening (a stray click would land inside the open bank
     *  UI) — but only until [ACTION_RETRY_MS]: if the bank hasn't OPENED by then the click missed, so we clear
     *  the latch and re-click. ([bankOre] clears [bankOpening] the moment the bank is actually open.) */
    private fun openBank(banking: BankingService): Long {
        if (bankOpening) {
            // Keep waiting while we're still WALKING to the chest (a direct click across the room walks-to-interact,
            // which can outlast the timeout); only once stopped-and-still-closed do we treat it as a missed click.
            val moving = ctx.players().localPlayer()?.isMoving ?: false
            if (moving || System.currentTimeMillis() - bankClickedAt < ACTION_RETRY_MS) return snap(300, 900) // opening — wait
            bankOpening = false // didn't open in time → the click missed; fall through and retry
        }
        val chest = bankChest() ?: return if (banking.openNearest()) snap(400, 900) else snap(700, 1500)
        mark(chest, Color(0x33, 0x99, 0xFF), "Bank")
        // DIRECT click first ("Use" is its default) — the game walks-to-interact when it's on-screen. Walk to its
        // anchor only as a FALLBACK when the click can't land (culled/occluded).
        if (chest.leftClickIfDefault("Use")) {
            bankOpening = true; bankClickedAt = System.currentTimeMillis()
            return snap(400, 1000)
        }
        return reveal(chest, BANK_CHEST_TILE)
    }

    /** Climb the ladder DOWN to the lower floor (the sack + bank live there) before draining/repairing. */
    private fun descend(): Long = ctx.profiler().section("miner/mlm-descend") { climb("climbing down") }

    /** Climb the ladder in whichever direction our floor dictates, LATCHED on the real floor-change signal.
     *  After issuing a Climb we set [climbing] and wait until [onUpperFloor] flips — we never re-click the ladder
     *  mid-climb, which is what made it climb straight back up and then miss the strut on the floor below. A
     *  missed approach (ladder out of range) just walks to it; the latch is only set once a Climb is issued. */
    private fun climb(status: String): Long {
        if (climbing) {
            if (onUpperFloor() != climbFromUpper) { climbing = false; return snap(300, 700) } // floor flipped — done
            if (System.currentTimeMillis() - climbClickedAt < ACTION_RETRY_MS) return snap(500, 1100) // climbing — wait
            climbing = false // floor never flipped → the Climb was missed/eaten (mid-mine, or the ladder was
                             // occluded by the vein) — fall through and re-issue instead of latching forever.
        }
        val ladder = ladder()
        if (ladder == null || ladder.distance() > CLICK_RANGE) {
            stats.status = "walking"; walkNear(LADDER_TILE); return snap(300, 1200)
        }
        stats.status = status
        mark(ladder, Color.CYAN, status)
        climbFromUpper = onUpperFloor()
        // PRECISE click: the ladder can sit behind a vein (whose "Mine" wins a plain hover), so a plain
        // leftClickIfDefault silently fails; interactPrecise rotates it into view and locks its own clickbox.
        if (ladder.interactPrecise("Climb")) { climbing = true; climbClickedAt = System.currentTimeMillis() }
        return snap(1500, 2800)
    }

    private fun repair(): Long = ctx.profiler().section("miner/mlm-repair") {
        closeStrayBank()?.let { return@section it }
        // Already fixed (maybe by another player) → the broken-strut object is gone, so there's nothing to do.
        val strut = strut() ?: run { lastWashMs = System.currentTimeMillis(); return@section snap(400, 900) }
        mark(strut, Color.RED, "Repair")
        strut.tile()?.let { approach(strut, it)?.let { w -> return@section w } }
        lastWashMs = System.currentTimeMillis() // we're acting on it — give the wheel time to wash before re-judging
        stats.status = "repairing wheel"
        // "Hammer" is the broken strut's DEFAULT action → left-click it. Using leftClickIfDefault (not the
        // right-click menu) means that if it just got fixed, this is a clean no-op instead of a ~10s
        // right-click-retry loop hunting for a "Hammer" option that no longer exists.
        leftOrMenu(strut, "Hammer")
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

    /** Left-click [action] when it's the entity's live default, else the right-click menu. */
    private fun leftOrMenu(entity: SceneEntity, action: String) {
        if (!entity.leftClickIfDefault(action)) entity.interact(action)
    }

    /** Walk (LOCALLY) toward [anchor] until [obj] is close enough to click reliably, returning the wait; null
     *  once in range. The fixed MLM objects (sack/hopper/bank/ladder/strut) are approached from across the room,
     *  where a click silently no-ops (the clickbox is culled/tiny off-screen) — so close the distance FIRST with
     *  a local single-scene step (correct for both overlaid floors), then a plain left-click lands. */
    private fun approach(obj: SceneEntity, anchor: Tile): Long? {
        if (obj.distance() <= CLICK_RANGE) return null
        stats.status = "walking"
        walkNear(anchor)
        return snap(300, 900)
    }

    /** FALLBACK for a fixed object we couldn't left-click directly (culled / off-screen / occluded): if it's still
     *  far, step toward its [anchor] to bring it into the scene; if we're already beside it, rotate it into view.
     *  Either way we retry the direct click next loop — so the common case (on-screen) never walks needlessly. */
    private fun reveal(obj: SceneEntity, anchor: Tile): Long {
        stats.status = "walking"
        if (obj.distance() > CLICK_RANGE) walkNear(anchor) else ctx.camera().rotateToObject(obj)
        return snap(300, 900)
    }

    /** Walk (LOCALLY) toward [tile], first resolving to the nearest WALKABLE tile — the fixed MLM objects
     *  (sack/hopper/bank/ladder) sit on UNwalkable tiles, and a local step to an unwalkable dest just fails
     *  (that stalled the whole drain). [reachableNear] returns the closest reachable tile to the target. */
    private fun walkNear(tile: Tile) { ctx.walking().walkStep(ctx.walking().reachableNear(tile)) }

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

    /** The minable ore vein we're ACTUALLY working, resolved by which tile we're FACING. The client turns us to
     *  face the vein we mine, so [Player.facingTile] pinpoints it even when veins sit on BOTH sides of us (both
     *  orthogonally adjacent — the old nearest-adjacent guess could highlight the wrong one). Falls back to any
     *  orthogonally-adjacent minable vein when the faced tile has none (e.g. mid-turn). Null if none adjacent. */
    private fun workedVein(): SceneEntity? {
        val player = ctx.players().localPlayer() ?: return null
        val me = player.tile() ?: return null
        val faced = player.facingTile()
        val adjacent = ctx.objects().query().id(*ORE_VEINS).withAction("Mine").within(1).sortNearest().list()
            .filter { v ->
                val t = v.tile() ?: return@filter false
                kotlin.math.abs(t.x - me.x) + kotlin.math.abs(t.y - me.y) == 1 && sameFloor(v.tileHeight())
            }
        return adjacent.firstOrNull { it.tile() == faced } ?: adjacent.firstOrNull()
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

        /** Where to mine on the upper floor — a real, reachable standing tile inside the upper vein cluster
         *  (the old (3755,5679) was walled off / not walkable, so the reachability-based trapped check false-
         *  fired up top). The ladder is the floor's exit. */
        val UPPER_ANCHOR = Tile(3761, 5668, 0)
        val LADDER_TILE = Tile(3755, 5673, 0)

        /** The two hoppers (one per floor) — walked TO when out of interact range so we always reach one instead
         *  of stalling near the vein anchor. Verified live (findobj "hopper"). */
        val BANK_CHEST_TILE = Tile(3761, 5666, 0)   // MLM bank chest (approach tile)
        val HOPPER_TILE = Tile(3748, 5672, 0)       // lower floor
        val UPPER_HOPPER_TILE = Tile(3755, 5677, 0) // upper floor

        // net.runelite.api.gameval.ObjectID
        val ORE_VEINS = intArrayOf(26661, 26662, 26663, 26664) // MOTHERLODE_ORE_SINGLE/LEFT/MIDDLE/RIGHT
        val ROCKFALL = intArrayOf(26679, 26680)                 // "Rockfall" ("Mine") — corridor blockers, minable
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
        const val CLICK_RANGE = 6 // walk this close to a fixed object before clicking (farther = culled, click no-ops)
        const val ACTION_RETRY_MS = 3500L // a latched Search/bank-open that hasn't taken effect by now missed → retry
        const val ARRIVE_RADIUS = 5 // "arrived" at the anchor when this close
        // Beyond this from the anchor we're not in the mine at all → web-walk IN. Larger than the whole cavern
        // (both floors sit well within ~50 tiles of ANCHOR), so in-mine navigation never trips it.
        const val MINE_ENTRY_RADIUS = 80
        // A vein is mined for several seconds per pay-dirt; the swing animation dips to idle briefly between
        // swings. Only treat idle THIS long as "actually stopped" (needs a re-click) — shorter than this is a
        // normal between-swing dip and must NOT re-click, or we spam the same vein.
        const val VEIN_IDLE_DEBOUNCE_MS = 1800L
        const val TRAP_CONFIRM = 2 // consecutive "anchor unreachable" reads before we treat ourselves as boxed in
        const val STUCK_LOOPS = 4  // consecutive fully-idle mining loops before we drop the vein and re-pick
        const val DEPOSIT_STUCK_MS = 5000L // no wash for this long (both struts broken) ⇒ the wheel's down, fix it
        const val WASH_STALE_MS = 9000L    // no wash this long + wheel NOT down ⇒ pending estimate is stale, clear it
        const val SACK_RESERVE = 1 // treat the sack full 1 early so a whole deposited load always fits
        const val INV_SLOTS = 28 // a full inventory (items() returns filled slots)

        const val PAY_DIRT = "Pay-dirt"
        const val NUGGET = "Golden nugget"
        val MLM_ORES = listOf(
            "Runite ore", "Adamantite ore", "Mithril ore", "Gold ore", "Coal", "Silver ore", "Iron ore",
        )

        /** Everything the sack yields (ores + nuggets) — the "we have something to bank" set. */
        val MLM_ORE_SET = (MLM_ORES + NUGGET).toHashSet()

        /** Same, as a list — used to inject a "Deposit-All" left-click default per name while banking. */
        val MLM_ORE_NAMES = MLM_ORES + NUGGET

        /** Refs to bank (ore + nuggets only) — deposited so the pickaxe, hammer and pay-dirt are kept. */
        val ORE_REFS = (MLM_ORES + NUGGET).map { ItemRef.ByName(it) }.toTypedArray()
    }
}
