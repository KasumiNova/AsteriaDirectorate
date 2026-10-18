package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.ui.dsl.HullmodTheme
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ui.LabelAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import kotlin.math.roundToInt

/**
 * SIC 联动的无人舰点数读写与 tooltip 渲染辅助（本包内部共用）。
 *
 * tooltip 样式为 ASTD 既定决定（用户指令，非 SIC 原版样式）：文本默认高亮黄、禁止白字；
 * 灰色仅用于失效/不生效行；负面行有子串时黄底 + 负色子串，无子串时整行负色。
 * 整行高亮的加成行不经本类，直接走 DSL `para` 块（显式 baseColor 高亮黄），
 * 仅子串着色行（subLine/grayLine/negativeLine）经 `custom` 块调用本类。
 * 全部可见文本经 I18n 取得后传入，此处只做着色与行距。
 */
internal object SicShared {

    /** SIC 联动 tooltip 卡片主题：透镜线紫（内置主题色板中最接近菀星紫 (160,150,235) 者）。 */
    val CARD_THEME: HullmodTheme = HullmodThemes.LENS

    /** SIC 技能 tooltip 卡宽（SIC SCSkillTooltipCreator.getTooltipWidth 固定 700f）。 */
    const val SKILL_TOOLTIP_WIDTH = 700f

    /** 智能联结改造按钮 tooltip 卡宽（LunaLib BaseRefitButton.getToolipWidth 默认 300f）。 */
    const val REFIT_TOOLTIP_WIDTH = 300f

    // ─── 无人舰点数（SIC sc_auto_dp 契约，见 SicTuning） ───

    /** 按难度解析无人舰点数并四舍五入为整数（玩家阵营按我方档位）。 */
    fun autoPointsFor(entry: ScalingEntry, isPlayer: Boolean): Int =
        DifficultyTuningImpl.valueFor(entry, isPlayer).roundToInt()

    /**
     * 写入无人舰点数（modifyFlat 同 sourceId 幂等，advance 逐帧重写以跟随难度设置变更）。
     * sourceId 带 [SicTuning.EXTERNAL_MOD_SUFFIX] 后缀，SIC 界面显示为外部来源。
     */
    fun applyAutoPoints(skillId: String, skillName: String, entry: ScalingEntry, isPlayer: Boolean) {
        Global.getSector().playerPerson.stats.dynamic.getMod(SicTuning.SC_AUTO_DP_MOD)
            .modifyFlat(skillId + SicTuning.EXTERNAL_MOD_SUFFIX, autoPointsFor(entry, isPlayer).toFloat(), skillName)
    }

    /** 停用回收：与 [applyAutoPoints] 同 sourceId unmodify。 */
    fun removeAutoPoints(skillId: String) {
        Global.getSector().playerPerson.stats.dynamic.getMod(SicTuning.SC_AUTO_DP_MOD)
            .unmodify(skillId + SicTuning.EXTERNAL_MOD_SUFFIX)
    }

    // ─── tooltip 渲染（子串着色行；整行高亮黄直接走 DSL para 块，不经本类） ───

    /** 说明子行：高亮黄底 + 指定子串高亮。 */
    fun subLine(tooltip: TooltipMakerAPI, text: String, padTop: Float, vararg highlights: String) {
        val label: LabelAPI = tooltip.addPara(text, Misc.getHighlightColor(), padTop)
        if (highlights.isNotEmpty()) {
            label.setHighlight(*highlights)
            label.setHighlightColors(*Array(highlights.size) { Misc.getHighlightColor() })
        }
    }

    /** 脚注/失效行：整行灰色（可选子串仍以高亮色标出，用于展示被屏蔽的数值）。 */
    fun grayLine(tooltip: TooltipMakerAPI, text: String, padTop: Float, vararg highlights: String) {
        val label: LabelAPI = tooltip.addPara(text, Misc.getGrayColor(), padTop)
        if (highlights.isNotEmpty()) {
            label.setHighlight(*highlights)
            label.setHighlightColors(*Array(highlights.size) { Misc.getHighlightColor() })
        }
    }

    /** 负面行：有子串时高亮黄底 + 子串负面高亮；无子串时整行负面色。 */
    fun negativeLine(tooltip: TooltipMakerAPI, text: String, padTop: Float, vararg highlights: String) {
        val baseColor = if (highlights.isEmpty()) Misc.getNegativeHighlightColor() else Misc.getHighlightColor()
        val label: LabelAPI = tooltip.addPara(text, baseColor, padTop)
        if (highlights.isNotEmpty()) {
            label.setHighlight(*highlights)
            label.setHighlightColors(*Array(highlights.size) { Misc.getNegativeHighlightColor() })
        }
    }

    // ─── 展示格式化（与 ASTDCombatIntelSkill 同一口径） ───

    /** 0.2 → "20%"（百分号由值携带，模板只放占位符）。 */
    fun formatPercent(fraction: Float): String = "${(fraction * 100f).roundToInt()}%"

    /** 整数值不带小数点（120 而非 120.0），小数值原样（0.5）。 */
    fun formatNumber(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
}
