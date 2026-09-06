package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 词缀 M-12：引擎辐能网隔离（[AffixRegistry.ID_ENGINE_FLUX_ISOLATION]）。
 * 按难度系数：提升 20%~40% 零辐能加速阈值；提升 25%~50% 零辐能加速的航速增益（最终乘区）。
 *
 * 实现口径：原版零辐能加速阈值基准为 0 辐能水平，"提升 X% 阈值"落地为
 * 允许在 X% 辐能水平以下仍触发零辐能加速（modifyFlat 比例值）。
 */
class AffixEngineFluxIsolationHullMod : BaseHullMod() {

    data class Bonuses(
        val thresholdFlat: Float,
        val boostMult: Float,
    )

    companion object {
        val THRESHOLD = ScalingEntry(v1 = 0.20f, v2 = 0.30f, v5 = 0.40f)
        val BOOST = ScalingEntry(v1 = 1.25f, v2 = 1.375f, v5 = 1.50f)

        fun bonuses(tuning: DifficultyTuning): Bonuses = Bonuses(
            thresholdFlat = tuning.value(THRESHOLD),
            boostMult = tuning.value(BOOST),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val b = bonuses(AffixShared.tuning)
        stats.zeroFluxMinimumFluxLevel.modifyFlat(id, b.thresholdFlat)
        stats.zeroFluxSpeedBoost.modifyMult(id, b.boostMult)
    }
}
