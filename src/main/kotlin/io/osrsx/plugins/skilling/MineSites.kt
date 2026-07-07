package io.osrsx.plugins.skilling

import io.osrsx.api.PluginContext
import io.osrsx.api.Skill
import io.osrsx.api.Tile

/**
 * The catalogue of named mining sites the [MinerPlugin] can travel to, and the live account checks that
 * decide which ones a player may actually use. This REPLACES the old name-based "nearest cluster" discovery
 * for mining: instead of guessing where the ore is, the miner walks to an explicit, curated anchor tile.
 *
 * Each [MineSite] carries the real in-game requirement to mine there — membership, a combat level (some
 * mines sit in aggressive/wilderness areas), and/or a Mining level (the Mining Guild). The plugin's Location
 * dropdown is filled live from [optionsFor], which HIDES any site the current account can't use, so a player
 * only ever sees locations they qualify for.
 */
enum class Ore(val display: String, val rockName: String?, val product: String?) {
    COPPER("Copper", "Copper rocks", "Copper ore"),
    TIN("Tin", "Tin rocks", "Tin ore"),
    IRON("Iron", "Iron rocks", "Iron ore"),
    COAL("Coal", "Coal rocks", "Coal"),

    /** Motherlode Mine — not a plain rock→ore loop; driven by [MotherlodeRoutine], so it has no rock/product. */
    MOTHERLODE("Motherlode", null, null);

    companion object {
        /** The enum whose [display] matches [value] (the stored config string), defaulting to [COPPER]. */
        fun fromDisplay(value: String): Ore = entries.firstOrNull { it.display == value } ?: COPPER

        /** The display names, in catalogue order — the "Ore" dropdown's options. */
        val displays: List<String> get() = entries.map { it.display }
    }
}

/**
 * One catalogued mining spot: an [ore] at an anchor [tile] the bot web-walks to, gated by the real
 * requirement to mine there. [id] is the short, stable key stored in config (e.g. `"VarrockSE"`).
 */
data class MineSite(
    val id: String,
    val ore: Ore,
    val tile: Tile,
    val members: Boolean = false,
    val combatReq: Int = 0,
    val miningReq: Int = 0,
) {
    /** The dropdown label: the id plus a compact requirement suffix, e.g. `"ArdyE (P2P, 43 cmb)"`. */
    fun label(): String {
        val parts = buildList {
            if (members) add("P2P")
            if (combatReq > 0) add("$combatReq cmb")
            if (miningReq > 0) add("$miningReq mining")
        }
        return if (parts.isEmpty()) id else "$id (${parts.joinToString(", ")})"
    }
}

object MineSites {

    /** The always-present bottom entry of the Location dropdown — resolved live to the best catalogued site
     *  (nearest the bank when banking, else nearest the player) by [resolveBest]. */
    const val BEST = "Auto — select best"

    // ---- The catalogue -------------------------------------------------------------------------------
    // Anchor tiles are the CENTRE of each rock cluster, computed from the offline object DB (~/.osrsx/data/
    // osrsx.db) and cross-checked against the OSRS Wiki, so the bot web-walks into the middle of the rocks.
    // Underground mines use their real cave coordinates (plane 0, large y). Requirements (members / combat /
    // Mining level) are the real in-game gates per (site, ore) — e.g. Varrock SE iron needs 13 combat
    // (scorpions) but its copper/tin do not.
    val SITES: List<MineSite> = listOf(
        // --- Copper ---
        MineSite("Dwarven", Ore.COPPER, Tile(3032, 9801, 0), combatReq = 65),
        MineSite("Hosidius", Ore.COPPER, Tile(1778, 3488, 0), members = true),
        MineSite("Piscatoris", Ore.COPPER, Tile(2338, 3643, 0), members = true),
        MineSite("Rimmington", Ore.COPPER, Tile(2977, 3247, 0)),
        MineSite("VarrockSE", Ore.COPPER, Tile(3287, 3362, 0)),
        // --- Tin ---
        MineSite("BarbVillage", Ore.TIN, Tile(3080, 3420, 0)),
        MineSite("Dwarven", Ore.TIN, Tile(3049, 9800, 0), combatReq = 65),
        MineSite("Hosidius", Ore.TIN, Tile(1777, 3489, 0), members = true),
        MineSite("Rimmington", Ore.TIN, Tile(2985, 3236, 0)),
        MineSite("VarrockSE", Ore.TIN, Tile(3282, 3364, 0)),
        MineSite("VarrockSW", Ore.TIN, Tile(3182, 3376, 0)),
        // --- Iron ---
        MineSite("ArdyE", Ore.IRON, Tile(2604, 3235, 0), members = true, combatReq = 43),
        MineSite("Desert1", Ore.IRON, Tile(3298, 3310, 0), combatReq = 29),
        MineSite("Desert2", Ore.IRON, Tile(3305, 3302, 0), combatReq = 29),
        MineSite("Dwarven", Ore.IRON, Tile(3041, 9800, 0), combatReq = 65),
        MineSite("GiantsPlateau", Ore.IRON, Tile(3092, 3768, 0)),
        MineSite("GuildF2P", Ore.IRON, Tile(3033, 9739, 0), miningReq = 60),
        MineSite("GuildP2P", Ore.IRON, Tile(3026, 9725, 0), members = true, miningReq = 60),
        MineSite("Keldagrim", Ore.IRON, Tile(2828, 10154, 0), members = true),
        MineSite("Rimmington", Ore.IRON, Tile(2973, 3238, 0)),
        MineSite("VarrockSE", Ore.IRON, Tile(3286, 3369, 0), combatReq = 13),
        MineSite("VarrockSW", Ore.IRON, Tile(3175, 3367, 0), combatReq = 13),
        // --- Coal ---
        MineSite("ArdyE", Ore.COAL, Tile(2606, 3224, 0), members = true, combatReq = 43),
        MineSite("BarbVillage", Ore.COAL, Tile(3082, 3421, 0)),
        MineSite("Dwarven", Ore.COAL, Tile(3044, 9788, 0), combatReq = 65),
        MineSite("GiantsPlateau", Ore.COAL, Tile(3086, 3762, 0)),
        MineSite("GuildF2P", Ore.COAL, Tile(3042, 9746, 0), miningReq = 60),
        MineSite("GuildP2P", Ore.COAL, Tile(3032, 9725, 0), members = true, miningReq = 60),
        MineSite("Lovakengj", Ore.COAL, Tile(1435, 3835, 0), members = true),
        MineSite("SwampW", Ore.COAL, Tile(3145, 3150, 0)),
    )

    // ---- Account eligibility (live) ------------------------------------------------------------------

    /**
     * Whether the current account may use [site] right now: members sites need a members world, and any
     * combat/Mining requirement must be met. Checks are *permissive when unknown* (e.g. the world list has
     * not loaded, or we are on the login screen with no local player) so nothing is wrongly hidden before
     * the game state is available — a known-too-low value is what hides a site.
     */
    fun eligible(site: MineSite, ctx: PluginContext): Boolean {
        if (site.members && isMembers(ctx) == false) return false
        val combat = ctx.players().localPlayer()?.combatLevel
        if (site.combatReq > 0 && combat != null && combat < site.combatReq) return false
        if (site.miningReq > 0 && ctx.skills().real(Skill.MINING) < site.miningReq) return false
        return true
    }

    /** True/false when the current world's membership is known, null while the world list is still loading. */
    fun isMembers(ctx: PluginContext): Boolean? {
        val worlds = ctx.worlds()
        val current = worlds.current()
        return worlds.list().firstOrNull { it.id == current }?.members
    }

    // ---- Dropdown wiring -----------------------------------------------------------------------------

    /** The catalogued sites for [ore] the account currently qualifies for (ineligible ones omitted). */
    fun eligibleSites(ore: Ore, ctx: PluginContext): List<MineSite> =
        SITES.filter { it.ore == ore && eligible(it, ctx) }

    /** The live "Location" dropdown contents for [ore]: every eligible site's [label], then [BEST]. */
    fun optionsFor(ore: Ore, ctx: PluginContext): List<String> =
        eligibleSites(ore, ctx).map { it.label() } + BEST

    /** The site for [ore] whose [label] equals the stored config value, or null if none matches. */
    fun byLabel(ore: Ore, label: String): MineSite? =
        SITES.firstOrNull { it.ore == ore && it.label() == label }

    /**
     * The best eligible site for [ore] to mine right now: the one nearest the bank when [banking] (shorter
     * bank round-trips), otherwise the one nearest the player. Null if the account qualifies for none.
     */
    fun resolveBest(ore: Ore, ctx: PluginContext, banking: Boolean): MineSite? {
        val sites = eligibleSites(ore, ctx)
        if (sites.isEmpty()) return null
        val anchor = (if (banking) ctx.webWalking().nearestBank() else null)
            ?: ctx.players().localPlayer()?.tile()
            ?: return sites.first()
        return sites.minByOrNull { it.tile.distanceTo(anchor) }
    }

    /**
     * Resolve the stored Location value to a concrete site: [BEST] (or an unknown/blank value) resolves via
     * [resolveBest]; any other value is matched by [label]. Null when nothing qualifies.
     */
    fun siteFor(ore: Ore, ctx: PluginContext, location: String, banking: Boolean): MineSite? =
        if (location == BEST || location.isBlank()) resolveBest(ore, ctx, banking)
        else byLabel(ore, location) ?: resolveBest(ore, ctx, banking)
}
