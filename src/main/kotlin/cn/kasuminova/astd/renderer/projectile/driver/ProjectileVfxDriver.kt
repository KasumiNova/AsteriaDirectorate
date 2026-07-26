package cn.kasuminova.astd.renderer.projectile.driver

import com.fs.starfarer.api.combat.CombatEngineAPI

/** 驱动生命周期阶段。 */
enum class ProjectileVfxDriverState { Active, Fading, Removed }

/**
 * 单个宿主(弹体)的 VFX 驱动：持有一棵 RenderEntity 树,每帧算 [cn.kasuminova.astd.api.render.FrameState]
 * 并推进树的生命周期;宿主消失→触发淡出;淡出结束→释放。取代旧 `ASTDProjectileVfxRenderGraph` 的驱动角色
 * （逻辑层,接口 + Impl,不用 Manager/Runtime 等禁用词）。
 */
interface ProjectileVfxDriver {

    /** 当前阶段;为 [ProjectileVfxDriverState.Removed] 时可从持有方剔除。 */
    val state: ProjectileVfxDriverState

    /** 每帧推进。 */
    fun advance(engine: CombatEngineAPI, amount: Float)

    /** 立即释放后端句柄并置为 Removed(战斗结束清理用)。 */
    fun dispose()
}
