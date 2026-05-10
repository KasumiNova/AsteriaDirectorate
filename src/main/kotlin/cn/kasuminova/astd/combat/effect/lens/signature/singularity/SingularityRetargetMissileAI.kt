package cn.kasuminova.astd.combat.effect.lens.signature.singularity

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
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs

/**
 * 奇点投射器导弹 AI：
 * - 每 2s 重新选择目标/重新定向
 * - 重定向窗口内速度减半，随后恢复全速追击
 *
 * 说明：
 * - “穿盾直击”属于命中逻辑，但为了做到“对盾不造成伤害”，需要在飞行中把 missile.damageAmount 动态置 0。
 */
class SingularityRetargetMissileAI(
    private val missile: MissileAPI,
) : MissileAIPlugin {

    companion object {
        /** 每次重新定向间隔（秒）。 */
        private const val RETARGET_INTERVAL = 2.0f

        /** 重定向持续时间（秒）：在该时间窗内速度会被压到一半。 */
        private const val REORIENT_SECONDS = 0.45f

        /** 重定向期速度倍率。 */
        private const val REORIENT_SPEED_MULT = 0.5f

        /** 目标幅能比例 >= 该值时触发“穿盾直击”。 */
        const val SHIELD_BYPASS_FLUX_LEVEL = 0.85f

        /** 目标搜索半径（su）。 */
        private const val SEARCH_RANGE = 3200f

        /** 转向阈值：避免小角度抖动。 */
        private const val TURN_THRESHOLD_DEG = 1.2f
    }

    private var target: CombatEntityAPI? = null
    private var reorientLeft = 0f

    /** 用“已达到的最高速度”作为全速追击基准，避免依赖外部读取 missile.maxSpeed（不同 stub/版本可能不一致）。 */
    private var fullSpeedSoFar = 0f

    private val retargetInterval = IntervalUtil(RETARGET_INTERVAL, RETARGET_INTERVAL)
    private val reacquireInterval = IntervalUtil(0.20f, 0.20f)

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        if (missile.isFading || missile.isExpired) return

        // 兼容：有些 stub/版本对 maxSpeed 的 getter 不稳定；这里仅用于“兜底基准”。
        val maxSpeed = safeMaxSpeed()

        // 记录原始伤害（首次）
        if (missile.customData[SingularityKeys.MISSILE_ORIGINAL_DAMAGE] == null) {
            try {
                missile.setCustomData(SingularityKeys.MISSILE_ORIGINAL_DAMAGE, missile.damageAmount)
            } catch (_: Throwable) {
            }
        }

        // 目标有效性：若无效，先清空（不要每帧都“重定向一次”导致 VFX 刷屏/速度长期减半）。
        if (!isTargetValid(target, engine)) {
            target = null
        }

        // 定期重新选目标（稳定节奏：默认 2s 一次）
        retargetInterval.advance(amount)
        var didRetarget = false

        if (retargetInterval.intervalElapsed()) {
            val newTarget = pickTarget(engine)
            if (isTargetValid(newTarget, engine)) {
                target = newTarget
                reorientLeft = REORIENT_SECONDS
                didRetarget = true
            }
        } else if (target == null) {
            // 没有目标时：更快的“重获目标”节奏（不影响 2s 一次的正式重定向机制）
            reacquireInterval.advance(amount)
            if (reacquireInterval.intervalElapsed()) {
                val newTarget = pickTarget(engine)
                if (isTargetValid(newTarget, engine)) {
                    target = newTarget
                    reorientLeft = REORIENT_SECONDS
                    didRetarget = true
                }
            }
        }

        if (didRetarget) {
            // 记录“发生过一次重定向”：用于 VFX 做轻量提示（不影响机制）。
            try {
                val prev = (missile.customData[SingularityKeys.MISSILE_RETARGET_COUNT] as? Int) ?: 0
                missile.setCustomData(SingularityKeys.MISSILE_RETARGET_COUNT, prev + 1)
                missile.setCustomData(SingularityKeys.MISSILE_LAST_RETARGET_AT, engine.getTotalElapsedTime(false))
            } catch (_: Throwable) {
            }
        }

        // 重定向计时
        if (reorientLeft > 0f) {
            reorientLeft -= amount
        }

        // 更新“全速基准”（仅上升不下降）
        // 修复：fullSpeedSoFar 初始为 0 时，desired=1 会把导弹锁死在极低速度，导致“没目标时慢飘几秒就消失”。
        val currentSpeed = try {
            missile.velocity.length()
        } catch (_: Throwable) {
            0f
        }
        if (maxSpeed > fullSpeedSoFar) fullSpeedSoFar = maxSpeed
        if (currentSpeed > fullSpeedSoFar) fullSpeedSoFar = currentSpeed

        val t = target
        if (t != null && isTargetValid(t, engine)) {
            val aimPoint = computeAimPoint(t)
            val angleTo = Misc.getAngleInDegrees(missile.location, aimPoint)
            val diff = MathUtils.getShortestRotation(missile.facing, angleTo)
            if (abs(diff) > TURN_THRESHOLD_DEG) {
                // getShortestRotation 的正负方向与 TURN_LEFT/RIGHT 在 Starsector 坐标系下是“反的”
                missile.giveCommand(if (diff > 0f) ShipCommand.TURN_LEFT else ShipCommand.TURN_RIGHT)
            }
        }

        // 始终加速：速度/方向交给 velocity 插值
        missile.giveCommand(ShipCommand.ACCELERATE)

        // 速度控制：重定向期减半，之后恢复。
        val speedMul = if (reorientLeft > 0f) REORIENT_SPEED_MULT else 1.0f
        val desiredFull = listOf(fullSpeedSoFar, maxSpeed, 200f).maxOrNull() ?: 200f
        applyVelocityTarget(amount, desiredFull * speedMul)

        // 关键：实现“高幅能目标穿盾直击（对盾不造成伤害）”
        // 做法：在飞行中把 missile.damageAmount 置 0，让引擎侧不会给护盾结算伤害；命中时由 OnHitEffect 另行 applyDamage(bypassShields=true)。
        updateDamageForShieldBypass(t)
    }

    private fun safeMaxSpeed(): Float {
        return try {
            missile.maxSpeed
        } catch (_: Throwable) {
            0f
        }
    }

    private fun pickTarget(engine: com.fs.starfarer.api.combat.CombatEngineAPI): CombatEntityAPI? {
        // 1) 优先跟随来源舰船的 shipTarget
        val st = missile.source?.shipTarget
        if (isTargetValid(st, engine)) return st

        // 2) 否则从附近敌舰里选一个
        val candidates = CombatUtils.getShipsWithinRange(missile.location, SEARCH_RANGE)
        if (candidates.isNotEmpty()) {
            var best: ShipAPI? = null
            var bestScore = -1f
            for (s in candidates) {
                if (!isTargetValid(s, engine)) continue
                if (s.hullSize == ShipAPI.HullSize.FIGHTER) continue

                val dist = Misc.getDistance(missile.location, s.location).coerceAtLeast(1f)
                val distScore = (1f - (dist / SEARCH_RANGE)).coerceIn(0f, 1f)
                val fluxScore = (0.25f + 0.75f * (s.fluxLevel.coerceIn(0f, 1f)))
                // 略微偏好“可穿盾”的高幅能目标
                val bypassBonus = if (s.fluxLevel >= SHIELD_BYPASS_FLUX_LEVEL) 1.25f else 1.0f

                val score = distScore * fluxScore * bypassBonus
                if (score > bestScore) {
                    bestScore = score
                    best = s
                }
            }
            if (best != null) return best
        }

        // 3) 兜底：最近敌人
        return AIUtils.getNearestEnemy(missile)
    }

    private fun computeAimPoint(target: CombatEntityAPI): Vector2f {
        // 简化：先不做复杂提前量（对“每 2s 重新定向”的体感更稳定）
        return target.location
    }

    private fun applyVelocityTarget(amount: Float, desiredSpeed: Float) {
        val vTarget = MathUtils.getPointOnCircumference(null, desiredSpeed, missile.facing)
        val v = missile.velocity
        val lerp = (amount * 4.0f).coerceIn(0f, 1f)
        v.x += (vTarget.x - v.x) * lerp
        v.y += (vTarget.y - v.y) * lerp
    }

    private fun updateDamageForShieldBypass(currentTarget: CombatEntityAPI?) {
        val ship = currentTarget as? ShipAPI

        val orig = (missile.customData[SingularityKeys.MISSILE_ORIGINAL_DAMAGE] as? Float)

        val shouldBypass = ship != null && !ship.isHulk && ship.fluxLevel >= SHIELD_BYPASS_FLUX_LEVEL

        try {
            if (shouldBypass) {
                missile.setDamageAmount(0f)
            } else if (orig != null) {
                // 恢复原始伤害（避免“锁定过一次高幅能目标后永久变 0”）
                missile.setDamageAmount(orig)
            }
        } catch (_: Throwable) {
        }
    }

    private fun isTargetValid(t: CombatEntityAPI?, engine: com.fs.starfarer.api.combat.CombatEngineAPI): Boolean {
        if (t == null) return false
        if (!engine.isEntityInPlay(t)) return false

        val ship = t as? ShipAPI ?: return true
        if (ship.isHulk) return false
        if (ship.owner == missile.owner) return false
        if (ship.isPhased) return false

        return true
    }
}
