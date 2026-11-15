package cn.kasuminova.astd.combat.effect.arc.starfallwing

import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal interface StarfallWingBody {
    val location: Vector2f
    val velocity: Vector2f
    val radius: Float
}

internal class StarfallWingObstacle(
    override val location: Vector2f,
    override val velocity: Vector2f,
    override val radius: Float,
) : StarfallWingBody

// 扫描按节流缓存实体引用，判定时读取舰船当前位置，避免快照在高速移动下过期。
internal class StarfallWingShipBody(private val ship: ShipAPI, override val radius: Float) : StarfallWingBody {
    override val location: Vector2f get() = ship.location
    override val velocity: Vector2f get() = ship.velocity
}

internal data class StarfallWingSteer(val deflection: Float, val side: Float)

internal object StarfallWingAvoidance {
    const val CLEARANCE = 70f
    const val LOOKAHEAD_SECONDS = 0.75f
    const val MAX_DEFLECTION = 55f
    const val COLLINEAR_LATERAL = 25f
    const val SCAN_INTERVAL = 0.1f

    private const val COLLINEAR_SIDE = 1f
    private const val SCAN_PADDING = 300f
    private const val EPSILON = 1e-3f
    private val NO_STEER = StarfallWingSteer(0f, 0f)

    fun scanRange(speed: Float, limitDistance: Float): Float =
        minOf(limitDistance, speed * LOOKAHEAD_SECONDS + CLEARANCE + SCAN_PADDING)

    fun steer(
        origin: Vector2f,
        heading: Float,
        speed: Float,
        limitDistance: Float,
        obstacles: List<StarfallWingBody>,
        latchedSide: Float,
    ): StarfallWingSteer {
        val radians = Math.toRadians(heading.toDouble())
        val headingX = cos(radians).toFloat()
        val headingY = sin(radians).toFloat()
        val velocityX = headingX * speed
        val velocityY = headingY * speed
        val horizon = minOf(LOOKAHEAD_SECONDS, limitDistance / maxOf(speed, 1f))
        var deflection = 0f
        var side = 0f
        for (obstacle in obstacles) {
            val offsetX = obstacle.location.x - origin.x
            val offsetY = obstacle.location.y - origin.y
            val forward = offsetX * headingX + offsetY * headingY
            val needed = obstacle.radius + CLEARANCE
            val relativeX = obstacle.velocity.x - velocityX
            val relativeY = obstacle.velocity.y - velocityY
            val relativeSq = relativeX * relativeX + relativeY * relativeY
            val approach = if (relativeSq <= EPSILON) {
                0f
            } else {
                -(offsetX * relativeX + offsetY * relativeY) / relativeSq
            }
            // 不再接近的障碍只有仍处在前向半平面时才可能擦撞，已在后方的保持忽略。
            if (approach <= 0f && forward < 0f) continue
            val time = approach.coerceIn(0f, horizon)
            val missX = offsetX + relativeX * time
            val missY = offsetY + relativeY * time
            val missSq = missX * missX + missY * missY
            if (missSq >= needed * needed) continue
            val gap = needed - sqrt(missSq)
            val travel = maxOf(speed * time, forward, 1f)
            val angle = Math.toDegrees(asin((gap / travel).coerceAtMost(1f).toDouble())).toFloat()
            if (angle <= deflection) continue
            deflection = angle
            val lateral = headingX * missY - headingY * missX
            side = when {
                lateral > COLLINEAR_LATERAL -> -1f
                lateral < -COLLINEAR_LATERAL -> 1f
                latchedSide != 0f -> latchedSide
                else -> COLLINEAR_SIDE
            }
        }
        if (deflection <= 0f) return NO_STEER
        return StarfallWingSteer(minOf(deflection, MAX_DEFLECTION) * side, side)
    }
}

internal fun starfallWingScanObstacles(
    engine: CombatEngineAPI,
    origin: Vector2f,
    range: Float,
    projectileRadius: Float,
    ignoreFighters: Boolean,
    vararg exempt: CombatEntityAPI?,
): List<StarfallWingBody> {
    val obstacles = ArrayList<StarfallWingBody>()
    for (ship in engine.ships) {
        if (exempt.any { it === ship }) continue
        if (ship.collisionClass == CollisionClass.NONE) continue
        if (ship.isPhased) continue
        if (ignoreFighters && ship.isFighter && !ship.isHulk) continue
        val dx = ship.location.x - origin.x
        val dy = ship.location.y - origin.y
        val reach = range + ship.collisionRadius
        if (dx * dx + dy * dy > reach * reach) continue
        obstacles += StarfallWingShipBody(ship, ship.collisionRadius + projectileRadius)
    }
    return obstacles
}
