package io.osrsx.plugins.skilling

import io.osrsx.api.ItemRef
import io.osrsx.api.PluginContext
import io.osrsx.api.RestockSpec
import io.osrsx.api.Skill
import io.osrsx.api.section
import io.osrsx.plugin.Plugin
import io.osrsx.util.Rng

/**
 * Keeps the best usable pickaxe in hand — the self-contained (no skilling-lib) replacement for the old
 * `ToolManager`, built on the declarative **Loadout** API instead of the `Toolbelt`. We rank the pickaxe
 * ladder by the player's Mining level ourselves, then hand the acquisition to [io.osrsx.api.Loadouts]:
 * `apply` deposits/withdraws through the synthetic bank and, when the player owns no pickaxe at all, buys a
 * modest metal tier off the GE (never an expensive dragon/crystal — those are only used if already owned).
 *
 * Drive it one step per loop before mining. [ensure] returns:
 *  - `null`  → a usable pickaxe is in hand (wielded where the Attack level allows) — proceed to mine;
 *  - `> 0`   → busy acquiring/wielding it this loop — return this ms delay from `onLoop`;
 *  - `< 0`   → no usable pickaxe exists at all (never happens — bronze needs level 1) — stop.
 */
class Pickaxe(
    private val ctx: PluginContext,
    private val wantHammer: () -> Boolean = { false },
    private val wearProspector: () -> Boolean = { false },
) {

    /** One pickaxe tier: its GE item [id], the Mining level to MINE with it, and whether it's a cheap metal
     *  tier we're willing to buy off the GE when the player owns nothing. */
    private data class Tier(val id: Int, val mineLevel: Int, val buyable: Boolean)

    /** The pickaxe ladder, best-first (highest Mining requirement first). Dragon/crystal/infernal/gilded are
     *  used when owned but never auto-bought. */
    private val tiers = listOf(
        Tier(CRYSTAL, 71, buyable = false),
        Tier(DRAGON, 61, buyable = false),
        Tier(INFERNAL, 61, buyable = false),
        Tier(GILDED, 41, buyable = false),
        Tier(RUNE, 41, buyable = true),
        Tier(ADAMANT, 31, buyable = true),
        Tier(MITHRIL, 21, buyable = true),
        Tier(BLACK, 11, buyable = true),
        Tier(STEEL, 6, buyable = true),
        Tier(IRON, 1, buyable = true),
        Tier(BRONZE, 1, buyable = true),
    )

    private var wieldTries = 0

    /** Consecutive loops the gear has looked UNMET — debounces a transient empty inventory read so one glitchy
     *  loop can't trigger a bank/GE trip (and a ring-of-wealth teleport) for tools we're actually holding. */
    private var gearUnmetStreak = 0

    /** Set once we've opened the bank and equipped every Prospector piece we own — so we don't keep reopening
     *  the bank chasing pieces we simply don't have (we can't tell "not owned" from "not seen" while it's shut). */
    private var prospectorChecked = false

    /** Drive one step toward holding — and, when possible, wielding — the best usable pickaxe. */
    fun ensure(): Long? = ctx.profiler().section("miner/gear") {
        val level = ctx.skills().real(Skill.MINING)
        val usable = tiers.filter { it.mineLevel <= level } // best-first, level-appropriate
        if (usable.isEmpty()) return@section Plugin.NO_LOOP

        // Read each container ONCE (a single client-thread hop) and match ids locally, instead of a
        // contains() per tier per container — the latter cost ~40 marshalls every loop.
        val invIds = ctx.inventory().items().mapTo(HashSet()) { it.id }
        val equipIds = ctx.equipment().items().mapTo(HashSet()) { it.id }

        val wornPickaxe = usable.any { it.id in equipIds }
        val carried = usable.firstOrNull { it.id in invIds }
        val hasPickaxe = wornPickaxe || carried != null
        // For Motherlode we always keep a HAMMER too (to fix a stopped water wheel without a bank trip).
        val hasHammer = !wantHammer() || HAMMER in invIds || HAMMER in equipIds
        // Prospector outfit (a Mining-XP boost): "done" when the option is off, every piece is already worn, or
        // we've already checked the bank and equipped whatever we own (so we don't reopen it for pieces we lack).
        val outfitDone = !wearProspector() || prospectorChecked || PROSPECTOR.all { it in equipIds }

        if (hasPickaxe && hasHammer && outfitDone) {
            gearUnmetStreak = 0
            // Geared. Wield a carried pickaxe (a few tries — too-low Attack can't, and it still mines from the
            // inventory), else we're already set.
            if (wornPickaxe) wieldTries = 0
            else if (carried != null && wieldTries < MAX_WIELD_TRIES) { ctx.equipment().equip(carried.id); wieldTries++ }
            return@section null
        }

        // Requirements look unmet — but a TRANSIENT container read can lie. Right after a hot-reload / on a
        // client-thread hiccup, `inventory().items()` briefly returns [] (equipment read fine), so a held
        // hammer/pickaxe reads as missing. Acting on that one glitchy loop fires a full bank + GE trip that
        // TELEPORTS there and spends a ring of wealth — for gear we already have. So require the deficit to
        // PERSIST for a few consecutive checks before touching the bank; a one-loop glitch can't trigger it.
        if (++gearUnmetStreak < GEAR_CONFIRM) return@section Rng.uniform(200, 500)

        // The Prospector pieces live in the bank, so to equip them we must SEE the bank. If the pickaxe + hammer
        // are already fine and ONLY the outfit is missing, the loadout below would satisfy without opening the
        // bank — so open it here first, then the ownership scan includes bank pieces.
        val bankOpen = ctx.bank().isOpen()
        if (hasPickaxe && hasHammer && !outfitDone && !bankOpen) {
            ctx.bank().openNearest()
            return@section Rng.uniform(400, 800)
        }

        // Equip ONLY Prospector pieces we actually OWN (worn / carried / in the open bank). Adding an equip for a
        // piece we don't have would leave the loadout permanently unsatisfiable (it can never be worn) and loop.
        val bankIds = if (bankOpen) ctx.bank().items().mapTo(HashSet()) { it.id } else emptySet()
        val ownedProspector =
            if (wearProspector()) PROSPECTOR.filter { it in invIds || it in equipIds || it in bankIds } else emptyList()

        // Missing the pickaxe and/or hammer → reconcile via a loadout: withdraw an owned tier from the bank
        // (buying the best cheap metal tier only if none owned), plus a hammer when we keep one, plus any owned
        // Prospector pieces as worn equips.
        val buyTarget = usable.firstOrNull { it.buyable } ?: usable.last()
        val loadout = ctx.loadouts().build("Miner gear") {
            item(
                ItemRef.AnyOf(usable.map { it.id }),
                quantity = 1,
                minimum = 1,
                restock = RestockSpec(ItemRef.ById(buyTarget.id), 1, markupPercent = 10),
            )
            if (wantHammer()) item(ItemRef.ById(HAMMER), quantity = 1, minimum = 1, restock = RestockSpec(ItemRef.ById(HAMMER), 1, 10))
            ownedProspector.forEach { equip(ItemRef.ById(it)) } // worn set — withdrawn from the bank if not already on
        }
        if (ctx.loadouts().apply(loadout)) {
            // Satisfied. If we saw the bank this trip (or the outfit's fully worn), we've equipped every piece we
            // own — mark checked so we don't keep reopening the bank chasing pieces we don't have.
            if (bankOpen || PROSPECTOR.all { it in equipIds }) prospectorChecked = true
            return@section null
        }
        Rng.uniform(400, 800)
    }

    private companion object {
        const val MAX_WIELD_TRIES = 4
        /** How many consecutive "gear unmet" reads to require before touching the bank — filters a transient
         *  empty inventory read (which would otherwise trigger a needless, ring-spending gear trip). */
        const val GEAR_CONFIRM = 3
        const val HAMMER = 2347 // ItemID.HAMMER — kept for Motherlode water-wheel repairs

        /** Prospector kit (MOTHERLODE_REWARD_* — helmet/jacket/legs/boots): a Mining-XP boost worn as a set.
         *  (Golden nugget itself is 12012.) Equipped only when owned; never bought. */
        val PROSPECTOR = intArrayOf(12013, 12014, 12015, 12016)

        // Pickaxe GE item ids (net.runelite.api.gameval.ItemID).
        const val BRONZE = 1265
        const val IRON = 1267
        const val STEEL = 1269
        const val BLACK = 12297
        const val MITHRIL = 1273
        const val ADAMANT = 1271
        const val RUNE = 1275
        const val GILDED = 23276
        const val DRAGON = 11920
        const val INFERNAL = 13243
        const val CRYSTAL = 23680
    }
}
