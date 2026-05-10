package cn.kasuminova.astd.combat.effect.generic.projectile

import cn.kasuminova.astd.combat.effect.arc.rare.Rct6TerminalCorrectionAI
import cn.kasuminova.astd.combat.effect.arc.signature.tsm.Tsm2TerminalSprintAI
import cn.kasuminova.astd.combat.effect.lens.signature.singularity.SingularityRetargetMissileAI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI

/**
 * 统一的“按 projectileSpecId 注入 MissileAI”的入口。
 *
 * 背景：
 * - onFireDispatcher 与 spawnVfxDispatcher 是两条不同的链路。
 * - 如果 scan 先处理/写标记导致 onFire return，导弹 AI 就可能永远不生效。
 * - 因此 AI 注入需要独立的去重标记，并允许两条链路都调用。
 */
internal object ProjectileMissileAiInjector {

    fun ensureInstalled(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
        val missile = projectile as? MissileAPI ?: return
        val projId = projectile.projectileSpecId ?: return
        if (projId.isBlank()) return

        // 去重：避免每帧/多路径重复覆盖 missileAI（可能重置 AI 内部状态）。
        if (ProjectileVfxDispatchState.isMarked(engine, projectile, ProjectileVfxKeys.PROJECTILE_AI_INSTALLED_MARK)) return

        when (projId) {
            // 奇点投射器：2s 重新定向 + 重定向期减速 + 高幅能穿盾（对盾 0 伤害）
            "astd_sgl8_swarm",
            "astd_tsm2_missile" -> {
                missile.missileAI = SingularityRetargetMissileAI(missile)
            }

            // RCT-6：末端修正导弹
            "astd_rct6_torp" -> {
                missile.missileAI = Rct6TerminalCorrectionAI(missile)
            }

            // TSM-Ω：两段式终端冲刺（TSM-2 已被“奇点投射器”复用）
            "astd_tsm_omega_missile" -> {
                missile.missileAI = Tsm2TerminalSprintAI(missile)
            }
        }

        ProjectileVfxDispatchState.mark(engine, projectile, ProjectileVfxKeys.PROJECTILE_AI_INSTALLED_MARK)
    }
}
