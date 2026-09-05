package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * S-03 极限辐能线圈扩容（affixes.md v3.0）：
 * - 按难度系数提升 20%~40% 辐能容量（最终乘区）；
 * - 降低 20% 辐能耗散；
 * - 与 S-02 六相冰辐能网络、R-15 电网深化升级互斥（互斥表见 AffixRegistry）。
 */
class AffixFluxCoilExpansionHullMod : BaseHullMod() {

    companion object {
        val FLUX_CAPACITY_BONUS = ScalingEntry(v1 = 0.20f, v2 = 0.30f, v5 = 0.40f)

        /** 辐能耗散惩罚。 */
        const val FLUX_DISSIPATION_MULT = 0.80f

        fun apply(stats: MutableShipStatsAPI, id: String, tuning: DifficultyTuning) {
            stats.fluxCapacity.modifyMult(id, 1f + tuning.value(FLUX_CAPACITY_BONUS))
            stats.fluxDissipation.modifyMult(id, FLUX_DISSIPATION_MULT)
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        apply(stats, id, DifficultyTuningImpl)
    }
}
