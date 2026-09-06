package cn.kasuminova.astd.campaign.ui.terminal

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.util.Misc
import java.awt.Color

/**
 * 分局终端视觉常量与音效投递（doc 00「视觉基调」）。
 *
 * 控件配色一律走原版 `Misc.get*Color()`（teal 主高亮 = 玩家主色、橙黄数值 =
 * highlight 色）；总局差异化仅两处自定义色：纸质文书卡米白底与印红章。
 */
object TerminalStyle {

    // ─── 原版配色 ───

    /** teal 主高亮（选中态 / tab 选中 / 主按钮）。 */
    val teal: Color get() = Misc.getBasePlayerColor()

    /** teal 亮（hover/强调文本）。 */
    val tealBright: Color get() = Misc.getBrightPlayerColor()

    /** teal 暗（描边/分隔）。 */
    val tealDark: Color get() = Misc.getDarkPlayerColor()

    /** 橙黄数值色（金额/进度/徽记）。 */
    val orange: Color get() = Misc.getHighlightColor()

    /** 正文浅灰白。 */
    val text: Color get() = Misc.getTextColor()

    /** 灰色存目/禁用态。 */
    val gray: Color get() = Misc.getGrayColor()

    // ─── 总局差异化自定义色（文书卡 / 印章，doc 00 钦定） ───

    /** 纸质文书卡米白底。 */
    val paper: Color = Color(0xEF, 0xE7, 0xD3)

    /** 纸卡墨本色。 */
    val paperInk: Color = Color(0x2B, 0x26, 0x20)

    /** 纸卡弱化行（签发小字/铅封语）。 */
    val paperDim: Color = Color(0x7A, 0x71, 0x60)

    /** 纸卡批注色（追加条款/系统批注，深橙褐）。 */
    val paperAnno: Color = Color(0x8A, 0x5A, 0x17)

    /** 印红章。 */
    val stampRed: Color = Color(0xB3, 0x33, 0x2B)

    // ─── 布局尺寸（px，对齐原型 1920×1080 定尺寸舞台比例） ───

    const val PAD: Float = 14f
    const val TOP_BAR_H: Float = 48f
    const val BOTTOM_BAR_H: Float = 46f
    const val GAP: Float = 8f
    const val LIST_W: Float = 540f
    const val ROW_H: Float = 26f
    const val STATUS_W: Float = 96f
    const val CARD_MAX_W: Float = 760f
    const val CARD_PAD_X: Float = 36f
    const val CARD_PAD_Y: Float = 28f
    const val TAB_W: Float = 132f
    const val TAB_H: Float = 30f
    const val RECEIPT_W: Float = 620f

    /** 终端 i18n category（strings.json 主表）。 */
    val CAT: I18n.Categories = I18n.Categories.MOD

    /** 工单/回执文案 category（bounty_strings.json）。 */
    const val CAT_BOUNTY: String = "asteria_directorate_bounty"

    /** 播放终端音效。 */
    fun play(sound: TerminalSound) {
        Global.getSoundPlayer()?.playUISound(sound.soundId, sound.pitch, sound.volume)
    }
}
