package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

/**
 * 星翼折跃 AI：位移距离固定（[cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning.SHIFT_DISTANCE]），
 * 方向由激活瞬间的舰速/舰首决定，AI 只判定时机——导弹规避、高幅/残结构撤退、中距接敌突进。
 */
class StarwingShiftSystemAI : ShipSystemAIScript {

    private var ship: ShipAPI? = null
    private var system: ShipSystemAPI? = null
    private var engine: CombatEngineAPI? = null
    private val scanInterval = IntervalUtil(SCAN_INTERVAL_MIN, SCAN_INTERVAL_MAX)

    override fun init(ship: ShipAPI, system: ShipSystemAPI, flags: ShipwideAIFlags, engine: CombatEngineAPI) {
        this.ship = ship
        this.system = system
        this.engine = engine
        scanInterval.forceIntervalElapsed()
    }

    override fun advance(amount: Float, missileDangerDir: Vector2f?, collisionDangerDir: Vector2f?, target: ShipAPI?) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return
        if (engine.isPaused || ship.isHulk) return
        if (system.state != ShipSystemAPI.SystemState.IDLE) return
        if (!system.canBeActivated()) return

        // 导弹规避为即时反应，不走扫描节拍
        if (missileThreat(ship, engine) && shieldNotCoveringDanger(ship, missileDangerDir)) {
            ship.useSystem()
            return
        }

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        val nearest = nearestEnemy(ship, engine) ?: return
        val toEnemy = Vector2f.sub(nearest.location, ship.location, null)
        val distance = toEnemy.length()
        if (distance <= 1f) return
        toEnemy.normalise()

        val velocity = ship.velocity
        val speed = velocity.length()
        val approach = if (speed > 1f) {
            Vector2f.dot(velocity, toEnemy) / speed
        } else {
            0f
        }

        val fluxLevel = ship.fluxTracker?.fluxLevel ?: 0f
        val hullLevel = ship.hullLevel
        if ((fluxLevel >= RETREAT_FLUX_THRESHOLD || hullLevel <= RETREAT_HULL_THRESHOLD) && approach < 0f) {
            ship.useSystem()
            return
        }

        // 接敌突进：本舰正朝目标机动时折跃缩短距离，现身齐射+过载正好衔接输出窗口
        if (distance in ENGAGE_MIN_RANGE..ENGAGE_MAX_RANGE && approach >= ENGAGE_APPROACH_MIN) {
            ship.useSystem()
        }
    }

    private fun missileThreat(ship: ShipAPI, engine: CombatEngineAPI): Boolean {
        var count = 0
        val dangerRange = ship.collisionRadius * 3f
        val hullDanger = ship.maxHitpoints * 0.2f
        for (missile in engine.missiles) {
            if (missile.owner == ship.owner || missile.isFading || missile.isFizzling) continue
            if (MathUtils.getDistance(ship.location, missile.location) > dangerRange) continue
            count++
            if (missile.damageAmount >= hullDanger) return true
        }
        return count >= MISSILE_COUNT_THRESHOLD
    }

    private fun shieldNotCoveringDanger(ship: ShipAPI, missileDangerDir: Vector2f?): Boolean {
        val shield = ship.shield ?: return true
        if (!shield.isOn) return true
        val dir = missileDangerDir ?: return false
        if (dir.lengthSquared() <= 1f) return false
        val point = Vector2f(
            ship.location.x + dir.x * ship.collisionRadius * 2f,
            ship.location.y + dir.y * ship.collisionRadius * 2f,
        )
        return !shield.isWithinArc(point)
    }

    private fun nearestEnemy(ship: ShipAPI, engine: CombatEngineAPI): ShipAPI? {
        var best: ShipAPI? = null
        var bestDistance = Float.MAX_VALUE
        for (candidate in engine.ships) {
            val other = candidate as? ShipAPI ?: continue
            if (other === ship || other.owner == ship.owner) continue
            if (!other.isAlive || other.isHulk || other.isFighter) continue
            val distance = MathUtils.getDistance(ship.location, other.location)
            if (distance < bestDistance) {
                best = other
                bestDistance = distance
            }
        }
        return best
    }

    companion object {
        private const val SCAN_INTERVAL_MIN = 0.3f
        private const val SCAN_INTERVAL_MAX = 0.5f

        private const val RETREAT_FLUX_THRESHOLD = 0.75f
        private const val RETREAT_HULL_THRESHOLD = 0.4f

        private const val ENGAGE_MIN_RANGE = 400f
        private const val ENGAGE_MAX_RANGE = 1500f
        private const val ENGAGE_APPROACH_MIN = 0.5f

        private const val MISSILE_COUNT_THRESHOLD = 3
    }
}
