package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * M-10 反应式辐能装甲（affixes.md v3.0）：
 * - 按难度系数降低 75%~90% 强制排辐期间受到的装甲与船体伤害（最终乘区，逐帧排辐判定）；
 * - 强制排辐速率降低 35%。
 */
class AffixReactiveFluxArmorHullMod : BaseHullMod() {

    companion object {
        const val HULLMOD_ID = "astd_affix_reactive_flux_armor"

        /** 强制排辐期间受到的装甲/船体伤害减免（最终乘区）。 */
        val VENTING_DAMAGE_TAKEN_REDUCTION = ScalingEntry(v1 = 0.75f, v2 = 0.825f, v5 = 0.90f)

        /** 强制排辐速率惩罚。 */
        const val VENT_RATE_MULT = 0.65f

        fun applyVentRatePenalty(stats: MutableShipStatsAPI, id: String) {
            stats.ventRateMult.modifyMult(id, VENT_RATE_MULT)
        }

        /** 逐帧排辐防御乘区：排辐中挂减免，否则摘除（R-15 电网深化升级复用本逻辑）。 */
        fun applyVentingDefense(ship: ShipAPI, id: String, tuning: DifficultyTuning) {
            val stats = ship.mutableStats
            if (ship.fluxTracker?.isVenting == true) {
                val mult = 1f - tuning.value(VENTING_DAMAGE_TAKEN_REDUCTION)
                stats.armorDamageTakenMult.modifyMult(id, mult)
                stats.hullDamageTakenMult.modifyMult(id, mult)
            } else {
                stats.armorDamageTakenMult.unmodify(id)
                stats.hullDamageTakenMult.unmodify(id)
            }
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        applyVentRatePenalty(stats, id)
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        applyVentingDefense(ship, HULLMOD_ID, DifficultyTuningImpl)
    }
}
