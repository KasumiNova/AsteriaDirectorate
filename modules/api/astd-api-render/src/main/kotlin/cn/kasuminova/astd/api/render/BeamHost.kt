package cn.kasuminova.astd.api.render

/**
 * 光束特效树的宿主，提供光束级常量。
 *
 * 逐帧几何与生命周期数据由 [FrameState] 提供。
 */
interface BeamHost : RenderHost {

    /** 束体基宽（原版 `beam.width`）。核心/辉光束体据此按 strength 缩放。宿主提供，通常恒定。 */
    val baseWidth: Float
}
