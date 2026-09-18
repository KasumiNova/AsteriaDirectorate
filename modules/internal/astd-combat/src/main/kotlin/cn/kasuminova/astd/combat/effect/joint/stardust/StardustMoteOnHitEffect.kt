package cn.kasuminova.astd.combat.effect.joint.stardust

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 星尘光尘命中结算（设计案 20-joint.md §武器；结构对照原版 MoteOnHitEffect，
 * 原版写死 motelauncher 查表不可复用，结算按本类自写）。
 *
 * 三分支：
 * - 导弹/战机：面板伤害照常碰撞结算，本效果追加 [StardustMoteTuning] 难度倍率的额外伤害；
 * - 舰船（非战机）：生成 EMP 电弧（伤害 = 面板等额 × 难度倍率，EMP 机制天然随机瘫痪武器/引擎）；
 *   被护盾阻挡时按目标硬辐能比例概率穿透（`spawnEmpArcPierceShields`，与原版光尘同一 API）。
 *
 * EMP 电弧颜色按弹体 spec id 分线（ARC 蓝 / LENS 紫，[StardustMoteTuning.colorForProj]）。
 * 小行星等场景物目标不做任何结算，仅播放命中音效（与原版 MoteOnHitEffect 一致）。
 */
class StardustMoteOnHitEffect : OnHitEffectPlugin {

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI
    ) {
        val source = projectile.source
        val values = StardustMoteTuning.resolve(DifficultyTuningImpl, isPlayer = source?.owner == 0)
        val base = projectile.damageAmount
        val projId = projectile.projectileSpecId
        if (projId == null) {
            StardustMoteTuning.warnOnce("nullProjId") { "星尘光尘命中结算收到 null projectileSpecId（配置异常），按 ARC 线配色兜底" }
        }
        val color = StardustMoteTuning.colorForProj(projId ?: StardustMoteIds.PROJ_ARC)

        when {
            target is MissileAPI -> {
                engine.applyDamage(
                    projectile, target, point, base * values.antiMissileBonus,
                    DamageType.ENERGY, 0f, false, false, source, true,
                )
            }
            target is ShipAPI && target.isFighter -> {
                engine.applyDamage(
                    projectile, target, point, base * values.antiFighterBonus,
                    DamageType.ENERGY, 0f, false, false, source, true,
                )
            }
            target is ShipAPI -> {
                // 护盾阻挡时按目标硬辐能比例概率穿透（设计案：硬辐能越高越容易被透盾）
                var pierceChance = 1f
                pierceChance *= target.mutableStats.dynamic.getValue("shield_pierced_mult", 1f)
                if (shieldHit) {
                    val tracker = target.fluxTracker
                    val hardFluxRatio = if (tracker != null && tracker.maxFlux > 0f) {
                        tracker.hardFlux / tracker.maxFlux
                    } else {
                        0f
                    }
                    pierceChance *= hardFluxRatio
                }
                if (!shieldHit || Math.random() < pierceChance) {
                    engine.spawnEmpArcPierceShields(
                        source,
                        point,
                        target,
                        target,
                        DamageType.ENERGY,
                        0f,
                        base * values.shipEmpMult,
                        100000f,
                        IMPACT_ARC_SOUND,
                        20f,
                        color,
                        Color(255, 255, 255, 255),
                    )
                }
            }
        }

        Global.getSoundPlayer().playSound(IMPACT_SOUND, 1f, 1f, point, Vector2f())
    }

    companion object {
        /** 命中音效（原版光尘资源，占位即正式）。 */
        private const val IMPACT_SOUND = "mote_attractor_impact_normal"
        private const val IMPACT_ARC_SOUND = "mote_attractor_impact_emp_arc"
    }
}
