package cn.kasuminova.astd.combat.effect.arc.geminidem

import cn.kasuminova.astd.renderer.effect.system.GeminiDemRackVisuals
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI

/**
 * 双子星 DEM 发射架的 everyFrameEffect：每帧驱动 [GeminiDemRackVisuals]
 * （逐管弹体染色 + 红/蓝光效叠加的登记与渲染安装）。
 */
class GeminiDemRackEveryFrameEffect : EveryFrameWeaponEffectPlugin {

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        lockTriggerWhenSalvoIncomplete(weapon)
        GeminiDemRackVisuals.onWeaponFrame(engine, weapon)
    }

    companion object {
        /**
         * 一次完整齐射消耗的 dummy 弹药数（双管各一发，与 [GeminiDemSalvoOnFireEffect] 的双弹头齐射对应）。
         * 弹药低于此值时开火只会打出残缺齐射（onFire 的回声发去重依赖完整双发），必须锁扳机。
         */
        private const val SALVO_AMMO_COST = 2

        /**
         * 锁扳机用的冷却垫：必须小于 weapon_data 的 burst delay（0.1s），
         * 否则会把进行中合法连发的第二发一并掐掉（连发第一发 dummy 击发后弹药即短暂低于 [SALVO_AMMO_COST]）。
         */
        private const val FIRE_LOCK_COOLDOWN = 0.05f

        /**
         * 剩余弹药不足一次完整齐射时压住扳机（每帧把冷却垫到 [FIRE_LOCK_COOLDOWN]）：
         * 弹药回充到够齐射后最多 0.05s 即可开火，玩家几乎无感。
         */
        private fun lockTriggerWhenSalvoIncomplete(weapon: WeaponAPI) {
            if (weapon.ammo >= SALVO_AMMO_COST) return
            if (weapon.cooldownRemaining >= FIRE_LOCK_COOLDOWN) return
            weapon.setRemainingCooldownTo(FIRE_LOCK_COOLDOWN)
        }
    }
}
