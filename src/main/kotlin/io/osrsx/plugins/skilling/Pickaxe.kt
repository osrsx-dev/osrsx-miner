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
class Pickaxe(private val ctx: PluginContext) {

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

    /** Drive one step toward holding — and, when possible, wielding — the best usable pickaxe. */
    fun ensure(): Long? = ctx.profiler().section("miner/gear") {
        val level = ctx.skills().real(Skill.MINING)
        val usable = tiers.filter { it.mineLevel <= level } // best-first, level-appropriate
        if (usable.isEmpty()) return@section Plugin.NO_LOOP

        // Already holding or wearing a usable pickaxe → wield it (once) to free a slot, then ready.
        if (usable.any { ctx.inventory().contains(it.id) || ctx.equipment().contains(it.id) }) {
            wieldIfCarried(usable)
            return@section null
        }

        // Not held → reconcile via a loadout: prefer withdrawing an owned tier from the bank; when the player
        // owns none anywhere, buy the best *cheap metal* tier the level allows (guaranteed affordable).
        val buyTarget = usable.firstOrNull { it.buyable } ?: usable.last()
        val loadout = ctx.loadouts().build("Miner pickaxe") {
            item(
                ItemRef.AnyOf(usable.map { it.id }),
                quantity = 1,
                minimum = 1,
                restock = RestockSpec(ItemRef.ById(buyTarget.id), 1, markupPercent = 10),
            )
        }
        if (ctx.loadouts().apply(loadout)) null else Rng.uniform(400, 800)
    }

    /** Wield a carried pickaxe (up to [MAX_WIELD_TRIES] tries — a too-low Attack level can't wield it, and it
     *  still mines from the inventory, so we stop retrying). No-op once one is worn. */
    private fun wieldIfCarried(usable: List<Tier>) {
        if (usable.any { ctx.equipment().contains(it.id) }) { wieldTries = 0; return }
        val carried = usable.firstOrNull { ctx.inventory().contains(it.id) } ?: return
        if (wieldTries >= MAX_WIELD_TRIES) return
        ctx.equipment().equip(carried.id)
        wieldTries++
    }

    private companion object {
        const val MAX_WIELD_TRIES = 4

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
