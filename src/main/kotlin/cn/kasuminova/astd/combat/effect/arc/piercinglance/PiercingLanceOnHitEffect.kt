package cn.kasuminova.astd.combat.effect.arc.piercinglance

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 贯星之矛 `.proj` 侧命中回调（规格 09 §2.1）：引擎回调入口（引擎要求具体类实例化）。
 *
 * 职责仅为命中上下文校验与调用点：命中点 null 回退（陨石等路径，样板
 * HighFluxShieldPressureOnHitEffect）后委托 [PiercingLanceConeStrike] 完成
 * 难度取值、锥状冲击结算与玩家可见反馈；锥面波及其他目标不因本体状态（hulk/相位）豁免——
 * 本体由 filter 单独豁免，故此处不做 target 状态早退。
 */
class PiercingLanceOnHitEffect : OnHitEffectPlugin {

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI?,
        point: Vector2f?,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        if (engine.isPaused) return

        // 某些实体（陨石/小行星等）命中回调 point 可能为 null；回退弹体当前位置，仍不可得直接放弃。
        val hitPoint = point ?: projectile.location ?: return

        // null = 命中矢量不可得，已在 ConeStrike 内记 WARN（不静默吞机制）。
        val spec = PiercingLanceConeStrike.buildConeSpec(projectile, target, hitPoint) ?: return
        PiercingLanceConeStrike.resolve(engine, spec, target)
    }
}
