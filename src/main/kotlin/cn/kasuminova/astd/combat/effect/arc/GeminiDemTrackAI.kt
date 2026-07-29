package cn.kasuminova.astd.combat.effect.arc

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.GuidedMissileAI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f

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
 * @param missile 所属弹头导弹
 * @param initialTarget 出生即持有的目标（可为 null = 直飞）
 * @param engineProvider 引擎来源（默认取全局战斗引擎；测试注入记录桩）
 * @param retarget 目标失效时的重搜索函数（默认 2500su 最近敌舰；测试注入桩验证重搜索被调用）
 */
class GeminiDemTrackAI(
    private val missile: MissileAPI,
    initialTarget: ShipAPI?,
    private val engineProvider: () -> CombatEngineAPI = { Global.getCombatEngine() },
    private val retarget: (CombatEngineAPI, MissileAPI) -> ShipAPI? = ::geminiDemFindNearestEnemyShip,
) : MissileAIPlugin, GuidedMissileAI {

    @Volatile
    private var target: ShipAPI? = initialTarget

    override fun advance(amount: Float) {
        val engine = engineProvider()
        if (engine.isPaused) return
        if (missile.isFading || missile.isExpired) return

        val current = target
        if (current == null || !isValidTarget(engine, current)) {
            target = retarget(engine, missile)
        }

        val t = target
        if (t != null) {
            val angleTo = VectorUtils.getAngle(missile.location, t.location)
            val diff = MathUtils.getShortestRotation(missile.facing, angleTo)
            if (kotlin.math.abs(diff) > TURN_DEADZONE_DEG) {
                missile.giveCommand(if (diff > 0f) ShipCommand.TURN_LEFT else ShipCommand.TURN_RIGHT)
            }
        }
        // 有无目标都加速（无目标直飞，规格 §2.4-1）
        missile.giveCommand(ShipCommand.ACCELERATE)
    }

    /** DEMScript WAIT 段读取入口（规格 §0.1 事实 #5）：触发前必须非空，否则永不触发。 */
    override fun getTarget(): ShipAPI? = target

    override fun setTarget(target: com.fs.starfarer.api.combat.CombatEntityAPI?) {
        this.target = target as? ShipAPI
    }

    private companion object {
        /** 转向死区（度）：偏角大于该值才发转向指令，避免微抖。 */
        private const val TURN_DEADZONE_DEG = 1f

        private fun isValidTarget(engine: CombatEngineAPI, ship: ShipAPI): Boolean =
            ship.isAlive && !ship.isHulk && engine.isEntityInPlay(ship)
    }
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
