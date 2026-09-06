package cn.kasuminova.astd.campaign.ui.terminal

import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * 分局终端特效层的 GL 直绘基元（章/墨渍/扫描线/噪点均无素材，按任务约束纯 GL 绘制）。
 *
 * 所有函数假定调用方已处于 UI 正交投影环境（Starsector UI 渲染期），
 * 内部自管 blend/texture 状态并在返回前恢复。
 */
object TerminalGl {

    /** 实色矩形。 */
    fun rect(x: Float, y: Float, w: Float, h: Float, color: Color, alpha: Float = 1f) {
        prepare(color, alpha)
        GL11.glBegin(GL11.GL_QUADS)
        GL11.glVertex2f(x, y)
        GL11.glVertex2f(x + w, y)
        GL11.glVertex2f(x + w, y + h)
        GL11.glVertex2f(x, y + h)
        GL11.glEnd()
        restore()
    }

    /**
     * 同参数实色矩形合批器：循环内 [rect] 仅累积顶点，结束后 [flush] 一次提交
     * （单次 glPushAttrib + glBegin/glEnd）。扫描线/纸纹/噪点等大批量同参数矩形用，
     * 避免逐 rect 保存/恢复 GL 状态的开销。
     */
    class RectBatch(private val color: Color, private val alpha: Float) {
        /** 累积顶点（x,y 交错，每矩形 8 个浮点；internal 供单测验证合批内容）。 */
        internal val coords = ArrayList<Float>(8 * 32)

        /** 累积一个矩形（不触发任何 GL 调用）。 */
        fun rect(x: Float, y: Float, w: Float, h: Float) {
            coords.add(x); coords.add(y)
            coords.add(x + w); coords.add(y)
            coords.add(x + w); coords.add(y + h)
            coords.add(x); coords.add(y + h)
        }

        /** 已累积矩形数。 */
        val size: Int get() = coords.size / 8

        /** 一次性提交全部累积矩形（单次状态保存/恢复；空批次为空操作）。 */
        fun flush() {
            if (coords.isEmpty()) return
            prepare(color, alpha)
            GL11.glBegin(GL11.GL_QUADS)
            var i = 0
            while (i < coords.size) {
                GL11.glVertex2f(coords[i], coords[i + 1])
                i += 2
            }
            GL11.glEnd()
            restore()
        }
    }

    /** 矩形描边（线宽 [thickness]）。 */
    fun rectOutline(x: Float, y: Float, w: Float, h: Float, color: Color, alpha: Float = 1f, thickness: Float = 1f) {
        prepare(color, alpha)
        GL11.glLineWidth(thickness)
        GL11.glBegin(GL11.GL_LINE_LOOP)
        GL11.glVertex2f(x, y)
        GL11.glVertex2f(x + w, y)
        GL11.glVertex2f(x + w, y + h)
        GL11.glVertex2f(x, y + h)
        GL11.glEnd()
        restore()
    }

    /** 圆环描边（铅封用）。 */
    fun circleOutline(cx: Float, cy: Float, radius: Float, color: Color, alpha: Float = 1f, thickness: Float = 2f, segments: Int = 48) {
        prepare(color, alpha)
        GL11.glLineWidth(thickness)
        GL11.glBegin(GL11.GL_LINE_LOOP)
        for (i in 0 until segments) {
            val a = i * 2f * Math.PI.toFloat() / segments
            GL11.glVertex2f(cx + radius * cos(a), cy + radius * sin(a))
        }
        GL11.glEnd()
        restore()
    }

    /** 实心圆（墨渍内层）。 */
    fun disc(cx: Float, cy: Float, radius: Float, color: Color, alpha: Float = 1f, segments: Int = 40) {
        prepare(color, alpha)
        GL11.glBegin(GL11.GL_TRIANGLE_FAN)
        GL11.glVertex2f(cx, cy)
        for (i in 0..segments) {
            val a = i * 2f * Math.PI.toFloat() / segments
            GL11.glVertex2f(cx + radius * cos(a), cy + radius * sin(a))
        }
        GL11.glEnd()
        restore()
    }

    /** 径向衰减墨渍（多层同心圆近似 radial-gradient）。 */
    fun splash(cx: Float, cy: Float, radius: Float, color: Color, alpha: Float) {
        if (alpha <= 0f || radius <= 0f) return
        disc(cx, cy, radius * 0.45f, color, alpha * 0.55f)
        disc(cx, cy, radius * 0.7f, color, alpha * 0.28f)
        disc(cx, cy, radius, color, alpha * 0.12f)
    }

    private fun prepare(color: Color, alpha: Float) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT or GL11.GL_LINE_BIT)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL11.glColor4f(color.red / 255f, color.green / 255f, color.blue / 255f, alpha * color.alpha / 255f)
    }

    private fun restore() {
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GL11.glPopAttrib()
    }
}
