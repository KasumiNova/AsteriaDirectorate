package cn.kasuminova.astd.campaign.dialog.core

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.campaign.TextPanelAPI
import com.fs.starfarer.api.ui.LabelAPI
import com.fs.starfarer.api.util.Highlights
import java.awt.Color

internal fun I18n.Rendered.toHighlightsOrNull(): Highlights? {
    if (highlights.isEmpty()) return null
    val hs = Highlights()
    for (h in highlights) {
        hs.append(h.text, h.color)
    }
    return hs
}

fun TextPanelAPI.addRendered(rendered: I18n.Rendered, baseColor: Color? = null): LabelAPI {
    val label = if (baseColor != null) {
        addPara(rendered.text, baseColor)
    } else {
        addPara(rendered.text)
    }

    val hs = rendered.toHighlightsOrNull()
    if (hs != null) {
        setHighlightsInLastPara(hs)
    }
    return label
}
