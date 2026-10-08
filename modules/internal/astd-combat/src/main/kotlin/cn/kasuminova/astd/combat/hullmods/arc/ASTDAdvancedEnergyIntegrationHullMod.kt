package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTooltipSpec
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.hullmodTooltip
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

/**
 * 先进能量集成（XC-001 星坠内置船插）：能量武装的深度集成与装配优化。
 *
 * 效果（规格 blue/10-unique.md XC-001 节）：
 * - 能量射弹飞行速度 +20%；
 * - 能量武器辐能产生 −15%；
 * - 武器转向速度 +30%；
 * - 非导弹武器装配点消耗 −2 / −4 / −8（小/中/大槽，实弹+能量）。
 *
 * 纯装配期效果（applyEffectsBeforeShipCreation），无战斗中动态通道。
 */
class ASTDAdvancedEnergyIntegrationHullMod : BaseHullMod() {

    companion object {
        private const val ENERGY_PROJ_SPEED_MULT = 1.20f   // +20%
        private const val ENERGY_FLUX_MULT = 0.85f          // -15%
        private const val WEAPON_TURN_MULT = 1.30f          // +30%

        // 非导弹武器 OP 折扣：小 -2, 中 -4, 大 -8
        private const val SMALL_OP_DISCOUNT = -2f
        private const val MEDIUM_OP_DISCOUNT = -4f
        private const val LARGE_OP_DISCOUNT = -8f

        private val THEME = HullmodThemes.ARC

        /** tooltip 卡片声明（静态内容）。 */
        private val TOOLTIP: HullmodTooltipSpec = hullmodTooltip {
            para("ui.hullmod.aei.summary")
            heading("ui.hullmod.export.section.effect")
            table {
                row("ui.hullmod.aei.attr.op", "ui.hullmod.aei.value.op")
                row("ui.hullmod.aei.attr.flux", "ui.hullmod.aei.value.flux")
                row("ui.hullmod.aei.attr.projectile_speed", "ui.hullmod.aei.value.projectile_speed")
                row("ui.hullmod.aei.attr.turn_rate", "ui.hullmod.aei.value.turn_rate")
            }
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        if (!variant.isASTDXc001Variant()) return

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

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean
    ) {
        tooltip.hullmodCard(width, THEME, spec?.displayName, TOOLTIP)
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun affectsOPCosts(): Boolean = true

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isASTDXc001Ship()

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor
}
