package cn.kasuminova.astd.combat.effect.arc.geminidem

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.GuidedMissileAI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// DEMScript 的 WAIT 段通过 ((GuidedMissileAI) missile.getAI()).getTarget() 取目标，
// 弹头触发前必须持有 GuidedMissileAI 且 target 非空；触发后 DEMScript 自行 setMissileAI 接管。
class GeminiDemTrackAI internal constructor(
    private val missile: MissileAPI,
    initialTarget: ShipAPI?,
    private val engineProvider: () -> CombatEngineAPI = { Global.getCombatEngine() },
    private val retarget: (CombatEngineAPI, MissileAPI) -> ShipAPI? = ::geminiDemFindNearestEnemyShip,
    random: Random = Random.Default,
    private val obstacleScan: (CombatEngineAPI, Vector2f, CombatEntityAPI?, Float) -> List<GeminiDemObstacle> = ::geminiDemScanObstacles,
) : MissileAIPlugin, GuidedMissileAI {

    @Volatile
    private var target: ShipAPI? = initialTarget

    private var flightElapsed = 0f

    private val weavePhase = random.nextFloat() * (PI_F * 2f)
    private val weaveFreq = lerp(WEAVE_FREQ_MIN, WEAVE_FREQ_MAX, random.nextFloat())
    private val weaveAmp = lerp(WEAVE_AMP_MIN, WEAVE_AMP_MAX, random.nextFloat())

    private val scanTracker = IntervalUtil(SCAN_INTERVAL_MIN, SCAN_INTERVAL_MAX)
    private var obstacles: List<GeminiDemObstacle> = emptyList()
    private var scanInitialized = false

    private var maxObstacleRadiusSeen = 0f

    override fun advance(amount: Float) {
        val engine = engineProvider()
        if (engine.isPaused) return
        if (missile.isFading || missile.isExpired) return
        flightElapsed += amount

        val current = target
        if (current == null || !isValidTarget(engine, current)) {
            target = retarget(engine, missile)
        }

        scanTracker.advance(amount)
        if (!scanInitialized || scanTracker.intervalElapsed()) {
            obstacles = obstacleScan(engine, missile.location, target, resolveScanRange(maxObstacleRadiusSeen))
            maxObstacleRadiusSeen = obstacles.maxOfOrNull { it.collisionRadius } ?: 0f
            scanInitialized = true
        }
        val avoid = avoidanceVector(missile.location, obstacles, AVOID_MARGIN)

        val t = target
        if (t != null) {
            val dist = MathUtils.getDistance(missile.location, t.location)
            val rampIn = (flightElapsed / WEAVE_RAMP_IN).coerceIn(0f, 1f)
            val proximity = (dist / WEAVE_PROXIMITY_RANGE).coerceIn(0f, 1f)
            val offset = weaveOffset(flightElapsed, weavePhase, weaveFreq, weaveAmp) * rampIn * proximity
            val aim = if (offset != 0f) {
                val angleTo = VectorUtils.getAngle(missile.location, t.location)
                MathUtils.getPointOnCircumference(Vector2f(t.location), offset, angleTo + 90f)
            } else {
                t.location
            }
            val desired = desiredHeadingAngle(VectorUtils.getAngle(missile.location, aim), avoid)
            val diff = MathUtils.getShortestRotation(missile.facing, desired)
            if (abs(diff) > TURN_DEADZONE_DEG) {
                missile.giveCommand(if (diff > 0f) ShipCommand.TURN_LEFT else ShipCommand.TURN_RIGHT)
            }
        } else if (avoid.lengthSquared() > AVOID_EPS) {
            val diff = MathUtils.getShortestRotation(missile.facing, desiredHeadingAngle(missile.facing, avoid))
            if (abs(diff) > TURN_DEADZONE_DEG) {
                missile.giveCommand(if (diff > 0f) ShipCommand.TURN_LEFT else ShipCommand.TURN_RIGHT)
            }
        }
        missile.giveCommand(ShipCommand.ACCELERATE)
    }

    override fun getTarget(): ShipAPI? = target

    override fun setTarget(target: CombatEntityAPI?) {
        this.target = target as? ShipAPI
    }

    companion object {
        private const val TURN_DEADZONE_DEG = 1f

        private const val WEAVE_RAMP_IN = 0.6f

        private const val WEAVE_PROXIMITY_RANGE = 500f

        private const val WEAVE_AMP_MIN = 160f
        private const val WEAVE_AMP_MAX = 320f

        private const val WEAVE_FREQ_MIN = 0.7f
        private const val WEAVE_FREQ_MAX = 1.4f

        private const val SCAN_INTERVAL_MIN = 0.05f
        private const val SCAN_INTERVAL_MAX = 0.1f

        internal const val SCAN_RANGE = 500f

        private const val SCAN_ADAPTIVE_SLACK = 30f

        internal const val AVOID_MARGIN = 180f

        internal const val AVOID_WEIGHT = 3f

        internal const val MAX_AVOID_DEFLECT_DEG = 75f

        private const val AVOID_EPS = 1e-4f

        private const val PI_F = Math.PI.toFloat()

        internal fun weaveOffset(elapsed: Float, phase: Float, freq: Float, amp: Float): Float =
            sin(elapsed * PI_F * 2f * freq + phase) * amp

        internal fun avoidanceVector(
            missileLoc: Vector2f,
            obstacles: List<GeminiDemObstacle>,
            margin: Float,
        ): Vector2f {
            val total = Vector2f()
            for (obstacle in obstacles) {
                val range = obstacle.collisionRadius + margin
                val dx = missileLoc.x - obstacle.location.x
                val dy = missileLoc.y - obstacle.location.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist >= range) continue
                val weight = (1f - dist / range) * AVOID_WEIGHT
                if (dist > 1e-3f) {
                    total.x += dx / dist * weight
                    total.y += dy / dist * weight
                } else {
                    total.x += weight
                }
            }
            return total
        }

        internal fun desiredHeadingAngle(baseAngleDeg: Float, avoidance: Vector2f): Float {
            if (avoidance.lengthSquared() <= AVOID_EPS) return baseAngleDeg
            val rad = Math.toRadians(baseAngleDeg.toDouble())
            val sumX = cos(rad).toFloat() + avoidance.x
            val sumY = sin(rad).toFloat() + avoidance.y
            if (sumX * sumX + sumY * sumY <= AVOID_EPS) return baseAngleDeg
            var angle = Math.toDegrees(atan2(sumY.toDouble(), sumX.toDouble())).toFloat()
            if (angle < 0f) angle += 360f
            var rotation = MathUtils.getShortestRotation(baseAngleDeg, angle)
            // getShortestRotation 完全反向时返回 -180，归一到 +180 使钳制结果确定。
            if (rotation <= -180f) rotation += 360f
            val clamped = rotation.coerceIn(-MAX_AVOID_DEFLECT_DEG, MAX_AVOID_DEFLECT_DEG)
            var result = (baseAngleDeg + clamped) % 360f
            if (result < 0f) result += 360f
            return result
        }

        internal fun resolveScanRange(maxObstacleRadius: Float): Float =
            maxOf(SCAN_RANGE, maxObstacleRadius.coerceAtLeast(0f) + AVOID_MARGIN + SCAN_ADAPTIVE_SLACK)

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private fun isValidTarget(engine: CombatEngineAPI, ship: ShipAPI): Boolean =
            ship.isAlive && !ship.isHulk && engine.isEntityInPlay(ship)
    }
}

internal data class GeminiDemObstacle(val location: Vector2f, val collisionRadius: Float)

internal fun geminiDemScanObstacles(
    engine: CombatEngineAPI,
    loc: Vector2f,
    exempt: CombatEntityAPI?,
    range: Float = GeminiDemTrackAI.SCAN_RANGE,
): List<GeminiDemObstacle> {
    val obstacles = ArrayList<GeminiDemObstacle>()
    val ships = engine.aiGridShips.getCheckIterator(loc, range * 2f, range * 2f)
    while (ships.hasNext()) {
        val entry = ships.next()
        if (entry is ShipAPI && entry !== exempt && !entry.isFighter) {
            obstacles += GeminiDemObstacle(Vector2f(entry.location), entry.collisionRadius)
        }
    }
    val asteroids = engine.aiGridAsteroids.getCheckIterator(loc, range * 2f, range * 2f)
    while (asteroids.hasNext()) {
        val entry = asteroids.next()
        if (entry is CombatEntityAPI && entry !== exempt) {
            obstacles += GeminiDemObstacle(Vector2f(entry.location), entry.collisionRadius)
        }
    }
    return obstacles
}

internal fun geminiDemFindNearestEnemyShip(
    engine: CombatEngineAPI,
    from: Vector2f,
    owner: Int,
    range: Float,
): ShipAPI? {
    var best: ShipAPI? = null
    var bestDistSq = range * range
    for (ship in engine.ships) {
        if (ship.owner == owner) continue
        if (!ship.isAlive || ship.isHulk || ship.isFighter || ship.isDrone) continue
        val distSq = MathUtils.getDistanceSquared(from, ship.location)
        if (distSq <= bestDistSq) {
            bestDistSq = distSq
            best = ship
        }
    }
    return best
}

private fun geminiDemFindNearestEnemyShip(engine: CombatEngineAPI, missile: MissileAPI): ShipAPI? =
    geminiDemFindNearestEnemyShip(engine, missile.location, missile.owner, GeminiDemDifficulty.TRACK_TARGET_RANGE)
