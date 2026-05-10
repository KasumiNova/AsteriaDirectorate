package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript

class PlaceholderShipSystemStats : BaseShipSystemScript() {

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        // No-op placeholder.
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        // No-op placeholder.
    }

    override fun getStatusData(index: Int, state: ShipSystemStatsScript.State, effectLevel: Float): ShipSystemStatsScript.StatusData? {
        if (index == 0) {
            return ShipSystemStatsScript.StatusData(I18n.j("asteria_directorate", "system.placeholder.status.0"), false)
        }
        return null
    }
}
