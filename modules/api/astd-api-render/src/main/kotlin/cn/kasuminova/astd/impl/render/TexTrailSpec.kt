package cn.kasuminova.astd.impl.render

/**
 * 贴图拖尾（DSL `texTrail{}`）的规格：拖尾主体层，横向图案来自平铺滚动贴图。
 *
 * 复刻 MagicTrail 语义：带体几何由 CPU 折线生成（历史中线等宽采样 + 逐节点渐变色），横向图案来自
 * 平铺滚动贴图（对标 astd_trails_* 素材：贴图 X=横向、Y=带长向，形在 alpha 通道，RGB 近白由节点色染色）。
 * 片元着色器只做「采样 × 顶点色」，图案质量完全由贴图保证。
 */
data class TexTrailSpec(
    /** 拖尾全宽（世界单位）。 */
    val width: Float,
    /** 平铺图案贴图路径（64×N，X=横向、Y=带长向，Y 向 REPEAT 平铺）。 */
    val texturePath: String,
    /** 叠层序号：同弹体多条贴图拖尾的绘制先后（1 垫底、2 其上，以此类推）。 */
    val layer: Int = 1,
    /** 头部颜色。 */
    val headColor: ASTDColor,
    /** 中段色（t=[midT] 处）；null 退化为两色插值。 */
    val midColor: ASTDColor? = null,
    /** 中段色在带长上的位置 0..1。 */
    val midT: Float = 0.25f,
    /** 尾部颜色。 */
    val tailColor: ASTDColor,
    /** 节点数下限（沿带长均匀分布，弯道平滑度）；实际渲染节点数按可见带长动态细分（见 [dynamicTexTrailNodeCount]）。 */
    val nodeCount: Int = 24,
    /** 图案沿带长的平铺周期（世界单位，即 MagicTrail 的 textureLoopLength）。 */
    val tileLength: Float = 180f,
    /** 图案沿带长滚动速度（世界单位/秒，0 不滚动；/tileLength 即每秒滚动整贴图次数）。 */
    val scrollSpeed: Float = 0f,
    /** 带体整体向后退的距离（世界单位）：带体头部亮端退到弹头网格之后，让弹头尖在带体前清晰露出。 */
    val recede: Float = 0f,
    /** 横向扰动峰值振幅（世界单位，0 = 不扰动）：复刻 MagicTrail dispersion，正弦叠加横向漂移让带体散开摆动。 */
    val wobbleAmplitude: Float = 0f,
    /** 扰动主波长（带长向，世界单位）；第二分量取黄金比频率，两频率不可约、图案沿带长不自重复。 */
    val wobbleWavelength: Float = 90f,
    /** 扰动图案沿带长平移速度（世界单位/秒，0 静止；语义对齐 [scrollSpeed]，负值反向爬行）。 */
    val wobbleScroll: Float = 0f,
    /** 扰动初始相位（弧度）：错开同弹体多条叠层的扰动图案，避免两层同相摆动。 */
    val wobblePhase: Float = 0f,
    /**
     * 逐节点寿命覆写（秒，0 = 自动取驱动的 [cn.kasuminova.astd.api.render.FrameState.trailLifetimeSeconds] 估算值）。
     * 节点年龄 = 当前时间 − 出生时刻（历史点时间戳沿带长插值），年龄/寿命驱动时变效果（含消散）。
     */
    val lifetimeSeconds: Float = 0f,
    /** 年龄进度从多少开始消散（0..1，默认 0.6）：之前 alpha 不衰减，之后线性降到 0。 */
    val dissolveStart: Float = 0.6f,
    /**
     * 逐段随机扭转幅度（度，0 = 关闭）：带体在距头弧长上取平滑值噪声 ∈ [-twistMaxAngleDeg, +twistMaxAngleDeg]
     * （种子挂弧长桶而非历史点时间戳——弧长在带体系跨帧稳定，带体伸缩/物质向尾流动时扭转图案不闪不爬），
     * 相邻桶间 smoothstep 过渡——前后段自动衔接。
     */
    val twistMaxAngleDeg: Float = 0f,
    /** 扭转噪声的空间波长（世界单位，沿带长）：0 = 取 [tileLength]（扭转图案与贴图平铺周期同频）。 */
    val twistWavelength: Float = 0f,
    /** 扭转随年龄的累积角速度（度/秒，可负）：段角 = 弧长噪声 + 年龄 × 本值，0 = 不随时间扭转。 */
    val twistTurnDegPerSec: Float = 0f,
) {
    /** 扭转噪声实际波长：[twistWavelength] ≤ 0 时回落 [tileLength]。 */
    fun effectiveTwistWavelength(): Float = if (twistWavelength > 0f) twistWavelength else tileLength
}
