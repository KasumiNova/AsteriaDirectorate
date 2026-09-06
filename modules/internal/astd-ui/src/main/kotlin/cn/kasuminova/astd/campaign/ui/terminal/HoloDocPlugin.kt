package cn.kasuminova.astd.campaign.ui.terminal

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin
import com.fs.starfarer.api.ui.Fonts
import com.fs.starfarer.api.ui.PositionAPI
import org.lazywizard.lazylib.ui.LazyFont
import java.util.EnumMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * 全息文书卡渲染插件：深色全息面板底（renderBelow）+ 电子签章角标 + 电子印戳（render）。
 *
 * 印戳两种呈现：
 * - 动效戳（接取/核销瞬间）：由 [animatedStamp] 提供戳种与时间，按 [StampTimeline]
 *   砸落（scale 2.6→1）并扩散光晕；
 * - 常驻戳（非待接取单）：[staticStamp] 提供戳种，静态斜置（-14°）。
 *
 * 签章/印戳均无美术素材，按任务约束纯 GL 矩形/圆环 + LazyFont 文字绘制。
 */
class HoloDocPlugin(
    private val cardWidth: Float,
    private val cardHeight: Float,
    /** 动效戳状态（戳种 + 经过秒数）；无动效为 null。 */
    private val animatedStamp: () -> Pair<StampKind, Float>?,
    /** 常驻戳种（选中单状态推导；待接取为 null）。 */
    private val staticStamp: () -> StampKind?,
) : BaseCustomUIPanelPlugin() {

    private var pos: PositionAPI? = null

    private val stampFont: LazyFont by lazy { LazyFont.loadFont(Fonts.INSIGNIA_LARGE) }
    private val sealFont: LazyFont by lazy { LazyFont.loadFont(Fonts.INSIGNIA_LARGE) }

    // 常驻字形缓存（createText 每帧重建会反复上传纹理）：签章两行 + 常驻戳面（按戳种）；
    // 动效戳文字随缩放变字号，仅在（戳种, 字号）变化时重建
    private var sealTopText: LazyFont.DrawableString? = null
    private var sealBottomText: LazyFont.DrawableString? = null
    private val staticStampTexts = EnumMap<StampKind, LazyFont.DrawableString>(StampKind::class.java)
    private var animStampKey: Pair<StampKind, Float>? = null
    private var animStampText: LazyFont.DrawableString? = null

    override fun positionChanged(position: PositionAPI) {
        pos = position
    }

    /** 深色全息面板底 + teal 描边 + 细密扫描横纹（与终端整体观感一致）。 */
    override fun renderBelow(alphaMult: Float) {
        val p = pos ?: return
        val x = p.x
        val y = p.y

        // 卡周微光晕（全息投影的发光体感）
        TerminalGl.rect(x - 3f, y - 3f, cardWidth + 6f, cardHeight + 6f, TerminalStyle.teal, 0.05f * alphaMult)
        TerminalGl.rect(x, y, cardWidth, cardHeight, TerminalStyle.panelBg, 0.97f * alphaMult)
        TerminalGl.rectOutline(x, y, cardWidth, cardHeight, TerminalStyle.tealDark, 0.9f * alphaMult, 1f)
        TerminalGl.rect(x, y + cardHeight - 2f, cardWidth, 2f, TerminalStyle.teal, 0.18f * alphaMult)

        // 全息扫描横纹（每 4px 一道极浅 teal 线；同参数合批，一次 glPushAttrib/glBegin）
        val scan = TerminalGl.RectBatch(TerminalStyle.teal, 0.025f * alphaMult)
        var ly = y + 2f
        while (ly < y + cardHeight) {
            scan.rect(x, ly, cardWidth, 1f)
            ly += 4f
        }
        scan.flush()
    }

    override fun render(alphaMult: Float) {
        val p = pos ?: return
        renderSeal(p.x, p.y, alphaMult)
        val animated = animatedStamp()
        if (animated != null) {
            renderStamp(p.x, p.y, animated.first, animated.second, alphaMult)
        } else {
            staticStamp()?.let { renderStamp(p.x, p.y, it, Float.MAX_VALUE, alphaMult) }
        }
    }

    /** 电子签章角标：右上圆环 + 两行小字，斜置 12°。 */
    private fun renderSeal(x: Float, y: Float, alphaMult: Float) {
        val cx = x + cardWidth - SEAL_MARGIN_X
        val cy = y + cardHeight - SEAL_MARGIN_Y
        TerminalGl.circleOutline(cx, cy, SEAL_RADIUS, TerminalStyle.tealBright, 0.75f * alphaMult, 2f)
        TerminalGl.circleOutline(cx, cy, SEAL_RADIUS - 4f, TerminalStyle.tealBright, 0.5f * alphaMult, 1f)

        val top = sealTopText ?: sealCentered("ui.terminal.doc.seal_top").also { sealTopText = it }
        top.baseColor = withAlpha(TerminalStyle.tealBright, 0.75f * alphaMult)
        top.drawAtAngle(cx, cy + SEAL_FONT_SIZE * 0.55f, SEAL_ANGLE)

        val bottom = sealBottomText ?: sealCentered("ui.terminal.doc.seal_bottom").also { sealBottomText = it }
        bottom.baseColor = withAlpha(TerminalStyle.tealBright, 0.75f * alphaMult)
        bottom.drawAtAngle(cx, cy - SEAL_FONT_SIZE * 0.85f, SEAL_ANGLE)
    }

    private fun sealCentered(textKey: String): LazyFont.DrawableString =
        sealFont.createText(I18n[TerminalStyle.CAT, textKey], TerminalStyle.tealBright, SEAL_FONT_SIZE).apply {
            anchor = LazyFont.TextAnchor.CENTER
            alignment = LazyFont.TextAlignment.CENTER
        }

    /**
     * 电子印戳：双线方戳 + 戳面文字，斜置 -14°。
     * [t] 为动效时间（[StampTimeline]）；[Float.MAX_VALUE] 表示常驻静态观感。
     */
    private fun renderStamp(x: Float, y: Float, kind: StampKind, t: Float, alphaMult: Float) {
        val animated = t != Float.MAX_VALUE
        val scale = if (animated) StampTimeline.scale(t) else 1f
        val alpha = (if (animated) StampTimeline.stampAlpha(t) else 0.85f) * alphaMult
        if (alpha <= 0f) return

        val cx = x + cardWidth - STAMP_MARGIN_X
        val cy = y + STAMP_MARGIN_Y

        // 光晕扩散（动效期）
        if (animated) {
            val splashAlpha = StampTimeline.splashAlpha(t) * alphaMult
            val splashRadius = SPLASH_BASE_RADIUS * StampTimeline.splashScale(t)
            TerminalGl.splash(cx, cy, splashRadius, TerminalStyle.tealBright, splashAlpha)
        }

        val w = STAMP_W * scale
        val h = STAMP_H * scale
        drawRotatedRectOutline(cx, cy, w, h, STAMP_ANGLE, TerminalStyle.tealBright, alpha, 3f)
        drawRotatedRectOutline(cx, cy, w - 8f * scale, h - 8f * scale, STAMP_ANGLE, TerminalStyle.tealBright, alpha * 0.9f, 1.5f)

        val textKey = when (kind) {
            StampKind.ACCEPTED -> "ui.terminal.stamp.accepted"
            StampKind.SETTLED -> "ui.terminal.stamp.settled"
        }
        val text = stampText(kind, textKey, STAMP_FONT_SIZE * scale)
        text.baseColor = withAlpha(TerminalStyle.tealBright, alpha)
        text.drawAtAngle(cx, cy, STAMP_ANGLE)
    }

    /** 戳面文字（居中）：常驻戳按戳种缓存；动效戳随缩放变字号，（戳种, 字号）变化才重建。 */
    private fun stampText(kind: StampKind, textKey: String, size: Float): LazyFont.DrawableString {
        if (size == STAMP_FONT_SIZE) {
            return staticStampTexts.getOrPut(kind) { stampCentered(textKey, size) }
        }
        val key = kind to size
        if (animStampKey != key) {
            animStampKey = key
            animStampText = stampCentered(textKey, size)
        }
        return animStampText!!
    }

    private fun stampCentered(textKey: String, size: Float): LazyFont.DrawableString =
        stampFont.createText(I18n[TerminalStyle.CAT, textKey], TerminalStyle.tealBright, size).apply {
            anchor = LazyFont.TextAnchor.CENTER
            alignment = LazyFont.TextAlignment.CENTER
        }

    /** 旋转矩形描边（绕中心 [angleDeg] 度）。 */
    private fun drawRotatedRectOutline(
        cx: Float, cy: Float, w: Float, h: Float, angleDeg: Float,
        color: java.awt.Color, alpha: Float, thickness: Float,
    ) {
        val rad = Math.toRadians(angleDeg.toDouble()).toFloat()
        val cosA = cos(rad)
        val sinA = sin(rad)
        val hw = w / 2f
        val hh = h / 2f
        val corners = arrayOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh)

        org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ENABLE_BIT or org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT or org.lwjgl.opengl.GL11.GL_LINE_BIT)
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND)
        org.lwjgl.opengl.GL11.glBlendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA)
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D)
        org.lwjgl.opengl.GL11.glLineWidth(thickness)
        org.lwjgl.opengl.GL11.glColor4f(color.red / 255f, color.green / 255f, color.blue / 255f, alpha * color.alpha / 255f)
        org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_LINE_LOOP)
        for ((lx, ly) in corners) {
            org.lwjgl.opengl.GL11.glVertex2f(cx + lx * cosA - ly * sinA, cy + lx * sinA + ly * cosA)
        }
        org.lwjgl.opengl.GL11.glEnd()
        org.lwjgl.opengl.GL11.glColor4f(1f, 1f, 1f, 1f)
        org.lwjgl.opengl.GL11.glPopAttrib()
    }

    private fun withAlpha(color: java.awt.Color, alpha: Float): java.awt.Color =
        java.awt.Color(color.red, color.green, color.blue, (color.alpha * alpha).toInt().coerceIn(0, 255))

    companion object {
        private const val SEAL_MARGIN_X: Float = 54f
        private const val SEAL_MARGIN_Y: Float = 52f
        private const val SEAL_RADIUS: Float = 30f
        private const val SEAL_FONT_SIZE: Float = 11f
        private const val SEAL_ANGLE: Float = 12f
        private const val STAMP_MARGIN_X: Float = 111f
        private const val STAMP_MARGIN_Y: Float = 74f
        private const val STAMP_W: Float = 150f
        private const val STAMP_H: Float = 64f
        private const val STAMP_ANGLE: Float = -14f
        private const val STAMP_FONT_SIZE: Float = 24f
        private const val SPLASH_BASE_RADIUS: Float = 34f
    }
}
