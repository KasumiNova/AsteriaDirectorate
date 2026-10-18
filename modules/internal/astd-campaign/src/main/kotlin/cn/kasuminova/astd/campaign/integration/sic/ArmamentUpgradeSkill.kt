package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemDifficulty
import cn.kasuminova.astd.combat.effect.arc.heavyionpulse.HeavyIonPulseTuning
import cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveDifficulty
import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjuePhaseRailgunDifficulty
import cn.kasuminova.astd.combat.effect.base.SicWeaponUpgradeFlags
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import second_in_command.SCData
import second_in_command.specs.SCBaseSkillPlugin

/**
 * 武备升级（astd_sc_armament_upgrade）——影响舰队中所有舰船
 * （docs/design/skills/10-sic-skills.md 阶段二；7 条武器行为改造均为固定语义，无难度缩放）。
 *
 * 机制总线：applyEffectsAfterShipCreation 无条件为每艘舰船写入激活标记
 * （`ship.setCustomData(SicWeaponUpgradeFlags.ARMAMENT_UPGRADE_KEY, true)`），
 * astd-combat 各武器特效在既有纯函数/结算路径上按标记分支（未激活分支 = 现状逐字保留）：
 *
 * 1. 引力坍缩炮：船体附加伤害按目标最大船体值比例（GravityCollapseDifficulty.hullDamageBasis）；
 * 2. 电荷针刺：电荷淤积的固定软辐能不再受目标护盾状态门控（ChargeNeedleTuning.flatFluxPerSec）；
 * 3. 彗星冲击波：超 100% 的 EMP 抗性削弱按 5%:1% 转为目标能量易伤（HeavyIonPulseTuning.splitEmpShift）；
 * 4. 「穷距」相位轨道炮：持续演算上限提升至 15 层（QiongjueStackMath.maxStacks）；
 * 5. 正电子冲击波：对战机追加等量高爆伤害（PositronShockwaveDifficulty.fighterExtraHeDamage）；
 * 6. 双子星 DEM：共振触发后红色导弹同样产生电弧，电弧按目标硬辐能比例概率穿盾
 *    （GeminiDemPayloadBeamEffect / GeminiDemDifficulty.arcShieldPierceChance）；
 * 7. 电驱加速炮：装药额外动能伤害固定取原随机区间上限（ElectricDriveAcceleratorDifficulty.chargeRollPct）。
 */
class ArmamentUpgradeSkill : SCBaseSkillPlugin() {

    override fun getAffectsString(): String =
        I18n[I18n.Categories.MOD, "ui.sic.armament_upgrade.affects"]

    override fun addTooltip(data: SCData, tooltip: TooltipMakerAPI) {
        tooltip.hullmodCard(
            SicShared.SKILL_TOOLTIP_WIDTH,
            SicShared.CARD_THEME,
            null,
            topGapFill = 0f,
            contentInset = SicShared.CARD_CONTENT_INSET,
        ) {
            para("ui.sic.armament_upgrade.line.gravity_collapse", Misc.getHighlightColor())
            para("ui.sic.armament_upgrade.line.charge_needle", Misc.getHighlightColor())
            para(
                "ui.sic.armament_upgrade.line.heavy_ion_pulse",
                Misc.getHighlightColor(),
                8f,
                v("ratio", "${SicShared.formatNumber(HeavyIonPulseTuning.ENERGY_VULN_DIVISOR)}%:1%"),
            )
            para(
                "ui.sic.armament_upgrade.line.qiongjue",
                Misc.getHighlightColor(),
                8f,
                v("stacks", SicShared.formatNumber(QiongjuePhaseRailgunDifficulty.SIC_UPGRADE_MAX_STACKS.toFloat())),
            )
            para(
                "ui.sic.armament_upgrade.line.positron",
                Misc.getHighlightColor(),
                8f,
                v("mult", SicShared.formatPercent(PositronShockwaveDifficulty.SIC_FIGHTER_EXTRA_HE_MULT)),
            )
            para("ui.sic.armament_upgrade.line.gemini_dem", Misc.getHighlightColor())
            para(
                "ui.sic.armament_upgrade.sub.gemini_dem",
                Misc.getHighlightColor(),
                2f,
                v("start", SicShared.formatPercent(GeminiDemDifficulty.ARC_PIERCE_RAMP_START)),
                v("full", SicShared.formatPercent(GeminiDemDifficulty.ARC_PIERCE_RAMP_FULL)),
                v("max", SicShared.formatPercent(GeminiDemDifficulty.ARC_PIERCE_MAX_CHANCE)),
            )
            para("ui.sic.armament_upgrade.line.eda", Misc.getHighlightColor())
            custom { SicShared.grayLine(it, I18n[I18n.Categories.MOD, "ui.sic.armament_upgrade.flavor"], 10f) }
        }
    }

    override fun applyEffectsAfterShipCreation(data: SCData, ship: ShipAPI, variant: ShipVariantAPI, id: String) {
        ship.setCustomData(SicWeaponUpgradeFlags.ARMAMENT_UPGRADE_KEY, true)
    }
}
