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
    /** bloom 发光强度（0..1，进 BoxUtil emissive → bloom G-buffer；默认 0 不发光——原版螺栓无辉光，按需开启）。 */
    val glowPower: Float = 0f,
    /**
     * 拖尾锚点前移覆写（世界单位）：null = 0（弹体前端 location 即螺栓视觉头部，无需再前移）。
     * 由 DSL scope 在 build 时从 lifecycle.headLead 统一盖印，层作者不直接填。
     */
    val headLeadWorld: Float? = null,
    /**
     * 每节点随机自旋角速度范围（度/秒，绕节点锚点，基于带体朝向）：带尾随存活时间扭转出弧度。
     * In=最新节点（life 0）、Out=最老节点（life 1），按节点生命插值；null 不启用。
     */
    val angularInRange: ClosedFloatingPointRange<Float>? = null,
    /** 见 [angularInRange]；尾端自旋（如电弧装饰带的卷曲感）只设本项即可。 */
    val angularOutRange: ClosedFloatingPointRange<Float>? = null,
    /** 每节点随机漂移速度范围（世界单位/秒，基于带体朝向）：带尾随存活时间漂离原航迹；null 不启用。 */
    val velocityInRange: TrailDriftRange? = null,
    /** 见 [velocityInRange]。 */
    val velocityOutRange: TrailDriftRange? = null,
)

/**
 * Static Trail 节点漂移速度范围（世界单位/秒，基于带体朝向）：
 * 每节点在 {minX..maxX, minY..maxY} 内取随机二维漂移速度，随节点存活时间累积位移。
 */
data class TrailDriftRange(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)
