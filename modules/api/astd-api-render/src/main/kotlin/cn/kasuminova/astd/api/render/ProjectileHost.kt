package cn.kasuminova.astd.api.render

import com.fs.starfarer.api.combat.DamagingProjectileAPI

/**
 * 弹体特效树的宿主。逐帧几何与生命周期数据由 [FrameState] 提供。
 */
interface ProjectileHost : RenderHost {

    /** 所依附的弹体。 */
    val projectile: DamagingProjectileAPI
}
