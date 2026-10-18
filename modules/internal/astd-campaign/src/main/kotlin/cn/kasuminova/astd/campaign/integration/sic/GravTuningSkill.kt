package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import second_in_command.SCData
import second_in_command.specs.SCBaseSkillPlugin

/**
 * 引力调谐（astd_sc_grav_tuning）——影响所有相位舰船
 * （docs/design/skills/10-sic-skills.md 阶段二；数值声明见 [SicTuning]）。
 *
 * 相位判定：variant 基础/改造舰体 spec 的 isPhase（与 SIC PhaseCoilTuning 同一口径）。
 *
 * 共存屏蔽：舰队已激活 SIC「相位深潜」（sc_technology_deep_dive）时，
 * 「+峰值时间」与「相位时流降低」两条不生效（tooltip 同步切换为灰色失效文案）。
 *
 * 机制落点（applyEffectsBeforeShipCreation）：
 * - +峰值时间：`peakCRDuration.modifyPercent`（共存屏蔽）；
 * - 相位时流降低：`dynamic.getStat(Stats.PHASE_TIME_BONUS_MULT).modifyFlat(-0.5 × 五档表)`——
 *   该 stat 的消费点（PhaseCloakStats.getMaxTimeMult）以单参 getValue 读 stats map，
 *   必须写 getStat 而非 getMod（与 ASTDCombatIntelTuning 已确认的口径一致；共存屏蔽）；
 * - 失速阈值提升：`dynamic.getMod(Stats.PHASE_CLOAK_FLUX_LEVEL_FOR_MIN_SPEED_MOD).modifyPercent`
 *   （PhaseCloakStats.getDisruptionLevel 消费）；
 * - 相位硬辐能产出降低：`phaseCloakUpkeepCostBonus.modifyMult(1 - x)`。
 *
 * 相位下峰值时间与 CR 消耗速度降低（applyEffectsAfterShipCreation 幂等挂
 * [PhaseCrCompensationListener]，SIC `if (!ship.hasListenerOfClass(X)) ship.addListener(X)` 范式）：
 * 原版实现（starfarer_obf com.fs.starfarer.combat.entities.Ship.advance）逐帧
 * `timeDeployed += amount * (1 + busyLaunchBays)`，超过 peakCRDuration 后
 * `currentCR -= amount * CRLossPerSecondPercent.computeEffective(...) * 0.01`。
 * 监听器按帧补偿：相位中将本帧 timeDeployed 增量按减免比例经 ShipAPI.setTimeDeployed 回退
 * （delta 法天然含 busyLaunchBays 系数，且不受监听器与舰船 advance 相对顺序影响），
 * 并对 `crLossPerSecondPercent` 逐帧 unmodify/modify 乘区（SIC SafeRecovery 既有范式）
 * 使 CR 降速 ×(1-x)。
 */
class GravTuningSkill : SCBaseSkillPlugin() {

    override fun getAffectsString(): String =
        I18n[I18n.Categories.MOD, "ui.sic.grav_tuning.affects"]

    override fun addTooltip(data: SCData, tooltip: TooltipMakerAPI) {
        val deepDive = data.isSkillActive(SicTuning.DEEP_DIVE_SKILL_ID)

        val peakCr = SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.GRAV_PEAK_CR_PERCENT, true) / 100f)
        val timeFlow = SicShared.formatPercent(
            DifficultyTuningImpl.valueFor(SicTuning.GRAV_PHASE_TIME_FLOW_TABLE, true) * SicTuning.GRAV_PHASE_TIME_FLOW_BASE,
        )
        tooltip.hullmodCard(
            SicShared.SKILL_TOOLTIP_WIDTH,
            SicShared.CARD_THEME,
            null,
            topGapFill = 0f,
            contentInset = SicShared.CARD_CONTENT_INSET,
        ) {
            if (deepDive) {
                custom {
                    SicShared.grayLine(
                        it,
                        I18n.t(I18n.Categories.MOD, "ui.sic.grav_tuning.line.peak_cr_suppressed", "pct" to peakCr),
                        8f,
                        peakCr,
                    )
                }
                custom {
                    SicShared.grayLine(
                        it,
                        I18n.t(I18n.Categories.MOD, "ui.sic.grav_tuning.line.time_flow_suppressed", "pct" to timeFlow),
                        8f,
                        timeFlow,
                    )
                }
            } else {
                para("ui.sic.grav_tuning.line.peak_cr", Misc.getHighlightColor(), 8f, v("pct", peakCr))
                para("ui.sic.grav_tuning.line.time_flow", Misc.getHighlightColor(), 8f, v("pct", timeFlow))
            }

            para(
                "ui.sic.grav_tuning.line.flux_threshold",
                Misc.getHighlightColor(),
                8f,
                v("pct", SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.GRAV_FLUX_THRESHOLD_PERCENT, true) / 100f)),
            )
            para(
                "ui.sic.grav_tuning.line.upkeep",
                Misc.getHighlightColor(),
                8f,
                v("pct", SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.GRAV_PHASE_UPKEEP_REDUCTION, true))),
            )
            para(
                "ui.sic.grav_tuning.line.cr_drain",
                Misc.getHighlightColor(),
                8f,
                v("pct", SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.GRAV_PHASE_CR_REDUCTION, true))),
            )
            custom { SicShared.grayLine(it, I18n[I18n.Categories.MOD, "ui.sic.grav_tuning.flavor"], 10f) }
        }
    }

    override fun applyEffectsBeforeShipCreation(
        data: SCData,
        stats: MutableShipStatsAPI,
        variant: ShipVariantAPI,
        hullSize: ShipAPI.HullSize,
        id: String,
    ) {
        if (!isPhaseVariant(variant)) return

        if (!data.isSkillActive(SicTuning.DEEP_DIVE_SKILL_ID)) {
            stats.peakCRDuration.modifyPercent(id, DifficultyTuningImpl.valueFor(SicTuning.GRAV_PEAK_CR_PERCENT, data.isPlayer))
            val flowTableValue = DifficultyTuningImpl.valueFor(SicTuning.GRAV_PHASE_TIME_FLOW_TABLE, data.isPlayer)
            stats.dynamic.getStat(Stats.PHASE_TIME_BONUS_MULT)
                .modifyFlat(id, -SicTuning.GRAV_PHASE_TIME_FLOW_BASE * flowTableValue)
        }

        stats.dynamic.getMod(Stats.PHASE_CLOAK_FLUX_LEVEL_FOR_MIN_SPEED_MOD)
            .modifyPercent(id, DifficultyTuningImpl.valueFor(SicTuning.GRAV_FLUX_THRESHOLD_PERCENT, data.isPlayer))
        stats.phaseCloakUpkeepCostBonus
            .modifyMult(id, 1f - DifficultyTuningImpl.valueFor(SicTuning.GRAV_PHASE_UPKEEP_REDUCTION, data.isPlayer))
    }

    override fun applyEffectsAfterShipCreation(data: SCData, ship: ShipAPI, variant: ShipVariantAPI, id: String) {
        if (!isPhaseVariant(variant)) return
        if (!ship.hasListenerOfClass(PhaseCrCompensationListener::class.java)) {
            ship.addListener(PhaseCrCompensationListener(ship, data.isPlayer))
        }
    }

    companion object {
        /** 相位判定：改造舰体（D 改/皮肤）回退到基础舰体 spec（SIC baseOrModSpec 同口径）。 */
        fun isPhaseVariant(variant: ShipVariantAPI): Boolean =
            (variant.hullSpec.baseHull ?: variant.hullSpec).isPhase
    }

    /**
     * 相位下峰值时间与 CR 消耗补偿（机制依据见外层类 KDoc）。
     *
     * CR 消耗走 `crLossPerSecondPercent` 乘区而非直接改写 currentCR：
     * ShipAPI.setCurrentCR 带变更通知，逐帧回写会造成 CR 状态回调抖动，
     * 乘区写法只影响原版扣减公式本身，无额外状态写入。
     */
    private class PhaseCrCompensationListener(
        private val ship: ShipAPI,
        private val isPlayer: Boolean,
    ) : AdvanceableListener {

        private var lastTimeDeployed: Float = ship.timeDeployedForCRReduction

        override fun advance(amount: Float) {
            val crLoss = ship.mutableStats.crLossPerSecondPercent
            crLoss.unmodifyMult(CR_LOSS_MOD_ID)

            if (ship.isHulk || !ship.isAlive) {
                ship.removeListener(this)
                return
            }

            val reduction = DifficultyTuningImpl.valueFor(SicTuning.GRAV_PHASE_CR_REDUCTION, isPlayer)
            if (ship.isPhased && reduction > 0f) {
                val now = ship.timeDeployedForCRReduction
                val delta = now - lastTimeDeployed
                if (delta > 0f) {
                    ship.setTimeDeployed((now - delta * reduction).coerceAtLeast(0f))
                }
                crLoss.modifyMult(CR_LOSS_MOD_ID, 1f - reduction)
            }

            lastTimeDeployed = ship.timeDeployedForCRReduction
        }
    }
}

/** CR 降速乘区的 sourceId（相位补偿监听器逐帧 unmodify/modify）。 */
private const val CR_LOSS_MOD_ID = "astd_sc_grav_tuning_phase_cr"
