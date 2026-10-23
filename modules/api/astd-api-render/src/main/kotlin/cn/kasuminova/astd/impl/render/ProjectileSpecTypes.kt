package cn.kasuminova.astd.impl.render

/** 渲染使用的 RGBA 颜色，各分量范围为 0 至 1。 */

data class ASTDColor(val red: Float, val green: Float, val blue: Float, val alpha: Float) {

    constructor(combined: Long) : this(
        red = ((combined shr 16) and 0xFF) / 255f,
        green = ((combined shr 8) and 0xFF) / 255f,
        blue = (combined and 0xFF) / 255f,
        alpha = ((combined shr 24) and 0xFF) / 255f,
    )

    fun scaledAlpha(scale: Float): ASTDColor = copy(alpha = (alpha * scale).coerceIn(0f, 1f))
}
