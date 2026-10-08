package cn.kasuminova.astd.combat.hullmods.base

import cn.kasuminova.astd.ui.dsl.HullmodTheme
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTooltipSpec
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.hullmodTooltip
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

/**
 * 奇点能源（全 ASTD 非战机舰船内置船插，蓝 [ID_ARC] / 紫 [ID_LENS] 双变体共用本效果类，
 * 仅 spec 贴图与设计类型不同）。
 *
 * 四件效果（纯装配期 stat 写入，无战斗中动态通道）：
 * 1. 强制排辐速率 +20%——`ventRateMult` 乘区（项目术语口径：词缀体系「强制排辐速率」
 *    同走该 stat，见 AffixCryoFluxNetworkHullMod；原版中文译名为「主动排幅速率」，FluxBreakers 判例）。
 * 2. 过载持续时间 -20%——`overloadTimeMod` 乘区。
 * 3. 负面环境对战备值与峰值时间的影响 -100%——复刻原版环境抗性实现（SolarShielding 判例）：
 *    dynamic stat `corona_resistance` 乘 0，该值被原版日冕/耀斑/超空间风暴/脉冲星束地形
 *    （StarCoronaTerrainPlugin、HyperspaceTerrainPlugin 等）读取并缩放 CR 损失与峰值时间惩罚。
 * 4. 武器辐能产出 -10%——实弹/能量/导弹三类 `weaponFluxCostMod` 乘区。
 */
class ASTDSingularityPowerHullMod : BaseHullMod() {

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        stats.ventRateMult.modifyMult(id, VENT_RATE_MULT)
        stats.overloadTimeMod.modifyMult(id, OVERLOAD_TIME_MULT)
        stats.dynamic.getStat(Stats.CORONA_EFFECT_MULT).modifyMult(id, ENVIRONMENT_EFFECT_MULT)
        stats.ballisticWeaponFluxCostMod.modifyMult(id, WEAPON_FLUX_COST_MULT)
        stats.energyWeaponFluxCostMod.modifyMult(id, WEAPON_FLUX_COST_MULT)
        stats.missileWeaponFluxCostMod.modifyMult(id, WEAPON_FLUX_COST_MULT)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, theme(), spec?.displayName, TOOLTIP)
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = theme().borderColor

    override fun getNameColor(): Color = theme().nameColor

    private fun theme(): HullmodTheme =
        if (spec?.id == ID_LENS) THEME_LENS else THEME_ARC

    companion object {
        /** 蓝变体 id（菀星设计局-星坠，装配 astd_xc_* 与 astd_lh_001）。 */
        const val ID_ARC: String = "astd_arc_singularity_power"

        /** 紫变体 id（菀星设计局-紫菀，装配 astd_zw_* 与 astd_lh_002）。 */
        const val ID_LENS: String = "astd_lens_singularity_power"

        const val VENT_RATE_MULT: Float = 1.2f
        const val OVERLOAD_TIME_MULT: Float = 0.8f
        const val ENVIRONMENT_EFFECT_MULT: Float = 0f
        const val WEAPON_FLUX_COST_MULT: Float = 0.9f

        private val THEME_ARC = HullmodThemes.ARC

        private val THEME_LENS = HullmodThemes.LENS

        /** tooltip 卡片声明（静态内容；主题由渲染时 [theme] 动态选择）。 */
        private val TOOLTIP: HullmodTooltipSpec = hullmodTooltip {
            heading("ui.hullmod.export.section.effect")
            table {
                row(
                    "ui.hullmod.singularity_power.attr.vent_rate",
                    "ui.hullmod.singularity_power.value.vent_rate",
                )
                row(
                    "ui.hullmod.singularity_power.attr.overload",
                    "ui.hullmod.singularity_power.value.overload",
                )
                row(
                    "ui.hullmod.singularity_power.attr.environment",
                    "ui.hullmod.singularity_power.value.environment",
                )
                row(
                    "ui.hullmod.singularity_power.attr.weapon_flux",
                    "ui.hullmod.singularity_power.value.weapon_flux",
                )
            }
        }
    }
}
