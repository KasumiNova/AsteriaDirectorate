package cn.kasuminova.astd.combat.effect.lens.rare

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * MNL-2 相位捕获网雷：触发时锚定目标在实空间，施加短时机动惩罚。
 *
 * 机制概述：
 * - 触发后施加速度/转向惩罚（封顶，与 MNL-3 类似但持续更短）
 * - 对相位舰特殊效果：禁止相位短时间 + 额外惩罚
 * - 可被点防清雷、可被机动绕行
 *
 * 设计定位：控制而非伤害，封锁冲锋线路与相位切入点
 */
class Mnl2PhaseCaptureOnHitEffect : OnHitEffectPlugin {

    companion object {
        /** 速度惩罚 */
        private const val SPEED_PENALTY = 0.60f

        /** 转向惩罚 */
        private const val TURN_PENALTY = 0.55f

        /** 惩罚持续时间 */
        private const val DURATION = 1.2f

        /** 相位禁用时间 */
        private const val PHASE_DISABLE_DURATION = 1.5f

        /** 相位目标额外速度惩罚 */
        private const val PHASE_EXTRA_SPEED_PENALTY = 0.45f

        /** 同一目标冷却（避免无限叠加）*/
        private const val TARGET_COOLDOWN = 1.8f

        /** 轻度 EMP 伤害 */
        private const val EMP_DAMAGE = 150f

        /** 特效颜色：相位捕获环 */
        private val CAPTURE_COLOR = Color(140, 80, 180, 180)

        /** EMP 火花颜色 */
        private val SPARK_COLOR = Color(180, 120, 220, 160)
    }

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        val ship = target as? ShipAPI ?: return
        if (ship.isHulk || ship.isFighter) return

        val time = engine.getTotalElapsedTime(false)
        val targetKey = System.identityHashCode(ship)
        val cooldownKey = "mnl2_cd:$targetKey"

        // 检查冷却
        val cooldownEnd = engine.customData[cooldownKey] as? Float ?: 0f
        if (time < cooldownEnd) return

        // 设置冷却
        engine.customData[cooldownKey] = time + TARGET_COOLDOWN

        // 判断是否为相位舰
        val isPhaseShip = ship.phaseCloak != null || ship.system?.specAPI?.isPhaseCloak == true

        // 应用捕获效果
        applyCaptureEffect(ship, engine, isPhaseShip)

        // 视觉效果
        spawnCaptureVfx(point, engine)

        // 轻度 EMP
        engine.applyDamage(
            ship,
            point,
            0f,
            com.fs.starfarer.api.combat.DamageType.ENERGY,
            EMP_DAMAGE,
            false,
            false,
            projectile.source,
        )
    }

    private fun applyCaptureEffect(ship: ShipAPI, engine: CombatEngineAPI, isPhaseShip: Boolean) {
        val id = "mnl2_capture_${System.identityHashCode(ship)}"

        // 基础惩罚
        val speedPenalty = if (isPhaseShip) SPEED_PENALTY * PHASE_EXTRA_SPEED_PENALTY else SPEED_PENALTY
        val turnPenalty = if (isPhaseShip) TURN_PENALTY * 0.8f else TURN_PENALTY

        ship.mutableStats.maxSpeed.modifyMult(id, speedPenalty)
        ship.mutableStats.acceleration.modifyMult(id, speedPenalty)
        ship.mutableStats.deceleration.modifyMult(id, speedPenalty)
        ship.mutableStats.maxTurnRate.modifyMult(id, turnPenalty)
        ship.mutableStats.turnAcceleration.modifyMult(id, turnPenalty)

        // 相位舰特殊处理：禁用相位
        if (isPhaseShip) {
            applyPhaseDisable(ship, engine)
        }

        // 定时移除惩罚
        engine.addPlugin(object : com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin() {
            private var timer = DURATION

            override fun advance(amount: Float, events: MutableList<com.fs.starfarer.api.input.InputEventAPI>?) {
                if (engine.isPaused) return
                timer -= amount
                if (timer <= 0f) {
                    ship.mutableStats.maxSpeed.unmodify(id)
                    ship.mutableStats.acceleration.unmodify(id)
                    ship.mutableStats.deceleration.unmodify(id)
                    ship.mutableStats.maxTurnRate.unmodify(id)
                    ship.mutableStats.turnAcceleration.unmodify(id)
                    engine.removePlugin(this)
                }
            }
        })
    }

    private fun applyPhaseDisable(ship: ShipAPI, engine: CombatEngineAPI) {
        val id = "mnl2_phase_disable_${System.identityHashCode(ship)}"

        // 强制退出相位
        if (ship.isPhased) {
            ship.phaseCloak?.deactivate()
        }

        // 禁用相位能力
        ship.mutableStats.phaseCloakActivationCostBonus.modifyFlat(id, 9999f)
        ship.mutableStats.phaseCloakUpkeepCostBonus.modifyFlat(id, 9999f)

        // 视觉反馈：紫色抖动
        ship.setJitterUnder(ship, CAPTURE_COLOR, 0.8f, 8, 12f)

        // 定时解除相位禁用
        engine.addPlugin(object : com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin() {
            private var timer = PHASE_DISABLE_DURATION

            override fun advance(amount: Float, events: MutableList<com.fs.starfarer.api.input.InputEventAPI>?) {
                if (engine.isPaused) return
                timer -= amount
                if (timer <= 0f) {
                    ship.mutableStats.phaseCloakActivationCostBonus.unmodify(id)
                    ship.mutableStats.phaseCloakUpkeepCostBonus.unmodify(id)
                    engine.removePlugin(this)
                }
            }
        })
    }

    private fun spawnCaptureVfx(point: Vector2f, engine: CombatEngineAPI) {
        // 相位捕获环（紫黑色折射圈）
        engine.addHitParticle(point, Vector2f(), 60f, 1.0f, 0.25f, CAPTURE_COLOR)

        // 环状扩散粒子
        for (i in 0 until 12) {
            val angle = i * 30f
            val dist = 40f
            val ringPos = MathUtils.getPointOnCircumference(point, dist, angle)
            val outwardVel = MathUtils.getPointOnCircumference(null, 80f, angle)

            engine.addSmoothParticle(
                ringPos,
                outwardVel,
                MathUtils.getRandomNumberInRange(6f, 10f),
                1.2f,
                0.3f,
                CAPTURE_COLOR,
            )
        }

        // 短促 EMP 火花
        for (i in 0 until 8) {
            val sparkAngle = MathUtils.getRandomNumberInRange(0f, 360f)
            val sparkDist = MathUtils.getRandomNumberInRange(15f, 35f)
            val sparkPos = MathUtils.getPointOnCircumference(point, sparkDist, sparkAngle)

            engine.addSmoothParticle(
                sparkPos,
                Vector2f(
                    MathUtils.getRandomNumberInRange(-60f, 60f),
                    MathUtils.getRandomNumberInRange(-60f, 60f)
                ),
                MathUtils.getRandomNumberInRange(3f, 6f),
                1.0f,
                0.12f,
                SPARK_COLOR,
            )
        }
    }
}
