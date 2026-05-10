package cn.kasuminova.astd.combat.effect.arc.production

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * SLT-4 过驱抑制炮列：短窗口内命中次数达阈值后，对目标施加短时抑制态。
 *
 * 抑制态效果：降低目标武器转向/导弹制导稳定性（封顶，不叠加）。
 */
class Slt4SuppressionOnHitEffect : OnHitEffectPlugin {

    companion object {
        /** 命中窗口时长（秒）*/
        private const val HIT_WINDOW = 0.8f

        /** 触发阈值（命中次数）*/
        private const val HIT_THRESHOLD = 6

        /** 抑制态持续时间（秒）*/
        private const val SUPPRESS_DURATION = 2.0f

        /** 抑制强度（武器转向/散布惩罚）*/
        private const val SUPPRESS_PENALTY = 0.75f

        /** 抑制特效颜色 */
        private val FX_COLOR = Color(160, 220, 255, 150)
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
        if (ship.isHulk) return

        val time = engine.getTotalElapsedTime(false)
        val targetKey = System.identityHashCode(ship)

        // 命中计数器键
        val countKey = "slt4_hit_count:$targetKey"
        val windowKey = "slt4_hit_window:$targetKey"
        val suppressKey = "slt4_suppress:$targetKey"

        // 检查是否已在抑制态（不叠加）
        val suppressEnd = engine.customData[suppressKey] as? Float ?: 0f
        if (time < suppressEnd) return

        // 获取/更新命中窗口
        val windowStart = engine.customData[windowKey] as? Float ?: time
        val hitCount = engine.customData[countKey] as? Int ?: 0

        // 窗口过期，重置
        if (time - windowStart > HIT_WINDOW) {
            engine.customData[windowKey] = time
            engine.customData[countKey] = 1
            return
        }

        val newCount = hitCount + 1
        engine.customData[countKey] = newCount

        // 达到阈值，触发抑制态
        if (newCount >= HIT_THRESHOLD) {
            engine.customData[suppressKey] = time + SUPPRESS_DURATION
            engine.customData[countKey] = 0
            engine.customData[windowKey] = null

            // 应用抑制效果
            applySuppression(ship, engine)

            // 视觉反馈
            engine.addHitParticle(point, Vector2f(), 60f, 1.2f, 0.25f, FX_COLOR)
            ship.setJitterUnder(ship, FX_COLOR, 0.6f, 8, 12f)
        }
    }

    private fun applySuppression(ship: ShipAPI, engine: CombatEngineAPI) {
        val id = "slt4_suppress_${System.identityHashCode(ship)}"

        // 降低武器转向速度
        ship.mutableStats.weaponTurnRateBonus.modifyMult(id, SUPPRESS_PENALTY)
        // 增加武器散布
        ship.mutableStats.maxRecoilMult.modifyMult(id, 1f / SUPPRESS_PENALTY)

        // 注册清理（通过 EveryFrame 插件或延迟移除）
        engine.addPlugin(object : com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin() {
            private var timer = SUPPRESS_DURATION

            override fun advance(amount: Float, events: MutableList<com.fs.starfarer.api.input.InputEventAPI>?) {
                if (engine.isPaused) return
                timer -= amount
                if (timer <= 0f) {
                    ship.mutableStats.weaponTurnRateBonus.unmodify(id)
                    ship.mutableStats.maxRecoilMult.unmodify(id)
                    engine.removePlugin(this)
                }
            }
        })
    }
}
