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
 * 裂隙折跃系统 AI（简化口径）：折跃方向恒为飞行向量，AI 决定激活时机与折跃长度——
 * 激活前经 SYSTEM_TARGET_COORDS 注入目标点（原版 MineStrikeStats 同款通道，
 * stats 脚本侧钳进 25%~100% 最大距离），选距决策：
 * - 导弹来袭且护盾未覆盖危险方向时立即折跃规避（中距 50%：快速脱离即可，
 *   避免长距撞进未知区域）；
 * - 高幅能（≥75%）或低结构（≤40%）且正在远离最近敌舰时折跃撤退（满距 100%）；
 * - 朝目标飞行（速度向量与目标方向夹角 ≤60°）且距离 400~1500su 时折跃切入：
 *   远距（>800su）用满距压进，近距缠斗用短距（25%）过穿换位。
 */
class RiftShiftSystemAI : ShipSystemAIScript {

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
            activateWithDistance(ship, DISTANCE_FRACTION_DODGE)
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
        // 航向对齐度：>0 接近目标、<0 远离目标；速度近零时折跃方向回退舰船朝向，不参与时机判定
        val approach = if (speed > 1f) {
            Vector2f.dot(velocity, toEnemy) / speed
        } else {
            0f
        }

        val fluxLevel = ship.fluxTracker?.fluxLevel ?: 0f
        val hullLevel = ship.hullLevel
        if ((fluxLevel >= RETREAT_FLUX_THRESHOLD || hullLevel <= RETREAT_HULL_THRESHOLD) && approach < 0f) {
            activateWithDistance(ship, DISTANCE_FRACTION_FULL)
            return
        }

        if (distance in ENGAGE_MIN_RANGE..ENGAGE_MAX_RANGE && approach >= ENGAGE_APPROACH_MIN) {
            // 远距压进用满距；贴身缠斗用短距过穿（穿到目标身后换位，避免长距脱战）
            val fraction = if (distance > ENGAGE_LONG_RANGE) DISTANCE_FRACTION_FULL else RiftShiftTuning.MIN_SHIFT_FRACTION
            activateWithDistance(ship, fraction)
        }
    }

    /**
     * 以选定长度激活折跃：目标点 = 舰心 + 折跃方向 × 最大距离×[fraction]，
     * 经 SYSTEM_TARGET_COORDS 注入（stats 脚本激活边沿读取并二次钳制，时长 1s 覆盖点火窗）。
     */
    private fun activateWithDistance(ship: ShipAPI, fraction: Float) {
        val maxDist = ship.mutableStats.systemRangeBonus.computeEffective(RiftShiftTuning.SHIFT_DISTANCE)
        val dir = RiftShiftTuning.shiftDirection(ship.velocity, ship.facing)
        val target = Vector2f(
            ship.location.x + dir.x * maxDist * fraction,
            ship.location.y + dir.y * maxDist * fraction,
        )
        ship.aiFlags?.setFlag(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS, 1.0f, target)
        ship.useSystem()
    }

    /** 导弹威胁：近身（3 倍碰撞半径）来袭导弹 ≥3 枚，或单枚伤害 ≥ 20% 最大结构。 */
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

    /** 护盾未覆盖来袭方向（盾关/无盾恒为未覆盖；无危险方向信息时按已覆盖处理，不触发规避）。 */
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

        /** 撤退触发：幅能占比 ≥75% 或结构占比 ≤40%，且正在远离最近敌舰。 */
        private const val RETREAT_FLUX_THRESHOLD = 0.75f
        private const val RETREAT_HULL_THRESHOLD = 0.4f

        /** 切入窗口：目标距离 400~1500su 且速度向量与目标方向夹角 ≤60°（点积 ≥0.5）。 */
        private const val ENGAGE_MIN_RANGE = 400f
        private const val ENGAGE_MAX_RANGE = 1500f
        private const val ENGAGE_APPROACH_MIN = 0.5f

        /** 远距/近距分界（su）：超过则用满距压进，否则短距过穿换位。 */
        private const val ENGAGE_LONG_RANGE = 800f

        /** 选距占比：满距（撤退/远距压进）与中距（导弹规避，避免长距撞进未知区域）。 */
        private const val DISTANCE_FRACTION_FULL = 1.0f
        private const val DISTANCE_FRACTION_DODGE = 0.5f

        /** 导弹规避的数量门槛（单枚高伤另判）。 */
        private const val MISSILE_COUNT_THRESHOLD = 3
    }
}
