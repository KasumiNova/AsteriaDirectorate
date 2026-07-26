package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ASTDProjectileHistoryNode
import org.lwjgl.util.vector.Vector2f

/**
 * 几何层的每帧渲染上下文。
 *
 * 弹体拖尾网格数学（各 Renderer 的 `*ForTests`）以此为主要入参。
 */
data class ASTDProjectileVfxRenderContext(
    val location: Vector2f,
    val velocityFacing: Float,
    val projectileFacing: Float,
    val renderFacing: Float,
    val elapsed: Float,
    val logicElapsed: Float = elapsed,
    val flightProgress: Float,
    val dissolve: Float,
    val visibleLength: Float,
    val beamAlpha: Float,
    val historyNodes: List<ASTDProjectileHistoryNode>,
    val presetId: String,
    val projectileSpecId: String,
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
