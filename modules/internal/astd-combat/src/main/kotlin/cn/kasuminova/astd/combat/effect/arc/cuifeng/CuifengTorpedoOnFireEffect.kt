package cn.kasuminova.astd.combat.effect.arc.cuifeng

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileSpecOnFireDispatcher
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 摧锋鱼雷的发射回调（blue/30-superlative.md §实装落点）：挂 `.proj` 的 `onFireEffect`，
 * 单一挂载点一次办完两件事——
 * 1. 委托现成 [ProjectileSpecOnFireDispatcher] 完成弹体 VFX 登记（组合而非复制，
 *    去重由 dispatcher 内部保证）；
 * 2. 对导弹实体安装 [CuifengTorpedoAI]（`missile.setMissileAI(...)`），
 *    捕获发射点与武器面板射程作为二段式速度曲线的基准。
 *
 * 引擎辉光/尾焰隐藏由 `.proj` 数据面承担（noEngineGlowTime=999 + engineSlots 空），
 * 本类不做视觉兜底——`.wpn` 的 everyFrameEffect 已挂 `CombatVfxBootstrapEveryFrameEffect` 安全网。
 */
class CuifengTorpedoOnFireEffect : OnFireEffectPlugin {

    private val log = Global.getLogger(CuifengTorpedoOnFireEffect::class.java)

    /** VFX 登记委托实例（dispatcher 无状态，组合复用）。 */
    private val vfxDispatcher = ProjectileSpecOnFireDispatcher()

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        vfxDispatcher.onFire(projectile, weapon, engine)

        val missile = projectile as? MissileAPI
        if (missile == null) {
            log.warn("摧锋 onFire 拿到非导弹实体（spec=${projectile.projectileSpecId}），属配置错误，AI 未安装")
            return
        }
        missile.missileAI = CuifengTorpedoAI(missile, Vector2f(missile.location), weapon.range)
    }
}
