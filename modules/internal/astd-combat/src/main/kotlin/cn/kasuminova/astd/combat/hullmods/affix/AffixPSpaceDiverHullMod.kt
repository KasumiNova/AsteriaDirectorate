package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 词缀 M-11：P空间深潜器（[AffixRegistry.ID_PSPACE_DIVER]）。
 * 按难度系数：降低 50%~100% 相位舰船因相位期间硬辐能水平提升导致的最大航速降低效果
 * （最终乘区；100% = 完全免疫该降速）。
 * 仅相位舰船可搭载。
 *
 * 实现口径：原版相位降速由 `phase_cloak_flux_level_for_min_speed_mod` 阈值决定
 * （降速强度 = hardFluxLevel / 阈值），放大阈值即等比例削弱降速；100% 时取超大倍率实现免疫。
 */
class AffixPSpaceDiverHullMod : BaseHullMod() {

    companion object {
        const val FLUX_LEVEL_FOR_MIN_SPEED_MOD = "phase_cloak_flux_level_for_min_speed_mod"

        /** 降速效果减免比例：50%~100%。 */
        val REDUCTION = ScalingEntry(v1 = 0.50f, v2 = 0.75f, v5 = 1.00f)

        /** 完全免疫时的阈值倍率（等效于降速强度归零）。 */
        private const val IMMUNE_THRESHOLD_MULT = 1_000_000f

        /** 阈值倍率：x/(1-x) 反比放大；x≈100% 时按免疫处理（完整逻辑，实现与测试共用）。 */
        fun thresholdMult(tuning: DifficultyTuning): Float {
            val reduction = tuning.value(REDUCTION).coerceIn(0f, 1f)
            return if (reduction >= 0.999f) IMMUNE_THRESHOLD_MULT else 1f / (1f - reduction)
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        if (!AffixShared.isPhaseShip(stats)) return
        stats.dynamic.getMod(FLUX_LEVEL_FOR_MIN_SPEED_MOD).modifyMult(id, thresholdMult(AffixShared.tuning))
    }
}
