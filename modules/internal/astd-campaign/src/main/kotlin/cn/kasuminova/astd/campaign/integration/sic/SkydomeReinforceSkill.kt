package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.impl.campaign.skills.HullRestoration
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import second_in_command.SCData
import second_in_command.specs.SCBaseSkillPlugin

/**
 * 天幕强化（astd_sc_skydome_reinforce）——影响所有自动化舰船
 * （docs/design/skills/10-sic-skills.md 阶段二；数值声明见 [SicTuning]）。
 *
 * 机制落点：
 * - +无人舰点数：与天幕网络同一机制（[SicShared.applyAutoPoints]，advance/onActivation 写、
 *   onDeactivation 收，NPC 舰队跳过）。
 * - 击沉总是可打捞：applyEffectsBeforeShipCreation 对满足自动化判定的舰船写
 *   `dynamic.getMod(Stats.INDIVIDUAL_SHIP_RECOVERY_MOD).modifyFlat(id, HullRestoration.RECOVERY_PROB)`
 *   （原版船体修复 HullRestoration 技能同一 stat 与同一概率常量）。
 *
 * 自动化判定：直接采用原版口径 [Misc.isAutomated]（variant）——原版 ShipTypeHints
 * 枚举中并不存在 AUTOMATED 项，自动化标记走 tag 体系：variant 带 `automated` 船插
 * （HullMods.AUTOMATED，装配/改造获得）、variant 或 hullSpec 带 `automated` tag
 * （原生自动化舰体，如余晖舰），三者任一即自动化舰船。
 */
class SkydomeReinforceSkill : SCBaseSkillPlugin() {

    override fun getAffectsString(): String =
        I18n[I18n.Categories.MOD, "ui.sic.skydome_reinforce.affects"]

    override fun addTooltip(data: SCData, tooltip: TooltipMakerAPI) {
        val points = SicShared.autoPointsFor(SicTuning.REINFORCE_AUTO_POINTS, isPlayer = true)
        tooltip.hullmodCard(SicShared.SKILL_TOOLTIP_WIDTH, SicShared.CARD_THEME, null, topGapFill = 0f) {
            para(
                "ui.sic.skydome_reinforce.line.points",
                Misc.getHighlightColor(),
                8f,
                v("points", points),
            )
            para("ui.sic.skydome_reinforce.line.recovery", Misc.getHighlightColor())
        }
    }

    override fun applyEffectsBeforeShipCreation(
        data: SCData,
        stats: MutableShipStatsAPI,
        variant: ShipVariantAPI,
        hullSize: ShipAPI.HullSize,
        id: String,
    ) {
        if (!isAutomatedVariant(variant)) return
        stats.dynamic.getMod(Stats.INDIVIDUAL_SHIP_RECOVERY_MOD).modifyFlat(id, HullRestoration.RECOVERY_PROB)
    }

    override fun advance(data: SCData, amunt: Float) {
        if (data.isNPC) return
        SicShared.applyAutoPoints(spec.id, spec.name, SicTuning.REINFORCE_AUTO_POINTS, data.isPlayer)
    }

    override fun onActivation(data: SCData) {
        if (data.isNPC) return
        SicShared.applyAutoPoints(spec.id, spec.name, SicTuning.REINFORCE_AUTO_POINTS, data.isPlayer)
    }

    override fun onDeactivation(data: SCData) {
        if (data.isNPC) return
        SicShared.removeAutoPoints(spec.id)
    }

    companion object {
        /** 自动化判定：原版 Misc.isAutomated 三通道口径（依据见类 KDoc）。 */
        fun isAutomatedVariant(variant: ShipVariantAPI): Boolean = Misc.isAutomated(variant)
    }
}
