package cn.kasuminova.astd.combat.hullmods.base

import cn.kasuminova.astd.ui.dsl.buildWith
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.internal.i18n.I18nUi
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import java.awt.Color

internal object ASTDHullModTooltipRenderer {

    private const val TABLE_ROW_HEIGHT = 24f

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

    sealed interface Block {
        val padTop: Float
    }

    data class Paragraph(
        val key: String,
        override val padTop: Float = 8f,
    ) : Block

    data class Heading(
        val key: String,
        override val padTop: Float = 12f,
    ) : Block

    data class Table(
        val headerAKey: String,
        val headerBKey: String,
        val rows: List<Row>,
        override val padTop: Float = 8f,
    ) : Block

    data class Row(
        val labelKey: String,
        val valueKey: String,
    )

    fun section(headingKey: String, vararg lineKeys: String): Section = Section(headingKey, lineKeys.toList())

    fun paragraph(key: String, padTop: Float = 8f): Paragraph = Paragraph(key, padTop)

    fun heading(key: String, padTop: Float = 12f): Heading = Heading(key, padTop)

    fun row(labelKey: String, valueKey: String): Row = Row(labelKey, valueKey)

    fun table(
        headerAKey: String = "ui.hullmod.table.attribute",
        headerBKey: String = "ui.hullmod.table.effect",
        vararg rows: Row,
    ): Table = Table(headerAKey, headerBKey, rows.toList())

    fun render(
        tooltip: TooltipMakerAPI,
        width: Float,
        title: String,
        theme: Theme,
        summaryKey: String,
        sections: List<Section>,
    ) {
        tooltip.buildWith {
            spacer(6f)
            withLatticePulseBackground(
                accentColor = theme.accentColor,
                width = width,
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

    fun renderBlocks(
        tooltip: TooltipMakerAPI,
        width: Float,
        title: String,
        theme: Theme,
        blocks: List<Block>,
    ) {
        tooltip.buildWith {
            spacer(6f)
            withLatticePulseBackground(
                accentColor = theme.accentColor,
                width = width,
            ) {
                heading(title, theme.nameColor, theme.headerBackground, 6f)
                for (block in blocks) {
                    when (block) {
                        is Paragraph -> I18nUi.addPara(
                            tooltip,
                            I18n.Categories.MOD,
                            block.key,
                            block.padTop,
                            Misc.getTextColor(),
                        )

                        is Heading -> heading(
                            I18n[I18n.Categories.MOD, block.key],
                            theme.nameColor,
                            theme.sectionBackground,
                            block.padTop,
                        )

                        is Table -> renderTable(tooltip, block, theme, width)
                    }
                }
            }
        }
    }

    private fun renderTable(
        tooltip: TooltipMakerAPI,
        table: Table,
        theme: Theme,
        width: Float,
    ) {
        val tableWidth = (width - 20f).coerceAtLeast(280f)
        val labelWidth = tableWidth * 0.62f
        val valueWidth = tableWidth - labelWidth
        tooltip.beginTable(
            Misc.getBasePlayerColor(),
            Misc.getDarkPlayerColor(),
            Misc.getBrightPlayerColor(),
            TABLE_ROW_HEIGHT,
            true,
            true,
            I18n[I18n.Categories.MOD, table.headerAKey],
            labelWidth,
            I18n[I18n.Categories.MOD, table.headerBKey],
            valueWidth,
        )
        for (row in table.rows) {
            tooltip.addRow(
                Alignment.MID,
                Misc.getTextColor(),
                I18n[I18n.Categories.MOD, row.labelKey],
                Alignment.MID,
                theme.nameColor,
                I18n[I18n.Categories.MOD, row.valueKey],
            )
        }
        tooltip.addTable("", 0, table.padTop)
    }
}
