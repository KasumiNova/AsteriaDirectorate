package cn.kasuminova.astd.combat.effect.lens.iceshard

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileSpecOnFireDispatcher
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI

/**
 * 源生冰晶 MIRV 母弹的发射回调（purple/30-superlative.md §实装）：挂 `.proj` 的 `onFireEffect`，
 * 单一挂载点一次办完两件事——
 * 1. 委托现成 [ProjectileSpecOnFireDispatcher] 完成弹体 VFX 登记（组合而非复制，
 *    去重由 dispatcher 内部保证）；
 * 2. 为母弹注册 [IceShardMirvSplitScript] 分裂引信（每弹一实例，正电子引信同款形态）。
 *
 * 母弹追踪沿用原版引导（missileType=MIRV → 原版 MirvAI 内嵌 MissileAI 承担引导段，
 * 其内建分裂经 behaviorSpec 的 minTimeToSplit=9999 关停），本类不安装自定义 AI。
 */
class IceShardMirvOnFireEffect : OnFireEffectPlugin {

    private val log = Global.getLogger(IceShardMirvOnFireEffect::class.java)

    /** VFX 登记委托实例（dispatcher 无状态，组合复用）。 */
    private val vfxDispatcher = ProjectileSpecOnFireDispatcher()

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        vfxDispatcher.onFire(projectile, weapon, engine)

        val missile = projectile as? MissileAPI
        if (missile == null) {
            log.warn("源生冰晶 onFire 拿到非导弹实体（spec=${projectile.projectileSpecId}），属配置错误，分裂引信未注册")
            return
        }
        engine.addPlugin(IceShardMirvSplitScript(missile, weapon.ship))
        engine.customData[TELEMETRY_FUSES_REGISTERED] =
            (engine.customData[TELEMETRY_FUSES_REGISTERED] as? Int ?: 0) + 1
    }

    companion object {
        /** 遥测键：已注册分裂引信的母弹数（dev 自动化烟测证据）。 */
        const val TELEMETRY_FUSES_REGISTERED = "astd_ice_shard_mirv_fuses_registered"
    }
}
