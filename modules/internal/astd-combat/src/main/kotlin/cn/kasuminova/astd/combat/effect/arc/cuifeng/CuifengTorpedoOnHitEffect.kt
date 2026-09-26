package cn.kasuminova.astd.combat.effect.arc.cuifeng

import cn.kasuminova.astd.api.combat.CuifengTorpedoStrike
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 摧锋鱼雷的命中回调薄入口：挂 `.proj` 的 `onHitEffect`。
 *
 * 职责仅三件事：引擎暂停跳过 → 命中点回退（某些实体命中回调 point 可为 null，
 * 回退弹体当前位置，对齐既有命中回调样板）→ 委托 [CuifengTorpedoStrike] 一次性结算
 * （面板 sanitize / 难度取值 / 自适应增伤 / 硬辐推进 / AOE / 特效全部在结算体内，
 * 供桩引擎单测完整驱动）。
 */
class CuifengTorpedoOnHitEffect : OnHitEffectPlugin {

    /** 结算入口（接口类型持有，实现为无状态 object）。 */
    private val strike: CuifengTorpedoStrike = CuifengTorpedoStrikeImpl

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f?,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        if (engine.isPaused) return
        val hitPoint = point ?: projectile.location ?: return
        strike.strike(engine, projectile, target, hitPoint, shieldHit)
    }
}
