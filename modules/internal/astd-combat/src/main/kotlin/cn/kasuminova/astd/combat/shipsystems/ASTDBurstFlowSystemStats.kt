package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript.State
import com.fs.starfarer.api.plugins.ShipSystemStatsScript.StatusData

class ASTDBurstFlowSystemStats : BaseShipSystemScript() {
    override fun apply(stats: MutableShipStatsAPI, id: String, state: State, effectLevel: Float) =
        BurstFlow.ORIGINAL.apply(stats, id, state)

    override fun unapply(stats: MutableShipStatsAPI, id: String) = BurstFlow.ORIGINAL.unapply(stats)

    override fun getStatusData(index: Int, state: State, effectLevel: Float): StatusData? {
        if (index != 0) return null
        val suffix = when (state) {
            State.IN -> "in"
            State.ACTIVE -> "active"
            State.OUT -> "out"
            else -> return null
        }
        return StatusData(I18n[I18n.Categories.MOD, "system.burst_flow.status.default.$suffix"], false)
    }
}
