package cn.kasuminova.astd.combat.effect.arc.starfallwing

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs

/**
 * 坠星残翼追踪子射弹的自定义导弹 AI（规格 10-signature 坠星残翼节：子射弹具有追踪效果）。
 *
 * 追踪延迟：射出后前 [StarfallWingTuning.MOTE_TRACK_DELAY_SECONDS] 秒不追踪——惯性直飞
 * （不下任何机动指令，引擎不推力，速度保持散发初速），满 1s 后才索敌/转向
 * （[StarfallWingTuning.moteTrackingActive] 闸门）。
 *
 * 形态对齐仓库既有自定义追踪导弹 AI 先例：目标失效即重选最近敌舰
 * （`engine.getShips()` 上按 owner/isFighter 过滤）、TURN_LEFT/RIGHT + ACCELERATE 驱动、
 * 速度 lerp 朝目标向 900su/s（子射弹弹速）收拢；原版弹体贴图/尾焰/引擎辉光全屏蔽
 * （本体由弹体 VFX 管线接管）。
 *
 * 挂载点：由 [StarfallWingWeaponEffect] 散发子射弹时经 `missile.missileAI = ...` 安装。
 */
class StarfallWingMoteAi(
    private val missile: MissileAPI,
    initialTarget: ShipAPI?,
) : MissileAIPlugin {

    private var target: ShipAPI? = initialTarget

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || missile.isFading || missile.isExpired) return
        missile.interruptContrail()
        missile.spriteAlphaOverride = 0f
        missile.glowRadius = 0f
        // 追踪延迟窗：惯性直飞，不索敌、不转向、不推力（直线弹道随散发初速）
        if (!StarfallWingTuning.moteTrackingActive(missile.flightTime)) return
        val source = missile.source ?: return
        if (!isValidTarget(engine, target, source)) {
            target = nearestEnemyShip(engine, source, missile.location, TARGET_REFRESH_RANGE)
        }
        val t = target
        if (t != null && isValidTarget(engine, t, source)) {
            val angleTo = VectorUtils.getAngle(missile.location, t.location)
            val diff = MathUtils.getShortestRotation(missile.facing, angleTo)
            missile.facing = angleTo
            missile.angularVelocity = 0f
            if (abs(diff) > 1.0f) missile.giveCommand(if (diff > 0f) ShipCommand.TURN_LEFT else ShipCommand.TURN_RIGHT)
            applyVelocityToward(amount, angleTo)
        }
        missile.giveCommand(ShipCommand.ACCELERATE)
    }

    private fun applyVelocityToward(amount: Float, facing: Float) {
        val desired = MathUtils.getPointOnCircumference(null, PURSUIT_SPEED, facing)
        val lerp = (amount * 14f).coerceIn(0f, 1f)
        missile.velocity.x += (desired.x - missile.velocity.x) * lerp
        missile.velocity.y += (desired.y - missile.velocity.y) * lerp
    }

    private fun isValidTarget(engine: CombatEngineAPI, t: ShipAPI?, source: ShipAPI): Boolean {
        if (t == null) return false
        if (!engine.isEntityInPlay(t)) return false
        if (!t.isAlive || t.isHulk || t.isFighter || t.isDrone) return false
        if (t.owner == source.owner) return false
        return true
    }

    companion object {
        /** 追踪收拢速度（su/s，子射弹弹速口径）。 */
        private const val PURSUIT_SPEED = 900f

        /** 目标重选搜索半径（su）。 */
        private const val TARGET_REFRESH_RANGE = 1400f

        /** 最近敌舰（ships 表扫描，排除战机/无人机/残骸/同阵营）。 */
        fun nearestEnemyShip(engine: CombatEngineAPI, source: ShipAPI, near: Vector2f, range: Float): ShipAPI? {
            var best: ShipAPI? = null
            var bestDistance = range
            for (candidate in engine.ships) {
                val ship = candidate as? ShipAPI ?: continue
                if (ship === source || ship.owner == source.owner) continue
                if (!ship.isAlive || ship.isHulk || ship.isFighter || ship.isDrone) continue
                val distance = MathUtils.getDistance(near, ship.location)
                if (distance < bestDistance) {
                    best = ship
                    bestDistance = distance
                }
            }
            return best
        }
    }
}
