package cn.kasuminova.astd.api.render

/**
 * 光束宿主：一棵光束特效树所依附的对象（一条 [com.fs.starfarer.api.combat.BeamAPI] 或一把发射武器），
 * 使核心模型宿主中立（设计 D6，与 [ProjectileHost] 对称）。
 *
 * 动机：束体节点 95% 只读 [RenderContext.frame]（起点/朝向/长度/firing/strength 都在 [FrameState]），
 * 但少数节点需要一个宿主特有的、每帧近乎恒定的量——束体基宽（原版 `beam.width`）。核心/辉光束体据此按
 * strength 缩放宽度，而它不属于逐帧几何，故走本接口而非 [FrameState]。
 *
 * 命中目标/护盾命中等"每帧接触查询"（StellarJet EMP 弧选点用）随其迁移（P7.3）再加入本接口，避免预先声明无实现成员。
 */
interface BeamHost : RenderHost {

    /** 束体基宽（原版 `beam.width`）。核心/辉光束体据此按 strength 缩放。宿主提供，通常恒定。 */
    val baseWidth: Float
}
