package cn.kasuminova.astd.campaign

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SpecialItemSpecAPI
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipHullSpecAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.FighterWingSpecAPI
import com.fs.starfarer.api.loading.HullModSpecAPI
import com.fs.starfarer.api.loading.WeaponSpecAPI

/**
 * Dev-only content filter for the test storage.
 *
 * Hidden/codex visibility is intentionally not a reject condition. The storage is for debugging
 * full mod setups, while still avoiding implementation-only content such as ship modules,
 * decorative weapons and ship-system weapons.
 */
object ASTDDevContentSelector {

    data class ShipRow(
        val id: String,
        val codexVariantId: String,
        val hints: Set<String>,
        val logisticsNaReason: String,
    )

    data class ShipSpecView(
        val id: String,
        val hullSize: ShipAPI.HullSize?,
        val hints: Set<String>,
        val logisticsNaReason: String,
    )

    data class WeaponRow(
        val id: String,
        val type: String,
        val hints: Set<String>,
        val tags: Set<String>,
        val groupTag: String,
        val primaryRoleStr: String,
        val noDpsInTooltip: Boolean,
    )

    data class SpecialItemRow(
        val id: String,
        val tags: Set<String>,
        val plugin: String,
        val params: String,
    )

    data class CommodityRow(
        val id: String,
        val tags: Set<String>,
        val nonEcon: Boolean,
        val meta: Boolean,
    )

    data class VariantView(
        val id: String,
        val hull: ShipSpecView?,
        val availableWeaponSlotIds: Set<String>,
        val fittedWeaponsBySlot: Map<String, String>,
        val wingIds: List<String>,
        val hasStationModules: Boolean,
    )

    data class VariantValidationResult(
        val accepted: Boolean,
        val reason: String = "",
    )

    fun isDevStorageShip(row: ShipRow): Boolean {
        if (row.id.isBlank()) return false
        if (row.hints.containsAny(SHIP_REJECT_HINTS)) return false
        if (row.logisticsNaReason.trim().isNotEmpty()) return false
        return true
    }

    fun isDevStorageShip(spec: ShipHullSpecAPI): Boolean {
        return isDevStorageShip(
            ShipSpecView(
                id = spec.hullId,
                hullSize = spec.hullSize,
                hints = spec.hints?.mapTo(mutableSetOf()) { it.name } ?: emptySet(),
                logisticsNaReason = spec.logisticsNAReason.orEmpty(),
            )
        )
    }

    fun isDevStorageShip(spec: ShipSpecView): Boolean {
        if (spec.id.isBlank()) return false
        if (spec.hints.containsAny(SHIP_REJECT_HINTS)) return false
        if (spec.hullSize == ShipAPI.HullSize.FIGHTER) return false
        if (spec.logisticsNaReason.isNotBlank()) return false
        return true
    }

    fun validateDevStorageVariant(
        variant: VariantView,
        weaponExists: (String) -> Boolean,
        fighterWingExists: (String) -> Boolean,
    ): VariantValidationResult {
        if (variant.id.isBlank()) return rejectedVariant("blank variant id")
        val hull = variant.hull ?: return rejectedVariant("${variant.id}: missing hull")
        if (!isDevStorageShip(hull)) return rejectedVariant("${variant.id}: rejected hull ${hull.id}")
        if (variant.hasStationModules) return rejectedVariant("${variant.id}: station/module variant")

        val availableSlots = variant.availableWeaponSlotIds
            .mapTo(mutableSetOf()) { it.trim() }
            .filterTo(mutableSetOf()) { it.isNotEmpty() }

        for ((rawSlotId, rawWeaponId) in variant.fittedWeaponsBySlot) {
            val slotId = rawSlotId.trim()
            val weaponId = rawWeaponId.trim()
            if (slotId.isBlank()) return rejectedVariant("${variant.id}: blank weapon slot")
            if (!availableSlots.contains(slotId)) {
                return rejectedVariant("${variant.id}: weapon slot $slotId not found on hull ${hull.id}")
            }
            if (weaponId.isBlank()) return rejectedVariant("${variant.id}: blank weapon id in slot $slotId")
            if (!weaponExists(weaponId)) return rejectedVariant("${variant.id}: missing weapon $weaponId in slot $slotId")
        }

        for (rawWingId in variant.wingIds) {
            val wingId = rawWingId.trim()
            if (wingId.isBlank()) continue
            if (!fighterWingExists(wingId)) return rejectedVariant("${variant.id}: missing fighter wing $wingId")
        }

        return acceptedVariant()
    }

    fun validateDevStorageVariant(variant: ShipVariantAPI): VariantValidationResult {
        val id = variant.hullVariantId.orEmpty().ifBlank { variant.displayName.orEmpty() }
        val hull = variant.hullSpec ?: return rejectedVariant("${id.ifBlank { "<unknown>" }}: missing hull")
        val hullView = ShipSpecView(
            id = hull.hullId,
            hullSize = hull.hullSize,
            hints = hull.hints?.mapTo(mutableSetOf()) { it.name } ?: emptySet(),
            logisticsNaReason = hull.logisticsNAReason.orEmpty(),
        )
        val availableSlots = hull.allWeaponSlotsCopy
            ?.mapNotNullTo(mutableSetOf()) { it?.id }
            ?: emptySet()
        val fittedWeapons = mutableMapOf<String, String>()
        for (slotId in variant.fittedWeaponSlots.orEmpty()) {
            val weaponId = variant.getWeaponId(slotId).orEmpty()
            fittedWeapons[slotId] = weaponId
        }

        return validateDevStorageVariant(
            VariantView(
                id = id,
                hull = hullView,
                availableWeaponSlotIds = availableSlots,
                fittedWeaponsBySlot = fittedWeapons,
                wingIds = variant.fittedWings.orEmpty(),
                hasStationModules = variant.stationModules?.isNotEmpty() == true || variant.moduleSlots?.isNotEmpty() == true,
            ),
            weaponExists = { weaponId -> getWeaponSpecOrNull(weaponId) != null },
            fighterWingExists = { wingId -> getFighterWingSpecOrNull(wingId) != null },
        )
    }

    fun isDevStorageWeapon(row: WeaponRow): Boolean {
        if (row.id.isBlank()) return false
        if (row.type.isOneOf(WEAPON_REJECT_TYPES)) return false
        if (row.hints.containsAny(WEAPON_REJECT_HINTS)) return false
        if (row.tags.containsAny(WEAPON_REJECT_TAGS)) return false
        if (row.primaryRoleStr.contains(SYSTEM_LINKED_ROLE, ignoreCase = true)) return false
        return true
    }

    fun isDevStorageWeapon(spec: WeaponSpecAPI): Boolean {
        val id = spec.weaponId ?: return false
        if (id.isBlank()) return false
        val typeName = spec.type?.name ?: spec.mountType?.name.orEmpty()
        if (typeName.isOneOf(WEAPON_REJECT_TYPES)) return false
        val mountTypeName = spec.mountType?.name.orEmpty()
        if (mountTypeName.isOneOf(WEAPON_REJECT_TYPES)) return false
        val hints = spec.aiHints?.mapTo(mutableSetOf()) { it.name } ?: emptySet()
        if (hints.containsAny(WEAPON_REJECT_HINTS)) return false
        val tags = spec.tags?.mapTo(mutableSetOf()) { it } ?: emptySet()
        if (tags.containsAny(WEAPON_REJECT_TAGS)) return false
        if (spec.primaryRoleStr?.contains(SYSTEM_LINKED_ROLE, ignoreCase = true) == true) return false
        return true
    }

    fun isDevStorageSpecialItem(row: SpecialItemRow): Boolean {
        if (row.id.isBlank()) return false
        if (row.tags.containsAny(SPECIAL_ITEM_REJECT_TAGS)) return false
        if (row.tags.containsAny(SPECIAL_ITEM_HIDDEN_TAGS)) return false
        if (row.plugin.isOneOf(SPECIAL_ITEM_STATEFUL_PLUGINS) && row.params.isBlank()) return false
        if (row.tags.containsAny(SPECIAL_ITEM_PARAM_REQUIRED_TAGS) && row.params.isBlank()) return false
        if (row.id.isOneOf(SPECIAL_ITEM_PARAM_REQUIRED_IDS) && row.params.isBlank()) return false
        return true
    }

    fun isDevStorageSpecialItem(spec: SpecialItemSpecAPI): Boolean {
        val id = spec.id ?: return false
        if (id.isBlank()) return false
        val tags = spec.tags?.mapTo(mutableSetOf()) { it } ?: emptySet()
        if (tags.containsAny(SPECIAL_ITEM_REJECT_TAGS)) return false
        if (tags.containsAny(SPECIAL_ITEM_HIDDEN_TAGS)) return false
        val params = spec.params.orEmpty()
        if (tags.containsAny(SPECIAL_ITEM_PARAM_REQUIRED_TAGS) && params.isBlank()) return false
        if (id.isOneOf(SPECIAL_ITEM_PARAM_REQUIRED_IDS) && params.isBlank()) return false
        return true
    }

    fun isDevStorageCommodity(row: CommodityRow): Boolean {
        if (row.id.isBlank()) return false
        if (row.meta) return false
        if (row.tags.containsAny(COMMODITY_REJECT_TAGS)) return false
        return true
    }

    fun isDevStorageCommodity(spec: CommoditySpecAPI): Boolean {
        val id = spec.id ?: return false
        if (id.isBlank()) return false
        if (spec.isMeta) return false
        val tags = spec.tags?.mapTo(mutableSetOf()) { it } ?: emptySet()
        if (tags.containsAny(COMMODITY_REJECT_TAGS)) return false
        return true
    }

    fun isDevStorageFighterWing(spec: FighterWingSpecAPI): Boolean {
        val id = spec.id ?: return false
        if (id.isBlank()) return false
        val tags = spec.tags?.mapTo(mutableSetOf()) { it } ?: emptySet()
        if (tags.containsAny(FIGHTER_REJECT_TAGS)) return false
        return true
    }

    fun isDevStorageHullMod(spec: HullModSpecAPI): Boolean {
        val id = spec.id ?: return false
        if (id.isBlank()) return false
        if (spec.isHidden || spec.isHiddenEverywhere) return false
        val tags = spec.tags?.mapTo(mutableSetOf()) { it } ?: emptySet()
        if (tags.containsAny(HULLMOD_REJECT_TAGS)) return false
        return true
    }

    private fun Set<String>.containsAny(rejected: Set<String>): Boolean {
        return any { value -> rejected.any { value.equals(it, ignoreCase = true) } }
    }

    private fun String.isOneOf(rejected: Set<String>): Boolean {
        return rejected.any { equals(it, ignoreCase = true) }
    }

    private fun acceptedVariant(): VariantValidationResult {
        return VariantValidationResult(accepted = true)
    }

    private fun rejectedVariant(reason: String): VariantValidationResult {
        return VariantValidationResult(accepted = false, reason = reason)
    }

    private fun getWeaponSpecOrNull(id: String): WeaponSpecAPI? {
        return try {
            Global.getSettings().getWeaponSpec(id)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getFighterWingSpecOrNull(id: String): FighterWingSpecAPI? {
        return try {
            Global.getSettings().getFighterWingSpec(id)
        } catch (_: Throwable) {
            null
        }
    }

    private const val SYSTEM_LINKED_ROLE = "系统联动"

    private val SHIP_REJECT_HINTS = setOf(
        ShipHullSpecAPI.ShipTypeHints.MODULE.name,
        ShipHullSpecAPI.ShipTypeHints.UNDER_PARENT.name,
        ShipHullSpecAPI.ShipTypeHints.STATION.name,
        ShipHullSpecAPI.ShipTypeHints.SHIP_WITH_MODULES.name,
        ShipHullSpecAPI.ShipTypeHints.UNBOARDABLE.name,
    )

    private val WEAPON_REJECT_TYPES = setOf(
        WeaponAPI.WeaponType.DECORATIVE.name,
        WeaponAPI.WeaponType.SYSTEM.name,
        WeaponAPI.WeaponType.BUILT_IN.name,
        WeaponAPI.WeaponType.STATION_MODULE.name,
        "OTHER",
    )

    private val WEAPON_REJECT_HINTS = setOf(
        WeaponAPI.AIHints.SYSTEM.name,
    )

    private val WEAPON_REJECT_TAGS = setOf(
        "decorative",
        "system",
        "system_weapon",
        "station_module",
    )

    private val SPECIAL_ITEM_REJECT_TAGS = setOf(
        "meta",
        "internal",
    )

    private val SPECIAL_ITEM_HIDDEN_TAGS = setOf(
        "hide_in_codex",
        "hidden",
    )

    private val SPECIAL_ITEM_PARAM_REQUIRED_TAGS = setOf(
        "modspec",
        "single_bp",
    )

    private val SPECIAL_ITEM_PARAM_REQUIRED_IDS = setOf(
        "modspec",
        "ship_bp",
        "weapon_bp",
        "fighter_bp",
        "industry_bp",
    )

    private val SPECIAL_ITEM_STATEFUL_PLUGINS = setOf(
        "com.fs.starfarer.api.campaign.impl.items.WormholeAnchorPlugin",
    )

    private val COMMODITY_REJECT_TAGS = setOf(
        "meta",
    )

    private val FIGHTER_REJECT_TAGS = setOf(
        "no_drop",
        "no_sell",
        "restricted",
        "internal",
        "decorative",
        "system",
    )

    private val HULLMOD_REJECT_TAGS = setOf(
        "built_in",
        "builtin",
        "no_drop",
        "hidden",
        "internal",
    )
}
