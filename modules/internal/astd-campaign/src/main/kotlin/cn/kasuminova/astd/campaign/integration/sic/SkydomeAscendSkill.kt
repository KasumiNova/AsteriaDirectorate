package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.campaign.bounty.StandardCores
import cn.kasuminova.astd.combat.skills.ASTDCombatIntelSkill
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import second_in_command.SCData
import second_in_command.specs.SCBaseSkillPlugin

/**
 * 天幕升格（astd_sc_skydome_ascend）——影响菀星设计局舰船
 * （docs/design/skills/10-sic-skills.md 阶段三；数值声明见 [SicTuning]）。
 *
 * 机制落点：
 * - +无人舰点数：与天幕强化同一机制（[SicShared.applyAutoPoints]，advance/onActivation 写、
 *   onDeactivation 收，NPC 舰队跳过）；数值与天幕强化同值但技能 id 不同，两者独立叠加（设计如此）；
 * - 菀星设计局舰船部署点降低：applyEffectsBeforeShipCreation 按
 *   variant 基础/改造舰体 spec 的 manufacturer 判定（[SicTuning.isWanxingManufacturer]，
 *   ship_data.csv tech/manufacturer 列的「菀星设计局-星坠」/「菀星设计局-紫菀」二值），
 *   命中则 `dynamic.getMod(Stats.DEPLOYMENT_POINTS_MOD).modifyMult(id, 1 - x)`；
 * - 非制式核心的自动化舰船进入战斗获得非精英「菀星战斗智能」：applyEffectsAfterShipCreation
 *   判定 `ship.captain` 为 AI 核心且非本模组制式核心（[StandardCores.byCommodity] 查无），
 *   直接实例化 [ASTDCombatIntelSkill] 的 Level1~Level5 逐个 apply（Level2/4/5 内部经
 *   ensureShipListener 幂等挂监听器，重复 apply 安全）。经 SIC 控制器船插随 stats 刷新重放，
 *   技能撤下后下一场战斗自然失效（监听器随舰船实体生灭，无跨战斗残留）。
 */
class SkydomeAscendSkill : SCBaseSkillPlugin() {

    override fun getAffectsString(): String =
        I18n[I18n.Categories.MOD, "ui.sic.skydome_ascend.affects"]

    override fun addTooltip(data: SCData, tooltip: TooltipMakerAPI) {
        val points = SicShared.autoPointsFor(SicTuning.ASCEND_AUTO_POINTS, isPlayer = true)
        tooltip.hullmodCard(
            SicShared.SKILL_TOOLTIP_WIDTH,
            SicShared.CARD_THEME,
            null,
            topGapFill = 0f,
            contentInset = SicShared.CARD_CONTENT_INSET,
        ) {
            para(
                "ui.sic.skydome_ascend.line.points",
                Misc.getHighlightColor(),
                8f,
                v("points", points),
            )
            para(
                "ui.sic.skydome_ascend.line.dp",
                Misc.getHighlightColor(),
                8f,
                v("pct", SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.ASCEND_DP_REDUCTION, true))),
            )
            para("ui.sic.skydome_ascend.line.combat_intel", Misc.getHighlightColor())
            custom { SicShared.autoPointsFooter(it, 10f) }
            custom { SicShared.grayLine(it, I18n[I18n.Categories.MOD, "ui.sic.skydome_ascend.flavor"], 10f) }
        }
    }

    override fun applyEffectsBeforeShipCreation(
        data: SCData,
        stats: MutableShipStatsAPI,
        variant: ShipVariantAPI,
        hullSize: ShipAPI.HullSize,
        id: String,
    ) {
        val manufacturer = (variant.hullSpec.baseHull ?: variant.hullSpec).manufacturer
        if (!SicTuning.isWanxingManufacturer(manufacturer)) return
        stats.dynamic.getMod(Stats.DEPLOYMENT_POINTS_MOD)
            .modifyMult(id, 1f - DifficultyTuningImpl.valueFor(SicTuning.ASCEND_DP_REDUCTION, data.isPlayer))
    }

    override fun applyEffectsAfterShipCreation(data: SCData, ship: ShipAPI, variant: ShipVariantAPI, id: String) {
        val captain = ship.captain ?: return
        if (!captain.isAICore) return
        val coreId = captain.aiCoreId ?: return
        // 制式核心已固有精英化菀星战斗智能，跳过（非制式核心才补发非精英版）
        if (StandardCores.byCommodity(coreId) != null) return

        val stats = ship.mutableStats
        val hullSize = ship.hullSize
        ASTDCombatIntelSkill.Level1().apply(stats, hullSize, id, 1f)
        ASTDCombatIntelSkill.Level2().apply(stats, hullSize, id, 1f)
        ASTDCombatIntelSkill.Level3().apply(stats, hullSize, id, 1f)
        ASTDCombatIntelSkill.Level4().apply(stats, hullSize, id, 1f)
        ASTDCombatIntelSkill.Level5().apply(stats, hullSize, id, 1f)
    }

    override fun advance(data: SCData, amunt: Float) {
        if (data.isNPC) return
        SicShared.applyAutoPoints(spec.id, spec.name, SicTuning.ASCEND_AUTO_POINTS, data.isPlayer)
    }

    override fun onActivation(data: SCData) {
        if (data.isNPC) return
        SicShared.applyAutoPoints(spec.id, spec.name, SicTuning.ASCEND_AUTO_POINTS, data.isPlayer)
    }

    override fun onDeactivation(data: SCData) {
        if (data.isNPC) return
        SicShared.removeAutoPoints(spec.id)
    }
}
