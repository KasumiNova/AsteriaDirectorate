package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.HullmodTheme
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.impl.campaign.AICoreOfficerPluginImpl
import com.fs.starfarer.api.ui.LabelAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import kotlin.math.roundToInt

internal object SicShared {

    val CARD_THEME: HullmodTheme = HullmodThemes.LENS

    /** SIC 技能 tooltip 内容流宽：SCSkillTooltipCreator 总宽 700f 含 24 边框。 */
    const val SKILL_TOOLTIP_WIDTH = 676f

    /** LunaLib BaseRefitButton tooltip 总宽 300f 含 24 边框。 */
    const val REFIT_TOOLTIP_WIDTH = 276f

    const val CARD_CONTENT_INSET = 10f

    fun autoPointsFor(entry: ScalingEntry, isPlayer: Boolean): Int =
        DifficultyTuningImpl.valueFor(entry, isPlayer).roundToInt()

    fun applyAutoPoints(skillId: String, skillName: String, entry: ScalingEntry, isPlayer: Boolean) {
        Global.getSector().playerPerson.stats.dynamic.getMod(SicTuning.SC_AUTO_DP_MOD)
            .modifyFlat(skillId + SicTuning.EXTERNAL_MOD_SUFFIX, autoPointsFor(entry, isPlayer).toFloat(), skillName)
    }

    fun removeAutoPoints(skillId: String) {
        Global.getSector().playerPerson.stats.dynamic.getMod(SicTuning.SC_AUTO_DP_MOD)
            .unmodify(skillId + SicTuning.EXTERNAL_MOD_SUFFIX)
    }

    fun subLine(tooltip: TooltipMakerAPI, text: String, padTop: Float, vararg highlights: String) {
        val label: LabelAPI = tooltip.addPara(text, Misc.getHighlightColor(), padTop)
        if (highlights.isNotEmpty()) {
            label.setHighlight(*highlights)
            label.setHighlightColors(*Array(highlights.size) { Misc.getHighlightColor() })
        }
    }

    fun grayLine(tooltip: TooltipMakerAPI, text: String, padTop: Float, vararg highlights: String) {
        val label: LabelAPI = tooltip.addPara(text, Misc.getGrayColor(), padTop)
        if (highlights.isNotEmpty()) {
            label.setHighlight(*highlights)
            label.setHighlightColors(*Array(highlights.size) { Misc.getHighlightColor() })
        }
    }

    fun negativeLine(tooltip: TooltipMakerAPI, text: String, padTop: Float, vararg highlights: String) {
        val baseColor = if (highlights.isEmpty()) Misc.getNegativeHighlightColor() else Misc.getHighlightColor()
        val label: LabelAPI = tooltip.addPara(text, baseColor, padTop)
        if (highlights.isNotEmpty()) {
            label.setHighlight(*highlights)
            label.setHighlightColors(*Array(highlights.size) { Misc.getNegativeHighlightColor() })
        }
    }

    fun autoPointsHeader(tooltip: TooltipMakerAPI, padTop: Float) {
        tooltip.addPara(
            I18n[I18n.Categories.MOD, "ui.sic.shared.auto_points_header_recovery"],
            Misc.getHighlightColor(),
            padTop,
        )
        tooltip.addPara(
            I18n[I18n.Categories.MOD, "ui.sic.shared.auto_points_header_ai_core"],
            Misc.getHighlightColor(),
            0f,
        )
    }

    fun autoPointsFooter(tooltip: TooltipMakerAPI, padTop: Float) {
        val alpha = AICoreOfficerPluginImpl.ALPHA_MULT.roundToInt()
        val beta = AICoreOfficerPluginImpl.BETA_MULT.roundToInt()
        val gamma = AICoreOfficerPluginImpl.GAMMA_MULT.roundToInt()
        val lock = I18n[I18n.Categories.MOD, "ui.sic.shared.auto_points_footer_lock"]
        val label: LabelAPI = tooltip.addPara(
            I18n.t(
                I18n.Categories.MOD, "ui.sic.shared.auto_points_footer",
                "alpha" to alpha, "beta" to beta, "gamma" to gamma, "lock" to lock,
            ),
            Misc.getGrayColor(),
            padTop,
        )
        label.setHighlight("${alpha}×", "${beta}×", "${gamma}×", lock)
        label.setHighlightColors(
            Misc.getHighlightColor(), Misc.getHighlightColor(), Misc.getHighlightColor(),
            Misc.getNegativeHighlightColor(),
        )
    }

    fun formatPercent(fraction: Float): String = "${(fraction * 100f).roundToInt()}%"

    fun formatNumber(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
}
