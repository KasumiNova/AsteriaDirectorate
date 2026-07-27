package cn.kasuminova.astd.impl.render

import org.lwjgl.util.vector.Vector2f

/**
 * 网格几何层（bloom 弹头）的每帧渲染上下文：世界锚点 + 绘制朝向 + 强度 + 像素换算。
 * 由宿主中立的 FrameState 还原而来（MeshRenderComponents.toRenderContext）。
 */
data class ASTDProjectileVfxRenderContext(
    val location: Vector2f,
    val renderFacing: Float,
    val beamAlpha: Float,
    val worldUnitsPerPixel: Float = 1f,
)

/** 单层的线性淡出状态：begin 后随 advance 递减 alpha，complete 表示淡完。 */
internal class ASTDProjectileVfxLayerFadeState {
    private var active = false
    private var seconds = 0f
    private var elapsed = 0f

    fun begin(seconds: Float) {
        active = true
        this.seconds = seconds.coerceAtLeast(0f)
        elapsed = 0f
    }

    fun advance(amount: Float) {
        if (active) elapsed += amount.coerceAtLeast(0f)
    }

    fun alpha(): Float {
        if (!active) return 1f
        if (seconds <= 0f) return 0f
        return (1f - elapsed / seconds).coerceIn(0f, 1f)
    }

    fun complete(): Boolean = active && alpha() <= 0f
}
