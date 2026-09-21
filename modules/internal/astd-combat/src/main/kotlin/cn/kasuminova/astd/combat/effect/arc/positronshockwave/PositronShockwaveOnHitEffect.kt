package cn.kasuminova.astd.combat.effect.arc.positronshockwave

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.sin

/**
 * 正电子冲击波撞舰引爆路由（2026-09 用户裁定：弹体识别舰船碰撞，不再穿舰通过）：
 * 挂 `.proj` 的 `onHitEffect`，碰撞类为 PROJECTILE_NO_FF（原版高爆同口径，免伤友军）。
 *
 * 舰船命中（护盾或船体装甲均可）→ 在命中点就地引爆锥面冲击（与引信脚本同源，
 * 走 [PositronShockwaveFuseScript.detonate] 共享实现）；命中点锥轴取弹体速度方向。
 *
 * 与引信脚本的互斥：[PositronShockwaveFuseScript.detonate] 入口按弹体做一次性 claim
 * （同帧竞态只爆一次）；OnHit 路径 `removeEntity` 后，引信脚本次帧见弹体不在场即静默回收。
 * 难度取值现场 [PositronShockwaveDifficulty.resolve] 一次（与引信取值口径一致，
 * 同一发弹体两次取值结果相同——同一 k_s / owner 输入，允许重复调用）。
 */
class PositronShockwaveOnHitEffect : OnHitEffectPlugin {

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f?,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        if (engine.isPaused) return

        // 只对舰船引爆；战机/导弹交给近炸引信（锥程 40% 触发圈）。
        if (target !is ShipAPI || target.isFighter || target.isDrone || target.isHulk) return

        val loc = point ?: projectile.location ?: return

        // 锥轴取弹体速度方向；速度近零（被外力骤停等罕见路径）用朝向兜底并 WARN 一次
        // （与引信脚本同条件口径一致，保证引爆产出合法锥形且异常可见）。
        val vel = projectile.velocity
        val dir = if (vel.lengthSquared() > 1e-3f) {
            Vector2f(vel).also { it.normalise() }
        } else {
            if (!warnedZeroVelocity) {
                warnedZeroVelocity = true
                log.warn("正电子冲击波撞舰引爆时弹体速度近零（|v|²=${vel.lengthSquared()}），锥轴退化为弹体朝向（一次性告警）")
            }
            val rad = Math.toRadians(projectile.facing.toDouble())
            Vector2f(cos(rad).toFloat(), sin(rad).toFloat())
        }

        val source = projectile.source
        val spec = PositronShockwaveDifficulty.resolve(source)
        PositronShockwaveFuseScript.detonate(
            engine, projectile, loc, dir, source, spec,
            fuseOwner = source?.owner ?: projectile.owner,
            detonateTelemetryKey = PositronShockwaveFuseScript.TELEMETRY_DETONATE_IMPACT,
        )
    }

    companion object {
        private val log = Global.getLogger(PositronShockwaveOnHitEffect::class.java)

        /** 近零速兜底的一次性 WARN 闸（罕见路径，不刷屏）。 */
        @Volatile
        private var warnedZeroVelocity = false
    }
}
