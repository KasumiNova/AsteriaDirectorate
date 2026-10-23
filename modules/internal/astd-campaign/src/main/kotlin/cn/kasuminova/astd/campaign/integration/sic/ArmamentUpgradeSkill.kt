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
