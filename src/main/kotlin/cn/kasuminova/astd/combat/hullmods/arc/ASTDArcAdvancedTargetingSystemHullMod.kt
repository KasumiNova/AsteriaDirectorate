package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.listeners.WeaponBaseRangeModifier
import com.fs.starfarer.api.impl.campaign.ids.HullMods
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

class ASTDArcAdvancedTargetingSystemHullMod : BaseHullMod() {

    companion object {
        private const val RANGE_PERCENT = 20f
        private const val SHORT_RANGE_THRESHOLD = 600f
        private const val SHORT_RANGE_MAX_FLAT = 150f

        private val INCOMPATIBLE_TARGETING_HULLMODS = setOf(
            "targetingunit",
            "integratedtargetingunit",
            "dedicated_targeting_core",
            "dedicatedtargetingcore",
            "advancedcore",
            "advancedoptics",
            HullMods.DISTRIBUTED_FIRE_CONTROL,
        )

        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(150, 232, 255),
            borderColor = Color(90, 180, 255),
            headerBackground = Color(20, 52, 82, 180),
            sectionBackground = Color(14, 36, 58, 120),
            accentColor = Color(60, 150, 230),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        stats.ballisticWeaponRangeBonus.modifyPercent(id, RANGE_PERCENT)
        stats.energyWeaponRangeBonus.modifyPercent(id, RANGE_PERCENT)
        stats.beamWeaponRangeBonus.modifyPercent(id, RANGE_PERCENT)
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        if (ship.isHulk) return
        if (!ship.hasListenerOfClass(ShortWeaponBaseRangeModifier::class.java)) {
            ship.addListener(ShortWeaponBaseRangeModifier())
        }
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean
    ) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = ASTDArcProductionTooltipContracts.arcAdvancedTargetingSystem.blocks,
        )
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean =
        !hasIncompatibleTargetingSystem(ship)

    override fun getUnapplicableReason(ship: ShipAPI): String? {
        if (hasIncompatibleTargetingSystem(ship)) {
            return I18n[I18n.Categories.MOD, "ui.hullmod.arc_advanced_targeting_system.unapplicable.targeting"]
        }
        return null
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    private fun hasIncompatibleTargetingSystem(ship: ShipAPI): Boolean {
        val variant = ship.variant ?: return false
        return INCOMPATIBLE_TARGETING_HULLMODS.any { variant.hasHullMod(it) }
    }

    private class ShortWeaponBaseRangeModifier : WeaponBaseRangeModifier {
        override fun getWeaponBaseRangePercentMod(ship: ShipAPI, weapon: WeaponAPI): Float = 0f

        override fun getWeaponBaseRangeMultMod(ship: ShipAPI, weapon: WeaponAPI): Float = 1f

        override fun getWeaponBaseRangeFlatMod(ship: ShipAPI, weapon: WeaponAPI): Float {
            if (!isEligibleWeapon(weapon)) return 0f
            val baseRange = weapon.spec?.maxRange ?: return 0f
            if (baseRange <= 0f || baseRange >= SHORT_RANGE_THRESHOLD) return 0f
            return (SHORT_RANGE_THRESHOLD - baseRange).coerceAtMost(SHORT_RANGE_MAX_FLAT)
        }

        private fun isEligibleWeapon(weapon: WeaponAPI): Boolean {
            if (weapon.isDecorative) return false
            return when (weapon.type) {
                WeaponAPI.WeaponType.MISSILE,
                WeaponAPI.WeaponType.LAUNCH_BAY,
                WeaponAPI.WeaponType.DECORATIVE,
                WeaponAPI.WeaponType.SYSTEM,
                WeaponAPI.WeaponType.STATION_MODULE -> false
                else -> true
            }
        }
    }
}
