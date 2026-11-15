package cn.kasuminova.astd.combat.effect.arc.starfallwing

import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

internal object StarfallWingSelection {
    fun shieldedApproach(origin: Vector2f, target: ShipAPI, ships: List<ShipAPI>): Boolean {
        val root = target.parentStation ?: target
        return ships.any { ship ->
            if ((ship.parentStation ?: ship) !== root || !ship.isAlive || ship.isHulk || ship.isPhased) {
                false
            } else {
                val shield = ship.shield
                shield != null && shield.isOn && shieldCoversApproach(
                    origin, target.location, shield.location, shield.radius, shield.facing, shield.activeArc,
                )
            }
        }
    }

    fun shieldCoversApproach(
        origin: Vector2f,
        target: Vector2f,
        shieldCenter: Vector2f,
        radius: Float,
        facing: Float,
        activeArc: Float,
    ): Boolean {
        if (radius <= 0f || activeArc <= 0f) return false
        val ox = origin.x.toDouble() - shieldCenter.x
        val oy = origin.y.toDouble() - shieldCenter.y
        val dx = target.x.toDouble() - origin.x
        val dy = target.y.toDouble() - origin.y
        val lengthSq = dx * dx + dy * dy
        val outside = ox * ox + oy * oy - radius.toDouble() * radius
        if (lengthSq == 0.0 || outside <= 0.0) return false
        val projection = ox * dx + oy * dy
        val discriminant = projection * projection - lengthSq * outside
        if (discriminant < 0.0) return false
        val entry = (-projection - sqrt(discriminant)) / lengthSq
        if (entry < 0.0 || entry > 1.0) return false
        val angle = Math.toDegrees(atan2(oy + dy * entry, ox + dx * entry)).toFloat()
        return abs(StarfallWingTargeting.angleBetween(facing, angle)) <= activeArc * 0.5f
    }

    fun choose(randomUnit: Float, hasTarget: Boolean, fighter: Boolean, shielded: Boolean): StarfallWingTuning.ShuttleType {
        require(randomUnit >= 0f && randomUnit < 1f)
        val exposedShip = hasTarget && !fighter && !shielded
        val blue = if (exposedShip) 2f else 1f
        val red = blue
        val gold = if (hasTarget && !fighter && shielded) 3f else 1f
        val purple = if (hasTarget && fighter) 3f else 1f
        val total = blue + red + gold + purple
        return when {
            randomUnit < blue / total -> StarfallWingTuning.ShuttleType.BLUE
            randomUnit < (blue + red) / total -> StarfallWingTuning.ShuttleType.RED
            randomUnit < (blue + red + gold) / total -> StarfallWingTuning.ShuttleType.GOLD
            else -> StarfallWingTuning.ShuttleType.PURPLE
        }
    }
}
