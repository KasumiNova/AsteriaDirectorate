package cn.kasuminova.astd.ui.dsl

import cn.kasuminova.astd.ui.effect.ASTDParticleBackground
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.internal.i18n.I18nUi
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

/**
 * 声明式 Tooltip 构建 DSL。
 *
 * 用法示例：
 * ```kotlin
 * tooltip.buildWith {
 *     heading("标题", Misc.getBasePlayerColor())
 *     spacer(10f)
 *     para(I18n.Categories.MOD, "some.key", Misc.getTextColor(), "var1" to value1)
 *     separator(Misc.getDarkPlayerColor())
 *     withParticleBackground(Color(80, 160, 255)) {
 *         para(I18n.Categories.MOD, "bg.text", Misc.getTextColor())
 *     }
 * }
 * ```
 */
@DslMarker
annotation class TooltipDsl

@TooltipDsl
class TooltipBuilder(val tooltip: TooltipMakerAPI) {

    /**
     * 添加一段文本（纯文本，不带高亮）。
     */
    fun text(text: String, color: Color, pad: Float = 3f) {
        tooltip.addPara(text, color, pad)
    }

    /**
     * 使用 I18n 添加一段文本（支持多色高亮的 `<param:...>` 标记）。
     */
    fun para(
        category: I18n.Categories,
        key: String,
        baseColor: Color,
        pad: Float = 3f,
        vararg vars: Pair<String, Any?>,
    ) {
        I18nUi.addPara(tooltip, category, key, pad, baseColor, *vars)
    }

    /**
     * 使用 I18n（字符串 category）添加一段文本。
     */
    fun para(
        category: String,
        key: String,
        baseColor: Color,
        pad: Float = 3f,
        vararg vars: Pair<String, Any?>,
    ) {
        I18nUi.addPara(tooltip, category, key, pad, baseColor, *vars)
    }

    /**
     * 添加带分节标题样式的段落。
     */
    fun heading(text: String, baseColor: Color, bgColor: Color = Color(0, 0, 0, 0), pad: Float = 10f) {
        tooltip.addSectionHeading(text, baseColor, bgColor, Alignment.MID, pad)
    }

    /**
     * 添加垂直间距。
     */
    fun spacer(height: Float = 10f) {
        tooltip.addSpacer(height)
    }

    /**
     * 添加水平分隔线。
     */
    fun separator(color: Color, pad: Float = 5f) {
        tooltip.addSectionHeading("", color, Color(0, 0, 0, 0), Alignment.MID, pad)
    }

    /**
     * 添加一个图片。
     */
    fun image(spriteName: String, width: Float, height: Float, pad: Float = 10f) {
        tooltip.addImage(spriteName, width, height, pad)
    }

    /**
     * 在星云渐变背景内渲染内容。
     *
     * 背景高度由实际内容精确决定，不使用预估值，不会溢出 Tooltip 范围。
     *
     * @param color 星云贴图主色调
     * @param width 背景宽度
     * @param starTrails 是否渲染延迟摄影星轨效果
     */
    fun withNebulaBackground(
        color: Color,
        width: Float,
        starTrails: Boolean = false,
        block: TooltipBuilder.() -> Unit,
    ) {
        val startHeight = tooltip.heightSoFar
        val plugin = ASTDParticleBackground.create(tooltip, width, color, starTrails)
        block()
        plugin.contentHeight = tooltip.heightSoFar - startHeight
    }
}

/**
 * 使用 DSL 构建 Tooltip 内容。
 */
fun TooltipMakerAPI.buildWith(block: TooltipBuilder.() -> Unit) {
    TooltipBuilder(this).block()
}
