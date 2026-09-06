package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 词缀 S-02：六相冰辐能网络（[AffixRegistry.ID_CRYO_FLUX_NETWORK]）。
 * 按难度系数：提升 10%~20% 辐能耗散；提升 20%~40% 强制排辐速率；
 * 降低 20%~40% 受到的 EMP 伤害（最终乘区）。
 * 与极限辐能线圈扩容互斥（抽取时强制）。
 */
class AffixCryoFluxNetworkHullMod : BaseHullMod() {

    data class Bonuses(
        val dissipationMult: Float,
        val ventRateMult: Float,
        val empDamageTakenMult: Float,
    )

    companion object {
        val DISSIPATION = ScalingEntry(v1 = 1.10f, v2 = 1.15f, v5 = 1.20f)
        val VENT_RATE = ScalingEntry(v1 = 1.20f, v2 = 1.30f, v5 = 1.40f)
        val EMP_TAKEN = ScalingEntry(v1 = 0.80f, v2 = 0.70f, v5 = 0.60f)

        /** 按给定难度读取面计算加成（完整逻辑，实现与测试共用；电网深化升级复用本条目数值）。 */
        fun bonuses(tuning: DifficultyTuning): Bonuses = Bonuses(
            dissipationMult = tuning.value(DISSIPATION),
            ventRateMult = tuning.value(VENT_RATE),
            empDamageTakenMult = tuning.value(EMP_TAKEN),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val b = bonuses(AffixShared.tuning)
        stats.fluxDissipation.modifyMult(id, b.dissipationMult)
        stats.ventRateMult.modifyMult(id, b.ventRateMult)
        stats.empDamageTakenMult.modifyMult(id, b.empDamageTakenMult)
    }
}
