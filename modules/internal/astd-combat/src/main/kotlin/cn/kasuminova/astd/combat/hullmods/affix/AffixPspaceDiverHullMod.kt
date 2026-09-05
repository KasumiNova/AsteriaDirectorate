package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * M-11 P空间深潜器（affixes.md v3.0）：
 * - 按难度系数降低 50%~100% 相位期间因硬辐能水平提升导致的最大航速降低效果
 *   （最终乘区；100% = 完全免疫该降速）；
 * - 仅相位舰船可搭载。
 *
 * 实现口径：原版相位线圈降速由 `PhaseCloakStats` 按
 * `hardFluxLevel / threshold`（threshold 由 dynamic `phase_cloak_flux_level_for_min_speed_mod`
 * 调制，基准 0.5）线性计算；本改装把阈值放大 1/(1-r)，使同一硬辐能水平下的
 * 降速强度恰好缩减 r；r = 100% 时以超大阈值达成完全免疫。
 */
class AffixPspaceDiverHullMod : BaseHullMod() {

    companion object {
        /** 原版相位降速读取的 dynamic 调制键（勿改，引擎内写死）。 */
        const val FLUX_LEVEL_THRESHOLD_MOD = "phase_cloak_flux_level_for_min_speed_mod"

        /** 降速效果免疫比例（50%~100%）。 */
        val SLOWDOWN_IMMUNITY = ScalingEntry(v1 = 0.50f, v2 = 0.75f, v5 = 1.0f)

        /** 完全免疫时直接垫高的阈值（hardFluxLevel ∈ [0,1]，该量级下扰动率恒 ≈ 0）。 */
        const val FULL_IMMUNITY_THRESHOLD = 1_000_000f

        fun apply(stats: MutableShipStatsAPI, id: String, tuning: DifficultyTuning) {
            val immunity = tuning.value(SLOWDOWN_IMMUNITY)
            val mod = stats.dynamic.getMod(FLUX_LEVEL_THRESHOLD_MOD)
            if (immunity >= 0.999f) {
                mod.modifyFlat(id, FULL_IMMUNITY_THRESHOLD)
            } else {
                mod.modifyMult(id, 1f / (1f - immunity))
            }
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        apply(stats, id, DifficultyTuningImpl)
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = AffixUtil.isPhaseShip(ship)
}
