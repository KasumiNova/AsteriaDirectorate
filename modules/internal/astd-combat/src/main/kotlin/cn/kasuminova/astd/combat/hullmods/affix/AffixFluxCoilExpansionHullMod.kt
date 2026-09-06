package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 词缀 S-03：极限辐能线圈扩容（[AffixRegistry.ID_FLUX_COIL_EXPANSION]）。
 * 按难度系数：提升 20%~40% 辐能容量（最终乘区）；降低 20% 辐能耗散（固定值）。
 * 与六相冰辐能网络、电网深化升级互斥（抽取时强制）。
 */
class AffixFluxCoilExpansionHullMod : BaseHullMod() {

    data class Bonuses(
        val capacityMult: Float,
    )

    companion object {
        val CAPACITY = ScalingEntry(v1 = 1.20f, v2 = 1.30f, v5 = 1.40f)

        /** 辐能耗散惩罚（固定 -20%）。 */
        const val DISSIPATION_MULT = 0.80f

        fun bonuses(tuning: DifficultyTuning): Bonuses = Bonuses(
            capacityMult = tuning.value(CAPACITY),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val b = bonuses(AffixShared.tuning)
        stats.fluxCapacity.modifyMult(id, b.capacityMult)
        stats.fluxDissipation.modifyMult(id, DISSIPATION_MULT)
    }
}
