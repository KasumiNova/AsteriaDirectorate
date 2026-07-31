package cn.kasuminova.astd.api.render

import com.fs.starfarer.api.combat.DamagingProjectileAPI

/**
 * 弹体宿主（设计 D6）。除 [RenderHost] 的通用契约外，暴露底层 [DamagingProjectileAPI] 作为宿主特有查询的逃生口
 * ——位置/朝向/生命周期已由 [FrameState] 覆盖，这里只给需要 `cutTrails` 等原生操作的少数 root component。
 */
interface ProjectileHost : RenderHost {

    /** 所依附的弹体。 */
    val projectile: DamagingProjectileAPI
}
