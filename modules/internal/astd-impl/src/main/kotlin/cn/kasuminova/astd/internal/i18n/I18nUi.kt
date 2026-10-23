package cn.kasuminova.astd.internal.i18n

import com.fs.starfarer.api.ui.LabelAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

/** 必须使用 Starsector `%s` 格式化高亮文本，否则含 `%` 的文本会匹配失败。 */
object I18nUi {

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
        label.color = baseColor
        return label
    }
}
