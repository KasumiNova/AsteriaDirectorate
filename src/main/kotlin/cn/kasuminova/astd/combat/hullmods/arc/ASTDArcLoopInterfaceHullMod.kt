package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.shipsystems.ASTDArcFlareOverdriveSystemStats
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

class ASTDArcLoopInterfaceHullMod : BaseHullMod() {

    companion object {
        private const val ENERGY_PROJ_SPEED_MULT = 1.20f   // +20%
        private const val ENERGY_FLUX_MULT = 0.85f          // -15%
        private const val WEAPON_TURN_MULT = 1.30f          // +30%

        /** 弹匣恢复速率加成 = 武器射速加成 / 2（每 2% 额外射速 → 1% 弹匣恢复）。 */
        private const val MAGAZINE_RELOAD_PER_ROF = 0.5f
        private const val MAGAZINE_BOOST_ID = "astd_arc_loop_magazine_from_rof"

        // 非导弹武器 OP 折扣：小 -2, 中 -4, 大 -8
        private const val SMALL_OP_DISCOUNT = -2f
        private const val MEDIUM_OP_DISCOUNT = -4f
        private const val LARGE_OP_DISCOUNT = -8f

        private const val BOOST_ID = "astd_arc_loop_system_boost"

        private val THEME = ASTDArcFlareHullModTooltip.Theme(
            nameColor = Color(150, 232, 255),
            borderColor = Color(90, 180, 255),
            headerBackground = Color(20, 52, 82, 180),
            sectionBackground = Color(14, 36, 58, 120),
            accentColor = Color(60, 140, 220),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        if (!variant.isASTDArcFlareVariant()) return

        stats.energyProjectileSpeedMult.modifyMult(id, ENERGY_PROJ_SPEED_MULT)
        stats.energyWeaponFluxCostMod.modifyMult(id, ENERGY_FLUX_MULT)
        stats.weaponTurnRateBonus.modifyMult(id, WEAPON_TURN_MULT)

        // 非导弹武器 OP 折扣
        stats.dynamic.getMod("small_ballistic_mod").modifyFlat(id, SMALL_OP_DISCOUNT)
        stats.dynamic.getMod("small_energy_mod").modifyFlat(id, SMALL_OP_DISCOUNT)
        stats.dynamic.getMod("medium_ballistic_mod").modifyFlat(id, MEDIUM_OP_DISCOUNT)
        stats.dynamic.getMod("medium_energy_mod").modifyFlat(id, MEDIUM_OP_DISCOUNT)
        stats.dynamic.getMod("large_ballistic_mod").modifyFlat(id, LARGE_OP_DISCOUNT)
        stats.dynamic.getMod("large_energy_mod").modifyFlat(id, LARGE_OP_DISCOUNT)
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f || ship.isHulk) return

        val shipKey = System.identityHashCode(ship).toString()
        val boostKey = "${ASTDArcFlareOverdriveSystemStats.HULLMOD_BOOST_KEY}$shipKey"
        val boostLevel = (engine.customData[boostKey] as? Float ?: 0f).coerceIn(0f, 1f)

        val stats = ship.mutableStats
        if (boostLevel > 0.01f) {
            // 系统增幅：将回路接口效果翻倍（在基础之上再叠加同等幅度）
            stats.energyProjectileSpeedMult.modifyMult(BOOST_ID, 1f + (ENERGY_PROJ_SPEED_MULT - 1f) * boostLevel)
            stats.energyWeaponFluxCostMod.modifyMult(BOOST_ID, 1f + (ENERGY_FLUX_MULT - 1f) * boostLevel)
            stats.weaponTurnRateBonus.modifyMult(BOOST_ID, 1f + (WEAPON_TURN_MULT - 1f) * boostLevel)
        } else {
            stats.energyProjectileSpeedMult.unmodify(BOOST_ID)
            stats.energyWeaponFluxCostMod.unmodify(BOOST_ID)
            stats.weaponTurnRateBonus.unmodify(BOOST_ID)
        }

        // 弹匣恢复速率加成：取当前武器射速额外加成的一半
        val rofBonusBallistic = (stats.ballisticRoFMult.modifiedValue - 1f).coerceAtLeast(0f)
        val rofBonusEnergy = (stats.energyRoFMult.modifiedValue - 1f).coerceAtLeast(0f)
        val magBonusBallistic = rofBonusBallistic * MAGAZINE_RELOAD_PER_ROF
        val magBonusEnergy = rofBonusEnergy * MAGAZINE_RELOAD_PER_ROF
        if (magBonusBallistic > 0.001f || magBonusEnergy > 0.001f) {
            stats.ballisticAmmoRegenMult.modifyMult(MAGAZINE_BOOST_ID, 1f + magBonusBallistic)
            stats.energyAmmoRegenMult.modifyMult(MAGAZINE_BOOST_ID, 1f + magBonusEnergy)
            stats.missileAmmoRegenMult.modifyMult(MAGAZINE_BOOST_ID, 1f + ((magBonusBallistic + magBonusEnergy) * 0.5f))
        } else {
            stats.ballisticAmmoRegenMult.unmodify(MAGAZINE_BOOST_ID)
            stats.energyAmmoRegenMult.unmodify(MAGAZINE_BOOST_ID)
            stats.missileAmmoRegenMult.unmodify(MAGAZINE_BOOST_ID)
        }
    }

    override fun addPostDescriptionSection(tooltip: TooltipMakerAPI, hullSize: ShipAPI.HullSize, ship: ShipAPI?, width: Float, isForModSpec: Boolean) {
        ASTDArcFlareHullModTooltip.render(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            summaryKey = "ui.hullmod.arc_loop.summary",
            sections = listOf(
                ASTDArcFlareHullModTooltip.section(
                    "ui.hullmod.section.integration",
                    "ui.hullmod.arc_loop.line.1",
                    "ui.hullmod.arc_loop.line.2",
                    "ui.hullmod.arc_loop.line.3",
                ),
                ASTDArcFlareHullModTooltip.section(
                    "ui.hullmod.section.system",
                    "ui.hullmod.arc_loop.line.4",
                ),
            ),
            starTrails = false,
        )
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun affectsOPCosts(): Boolean = true

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isASTDArcFlareShip()

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor
}
