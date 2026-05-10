package cn.kasuminova.astd.combat.effect.lens.production

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * MNL-3 惯性网布雷器：网雷爆炸触发机动惩罚（封顶）。
 */
class Mnl3InertialDragOnHitEffect : OnHitEffectPlugin {

    companion object {
        /** 速度惩罚 */
        private const val SPEED_PENALTY = 0.70f

        /** 转向惩罚 */
        private const val TURN_PENALTY = 0.65f

        /** 惩罚持续时间 */
        private const val DURATION = 1.4f

        /** 同一目标冷却（避免无限叠加）*/
        private const val TARGET_COOLDOWN = 2.0f

        /** 特效颜色 */
        private val FX_COLOR = Color(180, 140, 220, 160)
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
        val cooldownKey = "mnl3_cd:$targetKey"

        // 检查冷却
        val cooldownEnd = engine.customData[cooldownKey] as? Float ?: 0f
        if (time < cooldownEnd) return

        // 设置冷却
        engine.customData[cooldownKey] = time + TARGET_COOLDOWN

        // 应用惯性拖曳
        applyInertialDrag(ship, engine)

        // 视觉效果
        engine.addHitParticle(point, Vector2f(), 45f, 1f, 0.2f, FX_COLOR)
        ship.setJitterUnder(ship, FX_COLOR, 0.5f, 6, 8f)
    }

    private fun applyInertialDrag(ship: ShipAPI, engine: CombatEngineAPI) {
        val id = "mnl3_drag_${System.identityHashCode(ship)}"

        ship.mutableStats.maxSpeed.modifyMult(id, SPEED_PENALTY)
        ship.mutableStats.acceleration.modifyMult(id, SPEED_PENALTY)
        ship.mutableStats.deceleration.modifyMult(id, SPEED_PENALTY)
        ship.mutableStats.maxTurnRate.modifyMult(id, TURN_PENALTY)
        ship.mutableStats.turnAcceleration.modifyMult(id, TURN_PENALTY)

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
}
