package cn.kasuminova.astd.combat.skills

import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

object SkillUtil {

    @JvmStatic
    fun getShip(stats: MutableShipStatsAPI?): ShipAPI? {
        return stats?.entity as? ShipAPI
    }

    @JvmStatic
    fun isAstdHull(ship: ShipAPI?): Boolean {
        return ship?.hullSpec?.hullId?.startsWith("astd_") == true
    }

    @JvmStatic
    fun isHull(ship: ShipAPI?, hullId: String?): Boolean {
        if (ship == null || hullId.isNullOrEmpty()) return false
        return ship.hullSpec.hullId == hullId
    }
}
