package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript.State
import com.fs.starfarer.api.plugins.ShipSystemStatsScript.StatusData

class ASTDStarfallBloomSystemStats : BaseShipSystemScript() {
    override fun apply(stats: MutableShipStatsAPI, id: String, state: State, effectLevel: Float) =
        BurstFlow.STARFALL.apply(stats, id, state)

    override fun unapply(stats: MutableShipStatsAPI, id: String) = BurstFlow.STARFALL.unapply(stats)

    override fun getStatusData(index: Int, state: State, effectLevel: Float): StatusData? {
        if (index != 0 || state != State.ACTIVE) return null
        return StatusData(I18n[I18n.Categories.MOD, "system.xc_001_burst_flow.status.active"], false)
    }
}
