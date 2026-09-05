package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * S-02 六相冰辐能网络（affixes.md v3.0）：
 * - 按难度系数提升 10%~20% 辐能耗散、20%~40% 强制排辐速率；
 * - 降低 20%~40% 受到的 EMP 伤害（最终乘区）；
 * - 与 S-03 极限辐能线圈扩容互斥（互斥表见 AffixRegistry）。
 */
class AffixCryoFluxNetworkHullMod : BaseHullMod() {

    companion object {
        val FLUX_DISSIPATION_BONUS = ScalingEntry(v1 = 0.10f, v2 = 0.15f, v5 = 0.20f)
        val VENT_RATE_BONUS = ScalingEntry(v1 = 0.20f, v2 = 0.30f, v5 = 0.40f)

        /** 受到的 EMP 伤害减免（最终乘区）。 */
        val EMP_DAMAGE_TAKEN_REDUCTION = ScalingEntry(v1 = 0.20f, v2 = 0.30f, v5 = 0.40f)

        fun apply(stats: MutableShipStatsAPI, id: String, tuning: DifficultyTuning) {
            stats.fluxDissipation.modifyMult(id, 1f + tuning.value(FLUX_DISSIPATION_BONUS))
            stats.ventRateMult.modifyMult(id, 1f + tuning.value(VENT_RATE_BONUS))
            stats.empDamageTakenMult.modifyMult(id, 1f - tuning.value(EMP_DAMAGE_TAKEN_REDUCTION))
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        apply(stats, id, DifficultyTuningImpl)
    }
}
