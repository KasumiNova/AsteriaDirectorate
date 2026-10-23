package cn.kasuminova.astd.combat.effect.lens.stellar

import cn.kasuminova.astd.api.combat.StellarMrmTargeting
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

object StellarMrmTargetingImpl : StellarMrmTargeting {

    override fun select(
        candidates: List<CombatEntityAPI>,
        from: Vector2f,
        owner: Int,
        acquireRange: Float,
        inPlay: (CombatEntityAPI) -> Boolean,
    ): ShipAPI? {
        var bestFighter: ShipAPI? = null
        var bestFighterDist = Float.MAX_VALUE
        var bestShip: ShipAPI? = null
        var bestShipDist = Float.MAX_VALUE
        for (candidate in candidates) {
            val ship = candidate as? ShipAPI ?: continue
            if (ship.owner == owner) continue
            if (!ship.isAlive || ship.isHulk) continue
            if (!inPlay(ship)) continue
            val dist = MathUtils.getDistance(from, ship.location)
            if (dist > acquireRange) continue
            if (ship.isFighter) {
                if (dist < bestFighterDist) {
                    bestFighter = ship
                    bestFighterDist = dist
                }
            } else {
                if (dist < bestShipDist) {
                    bestShip = ship
                    bestShipDist = dist
                }
            }
        }
        return bestFighter ?: bestShip
    }
}
