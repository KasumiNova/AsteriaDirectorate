package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ui.LabelAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import kotlin.math.roundToInt

/**
 * SIC 联动的无人舰点数读写与 tooltip 渲染辅助（本包内部共用）。
 *
 * tooltip 复刻 SIC 既有风格（SecondInCommand 1.8.0 源码口径）：
 * - 加成行整行高亮（`addPara("%s", 0f, highlight, highlight, text)`，
 *   文本作为 %s 值传入，规避 I18n 文本中的 `%` 被 format 机制误解析）；
 * - 说明子行 `Misc.getTextColor()` 底 + 高亮子串（literal addPara + setHighlight，
 *   与 SIC SCBaseAutoPointsSkillPlugin 末尾的 setHighlight/setHighlightColors 用法一致）；
 * - 脚注/失效行灰 `Misc.getGrayColor()`；负面 `Misc.getNegativeHighlightColor()`。
 * 全部可见文本经 I18n 取得后传入，此处只做着色。
 */
internal object SicShared {

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

    // ─── tooltip 渲染（SIC 风格） ───

    /** 加成行：整行高亮。 */
    fun highlightLine(tooltip: TooltipMakerAPI, text: String) {
        tooltip.addPara("%s", 0f, Misc.getHighlightColor(), Misc.getHighlightColor(), text)
    }

    /** 说明子行：文本底色 + 指定子串高亮。 */
    fun subLine(tooltip: TooltipMakerAPI, text: String, vararg highlights: String) {
        val label: LabelAPI = tooltip.addPara(text, Misc.getTextColor(), 0f)
        if (highlights.isNotEmpty()) {
            label.setHighlight(*highlights)
            label.setHighlightColors(*Array(highlights.size) { Misc.getHighlightColor() })
        }
    }

    /** 脚注/失效行：整行灰色（可选子串仍以高亮色标出，用于展示被屏蔽的数值）。 */
    fun grayLine(tooltip: TooltipMakerAPI, text: String, vararg highlights: String) {
        val label: LabelAPI = tooltip.addPara(text, Misc.getGrayColor(), 0f)
        if (highlights.isNotEmpty()) {
            label.setHighlight(*highlights)
            label.setHighlightColors(*Array(highlights.size) { Misc.getHighlightColor() })
        }
    }

    /** 负面行：文本底色 + 指定子串负面高亮。 */
    fun negativeLine(tooltip: TooltipMakerAPI, text: String, vararg highlights: String) {
        val label: LabelAPI = tooltip.addPara(text, Misc.getTextColor(), 0f)
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
