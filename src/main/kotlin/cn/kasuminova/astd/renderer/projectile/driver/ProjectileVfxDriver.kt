package cn.kasuminova.astd.renderer.projectile.driver

import com.fs.starfarer.api.combat.CombatEngineAPI

/** 驱动生命周期阶段。 */
enum class ProjectileVfxDriverState { Active, Fading, Removed }

/**
 * 驱动单帧遥测（自动化取证用，见 ASTDAutomationCombatPlugin）：
 * 最近一次成功推进帧的核心观测量。
 *
 * @param elapsed 驱动累计推进秒数（含淡出期）。
 * @param visibleLength 该帧拖尾可视长度（世界单位）。
 * @param beamAlpha 该帧整体透明度系数。
 * @param worldUnitsPerPixel 该帧世界/像素换算比例。
 */
data class ProjectileVfxDriverTelemetry(
    val elapsed: Float,
    val visibleLength: Float,
    val beamAlpha: Float,
    val worldUnitsPerPixel: Float,
)

/**
 * 单个宿主(弹体)的 VFX 驱动：持有一棵 RenderEntity 树,每帧算 [cn.kasuminova.astd.api.render.FrameState]
 * 并推进树的生命周期;宿主消失→触发淡出;淡出结束→释放。取代旧 `ASTDProjectileVfxRenderGraph` 的驱动角色
 * （逻辑层,接口 + Impl,不用 Manager/Runtime 等禁用词）。
 */
interface ProjectileVfxDriver {

    /** 当前阶段;为 [ProjectileVfxDriverState.Removed] 时可从持有方剔除。 */
    val state: ProjectileVfxDriverState

    /** 最近一次成功推进帧的遥测；尚未产生过帧时为 null。 */
    val telemetry: ProjectileVfxDriverTelemetry?

    /** 每帧推进。 */
    fun advance(engine: CombatEngineAPI, amount: Float)

    /** 立即释放后端句柄并置为 Removed(战斗结束清理用)。 */
    fun dispose()
}
