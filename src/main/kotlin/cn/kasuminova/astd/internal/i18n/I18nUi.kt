package cn.kasuminova.astd.internal.i18n

import com.fs.starfarer.api.ui.LabelAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

/**
 * I18n + Tooltip 高亮的便捷封装。
 *
 * 使用 Starsector 原生 `%s` 格式化机制实现多色高亮，
 * 避免 setHighlight + setHighlightColors 对含 `%` 字符的高亮文本匹配失败。
 */
object I18nUi {

    /**
     * 添加一行文本，并对 `<param:#RRGGBB:key>` 标记的变量进行高亮（支持多色）。
     */
    fun addPara(
        tooltip: TooltipMakerAPI,
        category: I18n.Categories,
        key: String,
        pad: Float,
        baseColor: Color,
        vararg vars: Pair<String, Any?>,
    ): LabelAPI {
        val rendered = I18n.tr(category, key, *vars)
        return addParaRendered(tooltip, rendered, pad, baseColor)
    }

    /**
     * 添加一行文本（string category 版本）。
     */
    fun addPara(
        tooltip: TooltipMakerAPI,
        category: String?,
        key: String,
        pad: Float,
        baseColor: Color,
        vararg vars: Pair<String, Any?>,
    ): LabelAPI {
        val rendered = I18n.tr(category, key, *vars)
        return addParaRendered(tooltip, rendered, pad, baseColor)
    }

    /**
     * 使用已渲染的 [I18n.Rendered] 结果添加段落，自动处理高亮。
     */
    fun addParaRendered(
        tooltip: TooltipMakerAPI,
        rendered: I18n.Rendered,
        pad: Float,
        baseColor: Color,
    ): LabelAPI {
        val highlights = rendered.highlights.filter { it.text.isNotBlank() }
        if (highlights.isEmpty()) {
            return tooltip.addPara(rendered.text, baseColor, pad)
        }
        return addParaWithFormatHighlights(tooltip, rendered.text, highlights, pad, baseColor)
    }

    /**
     * 使用 Starsector 原生 `addPara(format, pad, Color, values...)` 机制构建带多色高亮的段落。
     *
     * 原理：把高亮文本的位置替换为 `%s` 占位符，非高亮部分的 `%` 转义为 `%%`，
     * 让 Starsector 内部的格式化机制安全地处理含 `%` 字符的高亮值。
     */
    private fun addParaWithFormatHighlights(
        tooltip: TooltipMakerAPI,
        text: String,
        highlights: List<I18n.Highlight>,
        pad: Float,
        baseColor: Color,
    ): LabelAPI {
        data class Match(val start: Int, val end: Int, val text: String, val color: Color)
        val matches = mutableListOf<Match>()
        var searchFrom = 0
        for (hl in highlights) {
            val idx = text.indexOf(hl.text, searchFrom)
            if (idx >= 0) {
                matches.add(Match(idx, idx + hl.text.length, hl.text, hl.color))
                searchFrom = idx + hl.text.length
            }
        }

        if (matches.isEmpty()) {
            return tooltip.addPara(text, baseColor, pad)
        }

        val sb = StringBuilder()
        val hlValues = mutableListOf<String>()
        val hlColors = mutableListOf<Color>()
        var lastEnd = 0

        for (m in matches) {
            sb.append(text.substring(lastEnd, m.start).replace("%", "%%"))
            sb.append("%s")
            hlValues.add(m.text)
            hlColors.add(m.color)
            lastEnd = m.end
        }
        sb.append(text.substring(lastEnd).replace("%", "%%"))

        val label = tooltip.addPara(sb.toString(), pad, hlColors.toTypedArray(), *hlValues.toTypedArray())
        label.setColor(baseColor)
        return label
    }
}
