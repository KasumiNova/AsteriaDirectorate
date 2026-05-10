package cn.kasuminova.astd.combat.effect.generic

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileVfxKeys

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI

/**
 * 一个"极轻量"的 everyFrameEffect：仅用于把战斗内的扫描式 VFX dispatcher 装进引擎一次。
 *
 * 设计目标：
 * - 即使 onFireEffect 因某些原因没有触发，也能看到弹体/曳光（避免透明 bulletSprite 导致完全不可见）。
 * - 装完插件后，本 effect 每帧开销≈一次 Map 查询。
 */
class CombatVfxBootstrapEveryFrameEffect : EveryFrameWeaponEffectPlugin {

    companion object {
        private val log = Global.getLogger(CombatVfxBootstrapEveryFrameEffect::class.java)
    }

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return

        if (engine.customData[ProjectileVfxKeys.ENGINE_LOG_BOOTSTRAP_ONCE] != true) {
            engine.customData[ProjectileVfxKeys.ENGINE_LOG_BOOTSTRAP_ONCE] = true
            val weaponId = try {
                weapon.spec?.weaponId
            } catch (_: Throwable) {
                null
            }
            val src = try {
                CombatVfxBootstrapEveryFrameEffect::class.java.protectionDomain?.codeSource?.location?.toString()
            } catch (_: Throwable) {
                null
            }
            log.info("[ASTD] CombatVfxBootstrapEveryFrameEffect running (weaponId=$weaponId, src=$src)")
        }

        CombatVfxBootstrap.ensureInstalled(engine)
    }

}
