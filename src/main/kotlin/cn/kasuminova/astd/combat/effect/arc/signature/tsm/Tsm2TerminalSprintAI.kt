package cn.kasuminova.astd.combat.effect.arc.signature.tsm

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.AIUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.abs

/**
 * TSM-2 终端冲刺打击系统：两段式巡航导弹 AI。
 *
 * 机制概述：
 * - 巡航段：可被诱饵/ECM 影响，速度较慢但有制导
 * - 终端段：进入触发距离后高速冲刺，导引能力大幅下降
 *
 * 设计要求：
 * - 制导巡航：主要反制面是诱饵/ECM
 * - 终端冲刺：极速直线，几乎不转向，但对点防高减伤
 * - 双重打击：第一击破盾，第二击船体（由 OnHitEffect 处理）
 */
class Tsm2TerminalSprintAI(private val missile: MissileAPI) : MissileAIPlugin {

    companion object {
        /** 巡航段基础速度（会根据 spec 调整） */
        private const val CRUISE_SPEED = 550f

        /** 终端冲刺速度 */
        private const val SPRINT_SPEED = 1800f

        /** 终端冲刺触发距离 */
        private const val SPRINT_TRIGGER_DISTANCE = 900f

        /** 终端冲刺最大持续时间（防止无限追击） */
        private const val SPRINT_MAX_DURATION = 2.5f

        /** 终端段转向能力衰减系数 */
        private const val SPRINT_TURN_MULT = 0.15f

        /** 巡航段转向阈值（越小越“黏”目标） */
        private const val CRUISE_TURN_THRESHOLD_DEG = 1.2f

        /** 冲刺段：入段后允许短暂修正锁定角度的时间窗 */
        private const val SPRINT_LOCK_UPDATE_WINDOW = 0.25f

        /** 冲刺段转向阈值（只做极少量纠偏） */
        private const val SPRINT_TURN_THRESHOLD_DEG = 0.9f

        /** 诱饵牵引概率（每次检测） */
        private const val FLARE_ATTRACTION_CHANCE = 0.35f

        /** 诱饵检测半径 */
        private const val FLARE_DETECTION_RADIUS = 600f

        /** 巡航段尾焰颜色 */
        private val CRUISE_FLAME_COLOR = Color(100, 160, 200, 140)

        /** 冲刺段尾焰颜色 */
        private val SPRINT_FLAME_COLOR = Color(200, 230, 255, 200)
    }

    private var target: CombatEntityAPI? = null
    private var phase = Phase.CRUISE
    private var sprintTimer = 0f
    private var lockedAngle = 0f

    private val smokeInterval = IntervalUtil(0.04f, 0.08f)
    private val flareCheckInterval = IntervalUtil(0.15f, 0.25f)

    private enum class Phase {
        CRUISE,
        SPRINT
    }

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        if (missile.isFading || missile.isExpired) return

        // 获取目标
        if (!isTargetValid(target, engine)) {
            target = missile.source?.shipTarget
            if (!isTargetValid(target, engine)) {
                target = AIUtils.getNearestEnemy(missile)
            }
        }

        when (phase) {
            Phase.CRUISE -> advanceCruise(amount, engine)
            Phase.SPRINT -> advanceSprint(amount, engine)
        }

        // 尾焰粒子
        emitTrail(amount, engine)
    }

    private fun advanceCruise(amount: Float, engine: com.fs.starfarer.api.combat.CombatEngineAPI) {
        // 检测诱饵/ECM（主要反制面）
        flareCheckInterval.advance(amount)
        if (flareCheckInterval.intervalElapsed()) {
            val flareTarget = findFlareTarget(engine)
            if (flareTarget != null && Math.random() < FLARE_ATTRACTION_CHANCE) {
                target = flareTarget
            }
        }

        // 注意：诱饵检测可能更新 target，因此这里再取一次。
        val t = target

        if (t != null) {
            val dist = Misc.getDistance(missile.location, t.location)

            // 检查是否触发终端冲刺
            if (dist <= SPRINT_TRIGGER_DISTANCE) {
                enterSprintPhase()
                return
            }

            // 巡航段：更积极的追踪（避免“看起来不制导”）
            val angleTo = Misc.getAngleInDegrees(missile.location, t.location)
            val diff = MathUtils.getShortestRotation(missile.facing, angleTo)

            if (abs(diff) > CRUISE_TURN_THRESHOLD_DEG) {
                // getShortestRotation 的正负方向与 TURN_LEFT/RIGHT 在 Starsector 坐标系下是“反的”：
                // diff > 0 表示应当向左（逆时针）转，diff < 0 表示应当向右（顺时针）转。
                missile.giveCommand(if (diff > 0f) ShipCommand.TURN_LEFT else ShipCommand.TURN_RIGHT)
            }

            // 巡航段始终加速；速度上限由弹体 spec/引擎参数自然限制。
            missile.giveCommand(ShipCommand.ACCELERATE)

            // 若速度明显不足，额外推一把（让“巡航”更像巡航）
            val currentSpeed = missile.velocity.length()
            if (currentSpeed < CRUISE_SPEED * 0.85f) {
                missile.giveCommand(ShipCommand.ACCELERATE)
            }
        } else {
            missile.giveCommand(ShipCommand.ACCELERATE)
        }
    }

    private fun advanceSprint(amount: Float, engine: com.fs.starfarer.api.combat.CombatEngineAPI) {
        sprintTimer += amount

        // 冲刺超时保护
        if (sprintTimer > SPRINT_MAX_DURATION) {
            missile.flameOut()
            return
        }

        val t = target

        // 冲刺刚开始允许短暂更新锁定角度，防止入段瞬间方向偏差过大。
        if (sprintTimer <= SPRINT_LOCK_UPDATE_WINDOW && t != null && isTargetValid(t, engine)) {
            lockedAngle = Misc.getAngleInDegrees(missile.location, t.location)
        }

        // 终端段：尽量直线，只做少量纠偏（主要让 facing 跟上 lockedAngle，速度方向由 lockedAngle 决定）
        val diff = MathUtils.getShortestRotation(missile.facing, lockedAngle)
        if (abs(diff) > SPRINT_TURN_THRESHOLD_DEG) {
            val turnCmd = if (diff > 0f) ShipCommand.TURN_LEFT else ShipCommand.TURN_RIGHT
            // 概率执行 = “弱导引”体感；阈值很小所以不会乱抖
            if (Math.random() < SPRINT_TURN_MULT) {
                missile.giveCommand(turnCmd)
            }
        }

        // 全速冲刺
        missile.giveCommand(ShipCommand.ACCELERATE)

        // 直接设置速度以实现"爆发加速"效果。
        // 关键：速度方向使用 lockedAngle（而不是 missile.facing），避免“冲刺朝奇怪方向”问题。
        val targetVel = MathUtils.getPointOnCircumference(null, SPRINT_SPEED, lockedAngle)
        val vel = missile.velocity
        val lerpFactor = amount * 3f // 快速插值到目标速度
        vel.x += (targetVel.x - vel.x) * lerpFactor
        vel.y += (targetVel.y - vel.y) * lerpFactor
    }

    private fun enterSprintPhase() {
        phase = Phase.SPRINT
        sprintTimer = 0f

        // 入段时锁定“朝向目标”的角度（而不是当前 facing），避免冲刺方向先天偏离。
        lockedAngle = try {
            val t = target
            if (t != null) Misc.getAngleInDegrees(missile.location, t.location) else missile.facing
        } catch (_: Throwable) {
            missile.facing
        }

        // 标记导弹进入冲刺状态（供 VFX 和 OnHitEffect 使用）
        val engine = Global.getCombatEngine() ?: return
        val key = "tsm2_sprint:${System.identityHashCode(missile)}"
        engine.customData[key] = true
    }

    private fun findFlareTarget(engine: com.fs.starfarer.api.combat.CombatEngineAPI): CombatEntityAPI? {
        // 搜索附近的诱饵（flare）
        for (proj in engine.projectiles) {
            if (proj.owner == missile.owner) continue

            // 注意：Starsector API 的 getProjectileSpecId() 在极少数情况下可能返回 null，
            // 但 Kotlin stub 里是非空类型；直接访问属性会触发 NPE（Intrinsics.checkNotNull）。
            // 因此这里必须用 try/catch 包住 getter。
            val specId = try {
                proj.projectileSpecId
            } catch (_: Throwable) {
                null
            } ?: continue

            if (!specId.contains("flare", ignoreCase = true)) continue

            val dist = Misc.getDistance(missile.location, proj.location)
            if (dist < FLARE_DETECTION_RADIUS) {
                return proj
            }
        }
        return null
    }

    private fun emitTrail(amount: Float, engine: com.fs.starfarer.api.combat.CombatEngineAPI) {
        smokeInterval.advance(amount)
        if (!smokeInterval.intervalElapsed()) return

        val loc = missile.location
        val vel = missile.velocity

        val back = MathUtils.getPointOnCircumference(null, 12f, missile.facing + 180f)
        val p = Vector2f(loc.x + back.x, loc.y + back.y)

        val (nebulaColor, glowColor, sizeMult) = when (phase) {
            Phase.CRUISE -> Triple(
                Color(60, 100, 140, 60),
                CRUISE_FLAME_COLOR,
                1.0f
            )

            Phase.SPRINT -> Triple(
                Color(100, 150, 200, 80),
                SPRINT_FLAME_COLOR,
                1.6f
            )
        }

        // 烟雾粒子
        engine.addNebulaParticle(
            p,
            Vector2f(vel.x * 0.5f, vel.y * 0.5f),
            MathUtils.getRandomNumberInRange(18f, 26f) * sizeMult,
            1.4f,
            0.1f,
            0.3f,
            MathUtils.getRandomNumberInRange(0.5f, 0.8f),
            nebulaColor,
            true,
        )

        // 发光粒子
        engine.addSmoothParticle(
            p,
            Vector2f(vel.x * 0.3f, vel.y * 0.3f),
            MathUtils.getRandomNumberInRange(10f, 16f) * sizeMult,
            1.5f,
            0.15f,
            glowColor,
        )

        // 冲刺段额外火焰效果
        if (phase == Phase.SPRINT) {
            engine.addSmoothParticle(
                Vector2f(p.x + MathUtils.getRandomNumberInRange(-3f, 3f), p.y + MathUtils.getRandomNumberInRange(-3f, 3f)),
                Vector2f(vel.x * 0.2f, vel.y * 0.2f),
                MathUtils.getRandomNumberInRange(6f, 10f),
                2.0f,
                0.1f,
                Color(255, 255, 255, 220),
            )
        }
    }

    private fun isTargetValid(t: CombatEntityAPI?, engine: com.fs.starfarer.api.combat.CombatEngineAPI): Boolean {
        if (t == null) return false
        if (!engine.isEntityInPlay(t)) return false
        if (t.owner == missile.owner) return false
        val ship = t as? ShipAPI
        if (ship != null) {
            if (ship.isHulk || ship.isPhased) return false
        }
        return true
    }
}
