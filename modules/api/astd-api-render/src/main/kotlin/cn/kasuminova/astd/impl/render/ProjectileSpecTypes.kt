package cn.kasuminova.astd.impl.render

/**
 * 弹体 VFX 共享 spec 数据类型（几何层入参）。
 *
 * 贴图化（P2）后仅余三类：颜色、拖尾风格声明（texTrail 贴图拖尾与 bloom 弹头的基宽/基色来源 +
 * 驱动锚点）、弹头层参数。旧 glow/body/ribbon 网格层 spec 已随退役渲染栈删除。
 */

data class ASTDColor(val red: Float, val green: Float, val blue: Float, val alpha: Float) {
    fun scaledAlpha(scale: Float): ASTDColor = copy(alpha = (alpha * scale).coerceIn(0f, 1f))
}

/**
 * 拖尾风格声明：startWidth 喂 [ASTDProjectileVfxLayout.widthBase] 与驱动锚点（可视长度/历史窗口基准），
 * startColor/startEmissive/endColor 喂弹头壳配色（[ASTDProjectileVfxLayout.headColors]）。
 */
data class ASTDTrailLayerSpec(
    val startWidth: Float,
    val length: Float,
    val startColor: ASTDColor,
    val startEmissive: ASTDColor,
    val endColor: ASTDColor,
)

/** 弹头层：收拢亮头的长宽/肩后比/壳三色（内→中→外）/模糊/整体透明。 */
data class ASTDProjectileVfxHeadLayerSpec(
    val enabled: Boolean = true,
    val length: Float,
    val width: Float,
    val shoulderRatio: Float,
    val rearRatio: Float,
    val shellColorStart: ASTDColor,
    val shellColorMid: ASTDColor,
    val shellColorEnd: ASTDColor,
    val blur: Float,
    val alphaScale: Float,
)
