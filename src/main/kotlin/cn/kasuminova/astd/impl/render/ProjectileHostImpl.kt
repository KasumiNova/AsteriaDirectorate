package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ProjectileHost
import com.fs.starfarer.api.combat.DamagingProjectileAPI

/**
 * [ProjectileHost] 实现：包裹一枚弹体。[hostId] 用弹体身份哈希，供日志定位与驱动去重。
 */
class ProjectileHostImpl(
    override val projectile: DamagingProjectileAPI,
) : ProjectileHost {

    override val hostId: String = "proj@" + System.identityHashCode(projectile)
}
