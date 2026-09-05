package cn.kasuminova.astd.campaign.ui

import com.fs.starfarer.api.ui.PositionAPI
import com.fs.starfarer.api.util.Misc
import org.lwjgl.opengl.GL11
import java.awt.Color
import java.util.Random
import kotlin.math.sin

/**
 * 分局终端的屏幕特效：原版配色边框、CRT 扫描线（待机呼吸）、开机电亮、短促闪现 glitch。
 *
 * 全部以 GL11 直接绘制（同 `ASTDStencilRenderer` / `ASTDLatticePulseTooltipBackground` 的既有模式），
 * 不依赖任何 Web/外部素材；观感对照 `tools/mod-ui-preview` 原型与
 * `docs/design/ui/00-模组总UI设计.md` 动效清单，克制使用。
 *
 * 由终端根面板的 plugin 每帧驱动：[positionChanged] → [advance] → [render]。
 */
class TerminalScreenEffects {

    private companion object {
        /** 开机电亮总时长（秒）：暗屏 → 扫描线自上而下点亮 → 暗场淡出。 */
        const val BOOT_TOTAL: Float = 0.9f

        /** 开机扫描线点亮时长（秒）。 */
        const val BOOT_SWEEP: Float = 0.55f

        /** 待机漂移扫描线周期（秒）。 */
        const val IDLE_SCAN_PERIOD: Float = 7f

        /** 静态扫描线间距（像素）。 */
        const val SCANLINE_STEP: Float = 4f

        /** 闪现默认时长（秒）：<0.5s 后自愈，系统不解释。 */
        const val GLITCH_DURATION: Float = 0.45f

        val BASE: Color = Misc.getBasePlayerColor()
        val BRIGHT: Color = Misc.getBrightPlayerColor()
        val HIGHLIGHT: Color = Misc.getHighlightColor()
        val DARK: Color = Misc.getDarkPlayerColor()
    }

    private var pos: PositionAPI? = null
    private var elapsed: Float = 0f
    private var glitchRemaining: Float = 0f
    private val glitchRandom = Random()

    fun positionChanged(position: PositionAPI) {
        pos = position
    }

    /** 触发一次短促闪现（剧情系统事件驱动，UI 层只做呈现）。 */
    fun triggerGlitch(duration: Float = GLITCH_DURATION) {
        glitchRemaining = duration.coerceIn(0.05f, 0.6f)
    }

    /** 跳过开机电亮（任意按键/点击）。 */
    fun skipBoot() {
        if (elapsed < BOOT_TOTAL) elapsed = BOOT_TOTAL
    }

    fun advance(amount: Float) {
        if (amount <= 0f) return
        elapsed += amount
        if (glitchRemaining > 0f) glitchRemaining -= amount
    }

    fun render(alphaMult: Float) {
        val p = pos ?: return
        val x = p.x
        val y = p.y
        val w = p.width
        val h = p.height
        if (w <= 0f || h <= 0f) return

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT)
        GL11.glPushMatrix()
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glDisable(GL11.GL_TEXTURE_2D)

        renderFrame(x, y, w, h, alphaMult)
        renderScanlines(x, y, w, h, alphaMult)
        renderBoot(x, y, w, h, alphaMult)
        if (glitchRemaining > 0f) renderGlitch(x, y, w, h, alphaMult)

        GL11.glPopMatrix()
        GL11.glPopAttrib()
    }

    /** 原版配色边框：外框主线 + 内侧细线，克制不抢眼。 */
    private fun renderFrame(x: Float, y: Float, w: Float, h: Float, alphaMult: Float) {
        GL11.glLineWidth(1f)

        GL11.glColor4f(BASE.red / 255f, BASE.green / 255f, BASE.blue / 255f, 0.45f * alphaMult)
        GL11.glBegin(GL11.GL_LINE_LOOP)
        GL11.glVertex2f(x + 0.5f, y + 0.5f)
        GL11.glVertex2f(x + w - 0.5f, y + 0.5f)
        GL11.glVertex2f(x + w - 0.5f, y + h - 0.5f)
        GL11.glVertex2f(x + 0.5f, y + h - 0.5f)
        GL11.glEnd()

        GL11.glColor4f(DARK.red / 255f, DARK.green / 255f, DARK.blue / 255f, 0.5f * alphaMult)
        GL11.glBegin(GL11.GL_LINE_LOOP)
        GL11.glVertex2f(x + 3.5f, y + 3.5f)
        GL11.glVertex2f(x + w - 3.5f, y + 3.5f)
        GL11.glVertex2f(x + w - 3.5f, y + h - 3.5f)
        GL11.glVertex2f(x + 3.5f, y + h - 3.5f)
        GL11.glEnd()
    }

    /** CRT 扫描线：静态暗纹 + 一条低频漂移的青色亮线（待机呼吸，“机器活着”）。 */
    private fun renderScanlines(x: Float, y: Float, w: Float, h: Float, alphaMult: Float) {
        GL11.glColor4f(0f, 0f, 0f, 0.22f * alphaMult)
        GL11.glBegin(GL11.GL_QUADS)
        var sy = y + SCANLINE_STEP
        while (sy < y + h) {
            GL11.glVertex2f(x, sy)
            GL11.glVertex2f(x + w, sy)
            GL11.glVertex2f(x + w, sy + 1f)
            GL11.glVertex2f(x, sy + 1f)
            sy += SCANLINE_STEP
        }
        GL11.glEnd()

        val drift = (elapsed % IDLE_SCAN_PERIOD) / IDLE_SCAN_PERIOD
        val lineY = y + h - drift * h
        val breath = (0.06f + 0.05f * sin(elapsed * 1.7f)) * alphaMult
        GL11.glColor4f(BASE.red / 255f, BASE.green / 255f, BASE.blue / 255f, breath * 0.35f)
        quad(x, lineY - 8f, w, 16f)
        GL11.glColor4f(BRIGHT.red / 255f, BRIGHT.green / 255f, BRIGHT.blue / 255f, breath)
        quad(x, lineY - 1f, w, 2f)
    }

    /** 开机电亮：暗屏 → 亮线自上而下扫过 → 暗场淡出。 */
    private fun renderBoot(x: Float, y: Float, w: Float, h: Float, alphaMult: Float) {
        if (elapsed >= BOOT_TOTAL) return

        if (elapsed < BOOT_SWEEP) {
            val t = elapsed / BOOT_SWEEP
            val lineY = y + h - t * h
            GL11.glColor4f(BRIGHT.red / 255f, BRIGHT.green / 255f, BRIGHT.blue / 255f, 0.55f * alphaMult)
            quad(x, lineY - 10f, w, 20f)
            GL11.glColor4f(1f, 1f, 1f, 0.7f * alphaMult)
            quad(x, lineY - 1.5f, w, 3f)
        }

        val fade = (1f - elapsed / BOOT_TOTAL).coerceIn(0f, 1f)
        GL11.glColor4f(0f, 0f, 0f, 0.9f * fade * alphaMult)
        quad(x, y, w, h)
    }

    /** 闪现 glitch：横向撕裂条带 + 噪点，<0.5s 自愈。 */
    private fun renderGlitch(x: Float, y: Float, w: Float, h: Float, alphaMult: Float) {
        val intensity = (glitchRemaining / GLITCH_DURATION).coerceIn(0f, 1f)
        val rnd = glitchRandom
        repeat(12) {
            val stripY = y + rnd.nextFloat() * h
            val stripH = 2f + rnd.nextFloat() * 5f
            val shift = (rnd.nextFloat() - 0.5f) * 36f * intensity
            val color = when (rnd.nextInt(3)) {
                0 -> BRIGHT
                1 -> HIGHLIGHT
                else -> Color.WHITE
            }
            val a = (0.08f + rnd.nextFloat() * 0.18f) * intensity * alphaMult
            GL11.glColor4f(color.red / 255f, color.green / 255f, color.blue / 255f, a)
            val mid = x + w / 2f + shift
            quad(x, stripY, mid - x, stripH)
            quad(mid, stripY, x + w - mid, stripH)
        }
    }

    private fun quad(x: Float, y: Float, w: Float, h: Float) {
        GL11.glBegin(GL11.GL_QUADS)
        GL11.glVertex2f(x, y)
        GL11.glVertex2f(x + w, y)
        GL11.glVertex2f(x + w, y + h)
        GL11.glVertex2f(x, y + h)
        GL11.glEnd()
    }
}
