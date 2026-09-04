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
 * 回声定影系统 AI（spec §2：延迟结算的区域威慑）。
 *
 * 施放时机：当本舰 [SELECT_RANGE] 内存在**敌群密集区**（[CLUSTER_RADIUS] 内 ≥ [MIN_CLUSTER_SIZE]
 * 艘敌舰）时，把该敌群质心写入 [ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS] 作为落点，并施放系统。
 * 落点最终由 [EchoFixationSystemStats] 读取该 flag 建场——AI 只决定「何时放、放哪片」。
 *
 * 设计意图：定影场是区域威慑，对**站桩 / 扎堆**的敌群收益最高（站桩重合度增伤，spec §2.1）。
 * 故 AI 选「场内能覆盖最多敌舰」的密集中心，而非单一最近目标，以最大化回放期认知撕裂覆盖面。
 *
 * 模式说明：回声定影机制本身无载人 / 无人差异（spec §2），故 AI 不分版；难度系数缩放在
 * [cn.kasuminova.astd.combat.lens.system.EchoFixationField] 内部按 source 处理。
 *
 * 节流：每 [SCAN_INTERVAL_SEC] 扫描一次敌群（区域扫描较重），缓存结果供施放判定使用。
 */
class EchoFixationSystemAI : ShipSystemAIScript {

    companion object {
        /** 落点选择范围（su，spec §2.1：鼠标选择范围 ~2000su；AI 与玩家共用上限）。 */
        private const val SELECT_RANGE = 2000f

        /** 敌群密集判定半径（su）：略小于场半径 ~700su，确保选中的群能被场覆盖。 */
        private const val CLUSTER_RADIUS = 650f

        /** 构成「值得施放」的敌群最小数量。 */
        private const val MIN_CLUSTER_SIZE = 2

        /** 敌群扫描间隔（s）。 */
        private const val SCAN_INTERVAL_SEC = 0.6f
    }

    private var ship: ShipAPI? = null
    private var system: ShipSystemAPI? = null
    private var engine: CombatEngineAPI? = null

    private val scanInterval = IntervalUtil(SCAN_INTERVAL_SEC, SCAN_INTERVAL_SEC)
    private var scanInited = false

    /** 缓存：上次扫描得到的最佳敌群中心（null 表示无值得施放的敌群）。 */
    private var bestClusterCenter: Vector2f? = null

    override fun init(ship: ShipAPI, system: ShipSystemAPI, flags: ShipwideAIFlags, engine: CombatEngineAPI) {
        this.ship = ship
        this.system = system
        this.engine = engine
        scanInterval.forceIntervalElapsed()
        scanInited = false
        bestClusterCenter = null
    }

    override fun advance(
        amount: Float,
        missileDangerDir: Vector2f?,
        collisionDangerDir: Vector2f?,
        target: ShipAPI?,
    ) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return

        if (engine.isPaused) return
        if (ship.isHulk) return

        scanInterval.advance(amount)
        if (!scanInited || scanInterval.intervalElapsed()) {
            bestClusterCenter = findBestClusterCenter(engine, ship)
            scanInited = true
        }

        // 系统已在激活中（含 IN / ACTIVE）或不可用：不重复触发。
        if (system.isOn ||
            system.state == ShipSystemAPI.SystemState.IN ||
            system.state == ShipSystemAPI.SystemState.ACTIVE
        ) {
            return
        }
        if (!system.canBeActivated()) return

        val center = bestClusterCenter ?: return

        // 把落点写入 AI flag 供 stats 读取，并施放系统。
        // 1f duration 必须覆盖到 stats 在 IN 首帧读 flag 的时机，即需 ≥ .system 的 chargeUp（当前 0.5s）。
        // 若后续调长 chargeUp，须同步加大此 duration，否则 flag 在 stats 读取前过期，落点会静默退化到前方敌群中心。
        ship.aiFlags?.setFlag(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS, 1f, center)
        ship.useSystem()
    }

    /**
     * 扫描 [SELECT_RANGE] 内的敌舰，找「以某敌舰为中心、[CLUSTER_RADIUS] 内敌舰最多」的群，
     * 返回该群质心；最大群规模不足 [MIN_CLUSTER_SIZE] 时返回 null。
     *
     * @return 最佳敌群质心（世界坐标）或 null（无值得施放的敌群）。
     */
    private fun findBestClusterCenter(engine: CombatEngineAPI, ship: ShipAPI): Vector2f? {
        val enemies = ArrayList<ShipAPI>()
        for (candidate in engine.ships) {
            val s = candidate as? ShipAPI ?: continue
            if (s.owner == ship.owner) continue
            if (s.isHulk || s.isFighter) continue
            if (!engine.isEntityInPlay(s)) continue
            if (MathUtils.getDistance(ship.location, s.location) > SELECT_RANGE) continue
            enemies.add(s)
        }
        if (enemies.isEmpty()) return null

        var bestCount = 0
        var bestCenter: Vector2f? = null
        val clusterR2 = CLUSTER_RADIUS * CLUSTER_RADIUS
        // 以每个候选敌舰为种子，统计其 CLUSTER_RADIUS 内敌舰数与质心，取覆盖最多者。
        for (seed in enemies) {
            var sumX = 0f
            var sumY = 0f
            var count = 0
            for (other in enemies) {
                val dx = other.location.x - seed.location.x
                val dy = other.location.y - seed.location.y
                if (dx * dx + dy * dy > clusterR2) continue
                sumX += other.location.x
                sumY += other.location.y
                count++
            }
            if (count > bestCount) {
                bestCount = count
                bestCenter = Vector2f(sumX / count, sumY / count)
            }
        }

        if (bestCount < MIN_CLUSTER_SIZE) return null
        return bestCenter
    }
}
