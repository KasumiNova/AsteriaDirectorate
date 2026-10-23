package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ProjectileHost
import com.fs.starfarer.api.combat.DamagingProjectileAPI

class ProjectileHostImpl(
    override val projectile: DamagingProjectileAPI,
) : ProjectileHost {

    override val hostId: String = "proj@" + System.identityHashCode(projectile)
}
