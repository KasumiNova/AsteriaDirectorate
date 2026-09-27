package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.combat.effect.generic.CombatVfxBootstrap
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI

/**
 * 坠星残响的武器级每帧效果（挂 `.wpn` 的 `everyFrameEffect`）：
 *
 * - 代行 VFX bootstrap（`.wpn` 只有一个 everyFrame 槽，本武器独占，必须自行
 *   [CombatVfxBootstrap.ensureInstalled]，否则弹体 VFX 管线不启动）；
 * - 弹匣禁射闸（隐藏机制）：弹药 < [StarfallEchoTuning.AMMO_GATE] 且不在连射中时逐帧
 *   `setForceNoFireOneFrame(true)`；连射进行中放行（否则会切断已起射的 5 发 burst）。
 *
 * 弹体航迹特效（同色三角碎片 / 马赫环）已迁移进 ProjectileVfx DSL 持续发射器层
 * （`shardWake`/`machRing`，见 ProjectileVfxSpecs 的 starfallEchoShot/starfallEchoFinalShot），
 * 由 RenderEntity 树管线驱动、统一粒子池渲染，不再由本插件扫描弹体逐帧发射。
 */
class StarfallEchoWeaponEffect : EveryFrameWeaponEffectPlugin {

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        CombatVfxBootstrap.ensureInstalled(engine)

        // 弹匣禁射闸（隐藏机制）
        if (!StarfallEchoTuning.canFire(weapon.ammo, weapon.isInBurst)) {
            weapon.setForceNoFireOneFrame(true)
        }
    }
}
