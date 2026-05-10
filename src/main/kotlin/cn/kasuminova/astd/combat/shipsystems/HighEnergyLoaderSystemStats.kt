package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import cn.kasuminova.astd.internal.i18n.I18n

/**
 * astd_high_energy_loader（高能装填）
 *
 * 作为偏通用的“短时武器增幅系统”（3s）：
 * - 提升能量武器射速/弹速
 * - 以轻度幅耗增加作为代价（保持反制面）
 */
class HighEnergyLoaderSystemStats : BaseShipSystemScript() {

    companion object {
        private const val ROF_MULT = 1.5f
        private const val PROJ_SPEED_MULT = 1.2f
        private const val FLUX_COST_MULT = 1.25f
    }

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val level = effectLevel.coerceIn(0f, 1f)

        stats.energyRoFMult.modifyMult(id, lerp(1f, ROF_MULT, level))
        stats.energyProjectileSpeedMult.modifyMult(id, lerp(1f, PROJ_SPEED_MULT, level))
        stats.energyWeaponFluxCostMod.modifyMult(id, lerp(1f, FLUX_COST_MULT, level))
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        stats.energyRoFMult.unmodify(id)
        stats.energyProjectileSpeedMult.unmodify(id)
        stats.energyWeaponFluxCostMod.unmodify(id)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    override fun getStatusData(
        index: Int,
        state: ShipSystemStatsScript.State,
        effectLevel: Float
    ): ShipSystemStatsScript.StatusData? {
        if (index == 0) return ShipSystemStatsScript.StatusData(I18n[I18n.Categories.MOD, "system.high_energy_loader.status.0"], false)
        if (index == 1) return ShipSystemStatsScript.StatusData(I18n[I18n.Categories.MOD, "system.high_energy_loader.status.1"], true)
        return null
    }
}
