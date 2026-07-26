package cn.kasuminova.astd.renderer.beam.driver

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import org.lwjgl.util.vector.Vector2f

/** 光束驱动生命周期阶段。 */
enum class BeamVfxDriverState { Active, Removed }

/**
 * 光束每帧输入：宿主插件（BeamEffectPlugin / EveryFrameWeaponEffectPlugin）把几何与状态算好后传入。
 * `start/facing/length/endpoint` 是几何，`firing/strength/fadeMul` 是状态。
 *
 * 束体常驻/复火由束体节点的心跳定时器承担（active 刷新常驻、停火不刷新自淡、复火重建，无 reset+重建）。
 * [fadeMul] 是与 [strength] 正交的淡出包络：Psi 恒 1（纯靠心跳淡出），GravityCollapse 停火时逐帧从 1 降到 0，
 * 令束体在保持 strength 观感的同时整体变细/变淡到消失（对齐旧 fade 平滑淡出，见迁移计划 §1.2-5）。
 */
class BeamFrame(
    /** 炮口/起点（原版 `beam.from`）。 */
    val start: Vector2f,
    /** 朝向角（度，`from`→`to`）。 */
    val facing: Float,
    /** 束长（起点到命中/末端）。 */
    val length: Float,
    /** 命中端（原版 `beam.to`）；无固定末端时为 null。供 impact 类节点选点。 */
    val endpoint: Vector2f?,
    /** 是否 firing（束体活动态）。false 时束体淡出、命中特效停抛。 */
    val firing: Boolean,
    /** 连续强度 0..1（ramp/level），驱动束体颜色/宽度与命中特效频次。 */
    val strength: Float,
    /** 淡出包络 0..1（默认 1）。停火淡出时由宿主逐帧降到 0，令束体整体变细/变淡、沿束粒子收敛。 */
    val fadeMul: Float = 1f,
    /** 本帧命中目标（原版 raycast 结果）；未命中为 null。供命中节点选锚点。Psi/GravityCollapse 不用（默认 null）。 */
    val hitTarget: CombatEntityAPI? = null,
    /** 本帧是否护盾命中；影响命中特效配色。默认 false。 */
    val isShieldHit: Boolean = false,
)

/**
 * 单个宿主（一条光束 / 一把武器）的光束 VFX 驱动：持一棵 [cn.kasuminova.astd.api.render.RenderEntity] 树，
 * 每帧把 [BeamFrame] 折成宿主中立的 [cn.kasuminova.astd.api.render.FrameState] 并推进树（逻辑层，接口 + Impl，
 * 不用 Manager/Runtime 等禁用词）。与弹体驱动的区别：几何不自算，由宿主喂入；淡出走节点心跳而非驱动单向 fade，
 * 故驱动本体极薄——不持有飞行历史、不做飞行布局。
 */
interface BeamVfxDriver {

    /** 当前阶段；[BeamVfxDriverState.Removed] 时可从持有方剔除。 */
    val state: BeamVfxDriverState

    /** 每帧推进：喂入本帧几何+状态。 */
    fun advance(engine: CombatEngineAPI, frame: BeamFrame, amount: Float)

    /** 立即释放后端句柄并置为 Removed（宿主永久消失/战斗结束清理用）。 */
    fun dispose()
}
