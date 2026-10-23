package cn.kasuminova.astd.impl.render

/**
 * BoxUtil Static Trail 拖尾层的参数。
 *
 * 带宽、颜色和透明度随节点寿命变化。贴图 X 轴为带长方向并使用 REPEAT 平铺，[tileLength] 与 [scrollSpeed] 使用世界单位。
 */
data class StaticTrailSpec(
    /** 平铺图案贴图路径（N×64，X=带长向、Y=横向）。 */
    val texturePath: String,
    /** 同一弹体多条拖尾的组织序号。 */
    val layer: Int = 1,
    /** 拖尾头部全宽，尾部宽度为该值乘以 [tailWidthRatio]。 */
    val width: Float,
    /** 尾宽比，范围为 0 至 1。 */
    val tailWidthRatio: Float = 0.35f,
    /** 最新节点的颜色。 */
    val headColor: ASTDColor,
    /** 最旧节点的颜色。 */
    val tailColor: ASTDColor,
    /** 预期带长，节点总寿命由带长除以弹体速度得到。 */
    val bandLength: Float,
    /** 图案平铺周期。 */
    val tileLength: Float = 180f,
    /** 图案滚动速度，0 表示不滚动。 */
    val scrollSpeed: Float = 0f,
    /** 带体向后偏移的距离；null 时由渲染实现根据弹体长度计算。 */
    val recede: Float? = null,
    /** bloom 发光强度，范围为 0 至 1；0 表示不发光。 */
    val glowPower: Float = 0f,
    /** 拖尾锚点向前偏移的距离；null 表示不偏移，由 DSL 生命周期参数设置。 */
    val headLeadWorld: Float? = null,
    /**
     * 最新节点的随机自旋角速度范围，单位为度每秒。
     * 必须同时设置 [velocityInRange]，否则自旋不生效。
     */
    val angularInRange: ClosedFloatingPointRange<Float>? = null,
    /**
     * 最旧节点的随机自旋角速度范围，单位为度每秒。
     * 必须同时设置 [velocityOutRange]，否则自旋不生效。
     */
    val angularOutRange: ClosedFloatingPointRange<Float>? = null,
    /** 最新节点的随机漂移速度范围。 */
    val velocityInRange: TrailDriftRange? = null,
    /** 最旧节点的随机漂移速度范围。 */
    val velocityOutRange: TrailDriftRange? = null,
)

/**
 * Static Trail 节点漂移速度范围（世界单位/秒，基于带体朝向）：
 * 每节点在 {minX..maxX, minY..maxY} 内取随机二维漂移速度，随节点存活时间累积位移。
 */
data class TrailDriftRange(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)
