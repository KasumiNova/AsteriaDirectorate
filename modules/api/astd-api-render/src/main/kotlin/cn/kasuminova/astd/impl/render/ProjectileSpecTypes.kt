package cn.kasuminova.astd.impl.render

/**
 * 弹体 VFX 共享 spec 数据类型（几何层入参）。
 *
 * Static Trail 迁移（2026-09）后仅余颜色类型；拖尾层 spec 见 [StaticTrailSpec]，
 * 光斑/锚点电弧见 BoxFlareSpec/AnchorArcSpec。旧拖尾风格声明与代码弹头层 spec
 * 已随自研 texTrail/head 渲染栈删除（aod7 弹头回归原版弹体渲染）。
 */

data class ASTDColor(val red: Float, val green: Float, val blue: Float, val alpha: Float) {

    constructor(combined: Long) : this(
        red = ((combined shr 16) and 0xFF) / 255f,
        green = ((combined shr 8) and 0xFF) / 255f,
        blue = (combined and 0xFF) / 255f,
        alpha = ((combined shr 24) and 0xFF) / 255f,
    )

    fun scaledAlpha(scale: Float): ASTDColor = copy(alpha = (alpha * scale).coerceIn(0f, 1f))
}
