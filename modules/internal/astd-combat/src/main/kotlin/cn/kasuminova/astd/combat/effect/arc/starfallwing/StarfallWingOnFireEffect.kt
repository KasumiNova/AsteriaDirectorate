package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f
import java.util.IdentityHashMap

/**
 * 坠星残翼的开火路由（挂主弹 spec 的 `onFireEffect`）：
 *
 * - 登记弹体状态（穿透结算 / 子射弹散发节拍），状态存 `engine.customData`
 *   （OnFire 插件实例为 spec 级共享，不能持有单武器状态；坠星残响同款判例）；
 * - 弹体 VFX 树登记（[ProjectileVfxDriverPlugin.track]，主弹紫色锥形 + 碎片航迹 + 马赫环）。
 *
 * 主弹 collisionClass=NONE：原版触碰结算恒不触发，护盾阻挡/穿盾/穿船体高频伤害
 * 全部由 [StarfallWingWeaponEffect] 的逐帧脚本碰撞判定承担。
 */
class StarfallWingOnFireEffect : OnFireEffectPlugin {

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        if (engine.isPaused) return
        projectileStates(engine)[projectile] = ProjectileState(
            ownerWeapon = weapon, isMote = false, lastPierceLocation = Vector2f(projectile.location),
        )
        ProjectileVfxDriverPlugin.track(engine, projectile, StarfallWingTuning.SHOT_SPEC_ID)
    }

    /** 单枚弹体的脚本结算状态（穿透/散发节拍；子射弹由散发时登记，[isMote]=true）。 */
    class ProjectileState(
        val ownerWeapon: WeaponAPI,
        val isMote: Boolean,
        var pierceTimer: Float = 0f,
        var moteTimer: Float = 0f,
        /** 上一帧扫掠起点（登记时初始化为出生点，保证首帧扫全段而非零长段）。 */
        var lastPierceLocation: Vector2f? = null,
        /** 单次穿越结算闩锁（装甲格逐格一次 / 非舰船目标穿越一次 / 护盾首触补拍判定）。 */
        val passContacts: PiercePassTracker = PiercePassTracker(),
    )

    companion object {
        /** engine.customData 键：弹体（IdentityHashMap）→ 脚本结算状态。 */
        private const val KEY_PROJECTILE_STATES = "astd_starfall_wing_projectile_states"

        @Suppress("UNCHECKED_CAST")
        fun projectileStates(engine: CombatEngineAPI): IdentityHashMap<DamagingProjectileAPI, ProjectileState> {
            return engine.customData.getOrPut(KEY_PROJECTILE_STATES) {
                IdentityHashMap<DamagingProjectileAPI, ProjectileState>()
            } as IdentityHashMap<DamagingProjectileAPI, ProjectileState>
        }
    }
}
