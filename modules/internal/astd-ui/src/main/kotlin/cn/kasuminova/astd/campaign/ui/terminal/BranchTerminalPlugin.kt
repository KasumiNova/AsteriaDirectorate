package cn.kasuminova.astd.campaign.ui.terminal

import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.ui.Fonts
import com.fs.starfarer.api.ui.PositionAPI
import org.lazywizard.lazylib.ui.LazyFont
import java.awt.Color
import java.awt.event.KeyEvent
import java.util.EnumMap

/**
 * 分局终端根面板插件：特效层（GL 直绘）与全局输入。
 *
 * - 每帧 [advance] → [BranchTerminalDelegate.tick] 推进全部时间线与特效调度；
 * - [render] 依次叠加：待机呼吸扫描线 → 闪现 glitch 噪点/撕裂 → 终端开机场；
 * - [processInput]：开机场任意键/点击跳过（吞掉全部输入）；Esc → [onEscape]
 *   （回执开启时先收回执，否则关闭终端——终端是玩家的地盘，允许退出）；
 * - 盖章震屏：[tick] 内经根面板 [PositionAPI] 偏移施加。
 *
 * 按钮事件不冒泡：原版 `CustomPanelImpl` 只把按钮按下派发给**直接持有该按钮
 * UIElement 的面板**自己的插件，本根插件收不到子面板按钮。承载按钮的面板一律挂
 * [ButtonRelayPlugin] 转发到 [BranchTerminalDelegate.onButton]。
 */
class BranchTerminalPlugin(
    private val host: BranchTerminalDelegate,
) : BaseCustomUIPanelPlugin() {

    /** 根面板屏幕坐标（震屏偏移施加对象；开机场/噪点绘制基准）。 */
    var panelPos: PositionAPI? = null
        private set

    private val emblemFont: LazyFont by lazy { LazyFont.loadFont(Fonts.INSIGNIA_VERY_LARGE) }
    private val hintFont: LazyFont by lazy { LazyFont.loadFont(Fonts.INSIGNIA_LARGE) }

    // 开机场常驻字形缓存（文案打开时定稿不变，createText 每帧重建会反复上传纹理；仅透明度逐帧改）
    private var bootEmblemGlyph: LazyFont.DrawableString? = null
    private var bootSubtitleGlyph: LazyFont.DrawableString? = null
    private var bootSkipHintGlyph: LazyFont.DrawableString? = null

    private fun centeredText(font: LazyFont, text: String, color: Color, size: Float): LazyFont.DrawableString =
        font.createText(text, color, size).apply {
            anchor = LazyFont.TextAnchor.CENTER
            alignment = LazyFont.TextAlignment.CENTER
        }

    override fun positionChanged(position: PositionAPI) {
        panelPos = position
    }

    override fun advance(amount: Float) {
        host.tick(amount)
    }

    override fun processInput(events: List<InputEventAPI>) {
        if (host.bootActive) {
            for (event in events) {
                if (event.isConsumed) continue
                if (event.isKeyDownEvent || event.isMouseDownEvent) {
                    host.skipBoot()
                }
                event.consume()
            }
            return
        }
        for (event in events) {
            if (event.isConsumed || !event.isKeyDownEvent) continue
            if (event.eventValue == KeyEvent.VK_ESCAPE) {
                event.consume()
                host.onEscape()
            }
        }
    }

    override fun render(alphaMult: Float) {
        val p = panelPos ?: return
        val w = p.width
        val h = p.height
        val x = p.x
        val y = p.y

        if (!host.bootActive) {
            renderScanlines(x, y, w, h, alphaMult)
            renderGlitch(x, y, w, h, alphaMult)
        }
        renderBoot(x, y, w, h, alphaMult)
    }

    /** 待机呼吸：常驻扫描线 + 顶/底 teal 微光，亮度随 [BreatheTimeline] 低频波动。 */
    private fun renderScanlines(x: Float, y: Float, w: Float, h: Float, alphaMult: Float) {
        val breathe = BreatheTimeline.alpha(host.time) * alphaMult
        // 同参数扫描线合批：整个循环一次 glPushAttrib/glBegin（逐 rect 提交约 h/3 次状态切换）
        val scanlines = TerminalGl.RectBatch(SCANLINE_COLOR, 0.05f * breathe)
        var ly = y
        while (ly < y + h) {
            scanlines.rect(x, ly, w, 1f)
            ly += 3f
        }
        scanlines.flush()
        TerminalGl.rect(x, y + h - 3f, w, 3f, TerminalStyle.teal, 0.05f * breathe)
        TerminalGl.rect(x, y, w, 3f, TerminalStyle.teal, 0.04f * breathe)
    }

    /** 闪现 glitch：全屏噪点 + 横向撕裂带（<0.5s 自愈，时序见 [GlitchTimeline]）。 */
    private fun renderGlitch(x: Float, y: Float, w: Float, h: Float, alphaMult: Float) {
        val t = host.glitchT
        if (t.isNaN() || !GlitchTimeline.active(t)) return
        val seed = host.glitchSeed
        val (jx, jy) = GlitchTimeline.jitterOffset(t, seed)

        // 细密噪点行（白 + teal/红交替），整层随 jitter 抖动；同桶行合批提交
        val noiseBatches = EnumMap<GlitchTimeline.NoiseTone, TerminalGl.RectBatch>(GlitchTimeline.NoiseTone::class.java)
        var ly = y
        var row = 0
        while (ly < y + h) {
            val tone = GlitchTimeline.noiseTone(row)
            val batch = noiseBatches.getOrPut(tone) {
                TerminalGl.RectBatch(noiseColor(tone), GlitchTimeline.noiseAlpha(tone) * alphaMult)
            }
            batch.rect(x + jx, ly + jy, w, 1f)
            ly += 2f
            row++
        }
        noiseBatches.values.forEach { it.flush() }

        // 横向撕裂带：若干高度带按带号错位
        val bands = 10
        val bandH = h / bands
        for (band in 0 until bands) {
            val dx = GlitchTimeline.tearOffset(t, band, seed)
            if (dx == 0f) continue
            val by = y + band * bandH
            TerminalGl.rect(x + dx, by, w, 3f, TerminalStyle.teal, 0.10f * alphaMult)
            TerminalGl.rect(x - dx, by + bandH * 0.5f, w, 2f, TerminalStyle.glitchRed, 0.10f * alphaMult)
        }
    }

    private fun noiseColor(tone: GlitchTimeline.NoiseTone): Color = when (tone) {
        GlitchTimeline.NoiseTone.WHITE -> Color.WHITE
        GlitchTimeline.NoiseTone.TEAL -> TerminalStyle.teal
        GlitchTimeline.NoiseTone.RED -> TerminalStyle.glitchRed
    }

    /**
     * 终端开机场：暗屏 → teal 扫描线自上而下点亮 → 总局徽记淡入 → 进入
     * （时序见 [BootTimeline]；任意键/点击跳过）。
     */
    private fun renderBoot(x: Float, y: Float, w: Float, h: Float, alphaMult: Float) {
        if (!host.bootActive) return
        val t = host.bootT

        TerminalGl.rect(x, y, w, h, BOOT_BG, 1f)

        // 扫描线点亮（自上而下推进；已扫过侧 = 扫描线上方区域）
        val sweep = BootTimeline.sweepProgress(t)
        if (sweep > 0f) {
            val sweepY = y + h * (1f - sweep)
            TerminalGl.rect(x, sweepY - 12f, w, 12f, TerminalStyle.teal, 0.05f)
            TerminalGl.rect(x, sweepY - 1.5f, w, 3f, TerminalStyle.teal, 0.9f)
            TerminalGl.rect(x, sweepY + 1.5f, w, 6f, TerminalStyle.teal, 0.12f)
            // 已点亮区域残余微光（扫描线上方的已扫过侧；起扫瞬间高度不足时不画）
            val glowH = y + h - sweepY - 8f
            if (glowH > 0f) {
                TerminalGl.rect(x, sweepY + 8f, w, glowH, TerminalStyle.teal, 0.02f)
            }
        }

        // 总局徽记淡入
        val emblemAlpha = BootTimeline.emblemAlpha(t)
        if (emblemAlpha > 0f) {
            val cx = x + w / 2f
            val cy = y + h / 2f + 40f
            val box = EMBLEM_BOX
            TerminalGl.rectOutline(cx - box / 2, cy - box / 2, box, box, TerminalStyle.teal, 0.9f * emblemAlpha, 2f)
            TerminalGl.rect(cx - box / 2, cy - box / 2, box, box, TerminalStyle.teal, 0.06f * emblemAlpha)

            val glyph = bootEmblemGlyph
                ?: centeredText(emblemFont, host.bootEmblemText(), TerminalStyle.teal, EMBLEM_GLYPH_SIZE)
                    .also { bootEmblemGlyph = it }
            glyph.baseColor = withAlpha(TerminalStyle.teal, emblemAlpha)
            glyph.draw(cx, cy)

            val sub = bootSubtitleGlyph
                ?: centeredText(hintFont, host.bootSubtitleText(), TerminalStyle.gray, 12f)
                    .also { bootSubtitleGlyph = it }
            sub.baseColor = withAlpha(TerminalStyle.gray, emblemAlpha)
            sub.draw(cx, cy - box / 2 - 34f)
        }

        // 跳过提示
        val hint = bootSkipHintGlyph
            ?: centeredText(hintFont, host.bootSkipHintText(), TerminalStyle.gray, 12f)
                .also { bootSkipHintGlyph = it }
        hint.baseColor = withAlpha(TerminalStyle.gray, 0.7f)
        hint.draw(x + w / 2f, y + 40f)
    }

    private fun withAlpha(color: Color, alpha: Float): Color =
        Color(color.red, color.green, color.blue, (color.alpha * alpha).toInt().coerceIn(0, 255))

    companion object {
        private val SCANLINE_COLOR: Color = Color(0xFF, 0xFF, 0xFF)
        private val BOOT_BG: Color = Color(0x03, 0x05, 0x07)
        private const val EMBLEM_BOX: Float = 130f
        private const val EMBLEM_GLYPH_SIZE: Float = 72f
    }
}

/**
 * 回执全屏遮罩插件：暗化底层 + 吞掉遮罩区域内的鼠标输入（回执开启期间底层列表不可点）。
 */
class ReceiptOverlayPlugin : BaseCustomUIPanelPlugin() {

    private var pos: PositionAPI? = null

    override fun positionChanged(position: PositionAPI) {
        pos = position
    }

    override fun processInput(events: List<InputEventAPI>) {
        // 遮罩置顶后先于底层控件接收事件：吞掉落在遮罩矩形内的鼠标输入，
        // 键盘输入（Esc 收回执/关终端）继续下传根插件
        val p = pos ?: return
        for (event in events) {
            if (event.isConsumed || !event.isMouseEvent) continue
            if (p.containsEvent(event)) {
                event.consume()
            }
        }
    }

    override fun renderBelow(alphaMult: Float) {
        val p = pos ?: return
        TerminalGl.rect(p.x, p.y, p.width, p.height, OVERLAY_BG, 0.72f * alphaMult)
    }

    companion object {
        private val OVERLAY_BG: Color = Color(0x05, 0x08, 0x0B)
    }
}

/**
 * 按钮事件转发插件：原版 `CustomPanelImpl` 不冒泡按钮事件（只派发给直接持有按钮
 * UIElement 的面板插件），承载按钮的子面板统一挂本插件把 `buttonPressed` 转发给
 * 终端代理的按钮路由。
 */
class ButtonRelayPlugin(
    private val onButton: (Any?) -> Unit,
) : BaseCustomUIPanelPlugin() {

    override fun buttonPressed(buttonId: Any?) {
        onButton(buttonId)
    }
}

/**
 * 回执明细单面板插件：终端底色 + teal 描边（面板本身无框，手动绘制）。
 *
 * 关闭按钮直接挂在本面板的 UIElement 上，按钮事件经 [onButton] 转发终端代理。
 */
class ReceiptPanelPlugin(
    private val panelWidth: Float,
    private val panelHeight: Float,
    private val onButton: (Any?) -> Unit,
) : BaseCustomUIPanelPlugin() {

    private var pos: PositionAPI? = null

    override fun positionChanged(position: PositionAPI) {
        pos = position
    }

    override fun buttonPressed(buttonId: Any?) {
        onButton(buttonId)
    }

    override fun renderBelow(alphaMult: Float) {
        val p = pos ?: return
        TerminalGl.rect(p.x, p.y, panelWidth, panelHeight, TerminalStyle.panelBg, 0.97f * alphaMult)
        TerminalGl.rectOutline(p.x, p.y, panelWidth, panelHeight, TerminalStyle.tealDark, 0.9f * alphaMult, 1f)
    }
}
