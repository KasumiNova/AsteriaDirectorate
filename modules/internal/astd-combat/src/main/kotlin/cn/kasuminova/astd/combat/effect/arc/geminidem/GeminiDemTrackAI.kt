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

/**
 * 双子星 DEM 弹头的追踪段 AI（规格 10 §2.2）：转向/加速指令追踪目标，目标失效重搜索。
 *
 * 动机（规格 §0.2 架构裁定 1）：打击段复用原版 DEMScript，自定义面收缩为追踪段。
 * 本类同时实现 [GuidedMissileAI]——DEMScript 的 WAIT 段通过
 * `((GuidedMissileAI) missile.getAI()).getTarget()` 取目标（规格 §0.1 事实 #5），
 * 弹头导弹在触发前必须持有 GuidedMissileAI 且 target 非空，否则永不触发。
 *
 * 生命周期：DEMScript 触发后自行 `setMissileAI(this)` 接管（事实 #6），本 AI 自然结束，无清理负担。
 * 转向只做指令级（giveCommand），把机动手感交给弹体引擎参数——与 DEM 段衔接平滑
 * （DEMScript 的 turnRateBoost 在同一引擎参数上加成）。
 *
 * 无目标定义行为（规格 §2.4-1）：只发 ACCELERATE 直飞，flightTime 耗尽自毁，无伤害、无异常。
 *
 * 摆动飞行（2026-09-26 实机裁定）：追踪段不做直线追尾，瞄准点 = 目标位置 + 垂直于弹目连线的
 * 正弦横向偏移（[weaveOffset]），每枚弹头随机幅度/频率/相位（构造期 [random] 抽取），
 * 形成双弹各自的蛇形弹道；起飞 [WEAVE_RAMP_IN] 秒内渐强（避免出生即急转），
 * 接近目标 [WEAVE_PROXIMITY_RANGE] 内线性收敛（末端回归直线保证命中段精度，
 * DEMScript 接管距离 700~750 大于该值，接管时仍带少量摆动余量）。
 *
 * 障碍规避（2026-10-17 实机裁定）：追踪段尽力规避一切「非当前追踪目标」的碰撞体——
 * 舰船（含残骸 hulk，主要撞击风险源）与陨石；障碍列表走 aiGrid 空间网格周期刷新
 * （[SCAN_INTERVAL_MIN]~[SCAN_INTERVAL_MAX]s 节拍，[geminiDemScanObstacles]），不逐帧全表扫描。
 * 规避向量（[avoidanceVector]，MoteAI 式加权斥力）与「目标 + weave」瞄准方向合成期望朝向
 * （[desiredHeadingAngle]），转向指令跟随合成方向——规避是偏转而非覆盖，不丢失追踪；
 * 极端避不开时允许撞上（合成方向被斥力压过即撞线，无额外保命逻辑）。
 * 战机/其他导弹不纳入障碍：体量小、相对速度大，规避无意义且徒增转向噪声。
 * 打击段（DEMScript 接管后）不带本规避。
 *
 * @param missile 所属弹头导弹
 * @param initialTarget 出生即持有的目标（可为 null = 直飞）
 * @param engineProvider 引擎来源（默认取全局战斗引擎；测试注入记录桩）
 * @param retarget 目标失效时的重搜索函数（默认 2500su 最近敌舰；测试注入桩验证重搜索被调用）
 * @param random 摆动参数随机源（测试注入定值种子验证摆动轨迹）
 * @param obstacleScan 障碍扫描函数（默认 aiGrid 空间网格；测试注入桩驱动规避合成）
 */
class GeminiDemTrackAI internal constructor(
    private val missile: MissileAPI,
    initialTarget: ShipAPI?,
    private val engineProvider: () -> CombatEngineAPI = { Global.getCombatEngine() },
    private val retarget: (CombatEngineAPI, MissileAPI) -> ShipAPI? = ::geminiDemFindNearestEnemyShip,
    random: Random = Random.Default,
    private val obstacleScan: (CombatEngineAPI, Vector2f, CombatEntityAPI?) -> List<GeminiDemObstacle> = ::geminiDemScanObstacles,
) : MissileAIPlugin, GuidedMissileAI {

    @Volatile
    private var target: ShipAPI? = initialTarget

    /** 追踪段累计飞行秒数（摆动相位与起飞渐强的时基；暂停帧不计）。 */
    private var flightElapsed = 0f

    private val weavePhase = random.nextFloat() * (PI_F * 2f)
    private val weaveFreq = lerp(WEAVE_FREQ_MIN, WEAVE_FREQ_MAX, random.nextFloat())
    private val weaveAmp = lerp(WEAVE_AMP_MIN, WEAVE_AMP_MAX, random.nextFloat())

    /** 障碍列表刷新节拍（MoteAI 同口径 0.05~0.1s；首个 advance 立即扫描一次）。 */
    private val scanTracker = IntervalUtil(SCAN_INTERVAL_MIN, SCAN_INTERVAL_MAX)
    private var obstacles: List<GeminiDemObstacle> = emptyList()
    private var scanInitialized = false

    override fun advance(amount: Float) {
        val engine = engineProvider()
        if (engine.isPaused) return
        if (missile.isFading || missile.isExpired) return
        flightElapsed += amount

        val current = target
        if (current == null || !isValidTarget(engine, current)) {
            target = retarget(engine, missile)
        }

        // 障碍列表周期刷新：当前追踪目标本人豁免（它就是要撞的）
        scanTracker.advance(amount)
        if (!scanInitialized || scanTracker.intervalElapsed()) {
            obstacles = obstacleScan(engine, missile.location, target)
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
            // 规避向量与瞄准方向合成期望朝向：近距斥力可压过追踪方向（尽力规避），远距几乎无扰动
            val desired = desiredHeadingAngle(VectorUtils.getAngle(missile.location, aim), avoid)
            val diff = MathUtils.getShortestRotation(missile.facing, desired)
            if (abs(diff) > TURN_DEADZONE_DEG) {
                missile.giveCommand(if (diff > 0f) ShipCommand.TURN_LEFT else ShipCommand.TURN_RIGHT)
            }
        } else if (avoid.lengthSquared() > AVOID_EPS) {
            // 无目标直飞段同样尽力避障：以当前朝向为基准方向叠加规避
            val diff = MathUtils.getShortestRotation(missile.facing, desiredHeadingAngle(missile.facing, avoid))
            if (abs(diff) > TURN_DEADZONE_DEG) {
                missile.giveCommand(if (diff > 0f) ShipCommand.TURN_LEFT else ShipCommand.TURN_RIGHT)
            }
        }
        // 有无目标都加速（无目标直飞，规格 §2.4-1）
        missile.giveCommand(ShipCommand.ACCELERATE)
    }

    /** DEMScript WAIT 段读取入口（规格 §0.1 事实 #5）：触发前必须非空，否则永不触发。 */
    override fun getTarget(): ShipAPI? = target

    override fun setTarget(target: CombatEntityAPI?) {
        this.target = target as? ShipAPI
    }

    companion object {
        /** 转向死区（度）：偏角大于该值才发转向指令，避免微抖。 */
        private const val TURN_DEADZONE_DEG = 1f

        /** 摆动起飞渐强时长（秒）：出生该时间内幅度 0 → 全额，避免出生即急转。 */
        private const val WEAVE_RAMP_IN = 0.6f

        /** 摆动末端收敛距离（su）：弹目距离小于该值时幅度线性收敛（末端回归直线保证命中段精度）。 */
        private const val WEAVE_PROXIMITY_RANGE = 500f

        /** 摆动幅度区间（su）：瞄准点横向偏移峰值。 */
        private const val WEAVE_AMP_MIN = 160f
        private const val WEAVE_AMP_MAX = 320f

        /** 摆动频率区间（Hz）：正弦周期 1/f 秒。 */
        private const val WEAVE_FREQ_MIN = 0.7f
        private const val WEAVE_FREQ_MAX = 1.4f

        /** 障碍扫描节拍（秒）：aiGrid 空间网格查询的刷新间隔区间。 */
        private const val SCAN_INTERVAL_MIN = 0.05f
        private const val SCAN_INTERVAL_MAX = 0.1f

        /**
         * 障碍网格查询半边长（su）：覆盖主力舰级碰撞半径（~200）+ 规避余量；
         * 弹速 225su/s 下 0.1s 刷新周期内弹体位移 ~22su，500su 窗口不会漏障。
         */
        internal const val SCAN_RANGE = 500f

        /**
         * 规避生效余量（su）：障碍距离 < 碰撞半径 + 本值时产生斥力。
         * 弹头转向 75°/s、弹速 225su/s 的转弯半径约 172su，本值须覆盖该提前量才来得及绕开。
         */
        internal const val AVOID_MARGIN = 180f

        /**
         * 斥力权重：单个贴脸障碍的斥力模长上限（与模长 1 的瞄准单位向量合成，
         * 近距规避方向可压过追踪方向；多个障碍线性累加）。
         */
        internal const val AVOID_WEIGHT = 3f

        /** 规避向量有效阈值（模长平方）：低于该值视为无规避需求，不干扰原瞄准/直飞。 */
        private const val AVOID_EPS = 1e-4f

        private const val PI_F = Math.PI.toFloat()

        /** 摆动横向偏移（su）：正弦波，[amp] 为峰值幅度；纯函数供测试锚定。 */
        internal fun weaveOffset(elapsed: Float, phase: Float, freq: Float, amp: Float): Float =
            sin(elapsed * PI_F * 2f * freq + phase) * amp

        /**
         * 规避斥力合成（MoteAI 式加权斥力，纯函数）：对每个 [obstacles] 中距离
         * < 碰撞半径 + [margin] 的障碍，累加「障碍→弹体远离方向 × (1 − dist/range) × [AVOID_WEIGHT]」。
         * 无生效障碍返回零向量。
         */
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
                    // 完全重合时方向无定义：满额斥力取 +x（极端重合允许任意方向推开）
                    total.x += weight
                }
            }
            return total
        }

        /**
         * 期望朝向合成（纯函数）：基准方向 [baseAngleDeg]（瞄准或当前朝向，模长 1）
         * 与规避斥力 [avoidance] 矢量相加，取合成方向角；斥力恰与基准完全抵消（罕见极端）
         * 时保持基准方向。返回角度制朝向（0~360）。
         */
        internal fun desiredHeadingAngle(baseAngleDeg: Float, avoidance: Vector2f): Float {
            if (avoidance.lengthSquared() <= AVOID_EPS) return baseAngleDeg
            val rad = Math.toRadians(baseAngleDeg.toDouble())
            val sumX = cos(rad).toFloat() + avoidance.x
            val sumY = sin(rad).toFloat() + avoidance.y
            if (sumX * sumX + sumY * sumY <= AVOID_EPS) return baseAngleDeg
            var angle = Math.toDegrees(atan2(sumY.toDouble(), sumX.toDouble())).toFloat()
            if (angle < 0f) angle += 360f
            return angle
        }

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private fun isValidTarget(engine: CombatEngineAPI, ship: ShipAPI): Boolean =
            ship.isAlive && !ship.isHulk && engine.isEntityInPlay(ship)
    }
}

/** 避障障碍快照（位置 + 碰撞半径）：按刷新节拍取样，周期内位置视为不变（10Hz 下位移可忽略）。 */
internal data class GeminiDemObstacle(val location: Vector2f, val collisionRadius: Float)

/**
 * aiGrid 障碍扫描（追踪段避障数据源）：[loc] 周边 2×[range] 盒内的舰船（含残骸 hulk）与陨石。
 * 口径：当前追踪目标 [exempt] 豁免（它就是要撞的）；战机/导弹不纳入——体量小、相对速度大，
 * 规避无意义且徒增转向噪声。
 */
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

/**
 * 最近敌舰搜索（Salvo 第 3 步与 TrackAI 重搜索共用规则，规格 10 §2.2）：
 * [range] 内归属不同、存活、非残骸、非战机、非无人机的最近舰船；无候选返回 null。
 */
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

/** TrackAI 重搜索默认实参形态：以导弹位置与归属为锚点。 */
private fun geminiDemFindNearestEnemyShip(engine: CombatEngineAPI, missile: MissileAPI): ShipAPI? =
    geminiDemFindNearestEnemyShip(engine, missile.location, missile.owner, GeminiDemDifficulty.TRACK_TARGET_RANGE)
