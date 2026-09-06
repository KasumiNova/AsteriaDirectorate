package cn.kasuminova.astd.impl.render

/**
 * Static Trail 拖尾层（DSL `staticTrail{}`）的规格：拖尾主体层，由 BoxUtil Static Trail 系统托管渲染。
 *
 * 渲染模型（BoxUtil 1.6.0）：GPU 实例化带体 + 每 trailData 一块环形 vRAM 池；系统每帧回调 tracker
 * 记录节点（位置/朝向/节点色），着色器按节点时间戳在三段时长（fadeIn/full/fadeOut）内推进生命，
 * 头宽 [width] → 尾宽 [width]×[tailWidthRatio] 随生命线性收细，颜色 [headColor]→[tailColor] 两段渐变。
 * 贴图约定：X=带长向（REPEAT 平铺，[tileLength] 为一周期世界单位）、Y=横向，形在 alpha 通道、RGB 近白。
 */
data class StaticTrailSpec(
    /** 平铺图案贴图路径（N×64，X=带长向、Y=横向）。 */
    val texturePath: String,
    /** 叠层序号：同弹体多条拖尾的声明序（1 垫底、2 其上；additive 混合下仅作树内组织语义）。 */
    val layer: Int = 1,
    /** 拖尾头部全宽（世界单位；尾部 = 本值 × [tailWidthRatio]，随生命线性收细）。 */
    val width: Float,
    /** 尾宽比（0..1）：带尾宽度 = [width] × 本值。 */
    val tailWidthRatio: Float = 0.35f,
    /** 头部颜色（最新节点）。 */
    val headColor: ASTDColor,
    /** 尾部颜色（最旧节点）。 */
    val tailColor: ASTDColor,
    /** 预期带长（世界单位）：折算节点总寿命 = 带长 / 弹体速度，三段时长按比例切分（见实现侧工厂）。 */
    val bandLength: Float,
    /** 图案沿带长的平铺周期（世界单位）。 */
    val tileLength: Float = 180f,
    /** 图案沿带长滚动速度（世界单位/秒，0 不滚动；/tileLength 即每秒滚动整贴图次数）。 */
    val scrollSpeed: Float = 0f,
    /** 带体整体向后退的距离（世界单位）：带体头部亮端退到原版螺栓弹头之后，让弹头尖在带体前露出。 */
    val recede: Float = 0f,
    /** 横向扰动峰值振幅（世界单位，0 = 不扰动）：tracker 记录节点时按逻辑时间横向偏移，带体呈蛇行。 */
    val wobbleAmplitude: Float = 0f,
    /** 扰动爬行速度（世界单位/秒，0 静止）。 */
    val wobbleScroll: Float = 0f,
    /** 扰动初始相位（弧度）：错开同弹体多条叠层的扰动图案。 */
    val wobblePhase: Float = 0f,
    /** 扰动主波长（世界单位）：爬行频率 = [wobbleScroll] / 本值（周期/秒）。 */
    val wobbleWavelength: Float = 90f,
    /** bloom 发光强度（0..1，进 BoxUtil emissive → bloom G-buffer）。 */
    val glowPower: Float = 1f,
    /**
     * 拖尾锚点前移覆写（世界单位）：null = 自动取弹体 spec.length/2（对齐原版螺栓视觉头部）。
     * 由 DSL scope 在 build 时从 lifecycle.headLead 统一盖印，层作者不直接填。
     */
    val headLeadWorld: Float? = null,
)
