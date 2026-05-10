package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.ui.dsl.buildWith
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import java.awt.Color

internal object ASTDArcFlareHullModTooltip {

    data class Theme(
        val nameColor: Color,
        val borderColor: Color,
        val headerBackground: Color,
        val sectionBackground: Color,
        val accentColor: Color,
    )

    data class Section(
        val headingKey: String,
        val lineKeys: List<String>,
    )

    fun section(headingKey: String, vararg lineKeys: String): Section = Section(headingKey, lineKeys.toList())

    fun render(
        tooltip: TooltipMakerAPI,
        width: Float,
        title: String,
        theme: Theme,
        summaryKey: String,
        sections: List<Section>,
        starTrails: Boolean = false,
    ) {
        tooltip.buildWith {
            spacer(6f)
            withNebulaBackground(
                color = theme.accentColor,
                width = width,
                starTrails = starTrails,
            ) {
                heading(title, theme.nameColor, theme.headerBackground, 6f)
                spacer(2f)
                para(I18n.Categories.MOD, summaryKey, Misc.getTextColor(), 4f)
                for (section in sections) {
                    spacer(6f)
                    heading(
                        I18n[I18n.Categories.MOD, section.headingKey],
                        theme.nameColor,
                        theme.sectionBackground,
                        2f,
                    )
                    for (lineKey in section.lineKeys) {
                        para(I18n.Categories.MOD, lineKey, Color(200, 200, 210), 2f)
                    }
                }
            }
        }
    }
}