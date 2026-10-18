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

    /**
     * SIC 技能 tooltip 卡片的内容流宽。SIC SCSkillTooltipCreator.getTooltipWidth 固定 700f，
     * 但 BaseTooltipCreator 系 tooltip 的 700f 是含边框总宽，内容流宽 = 700 - 24（边框），
     * 与原版船插宿主及 ASTDHullModTooltipBackground「总宽 = 流宽 + 24」的分支判定同一契约。
     * 此前误传总宽 700：背景走了无边框分支（卡片相对 tooltip 边框外扩），文本换行宽
     * 700-2×inset 超出实际卡片右缘，长段落（无人舰点数固定段）顶穿卡片右边框。
     */
    const val SKILL_TOOLTIP_WIDTH = 676f

    /**
     * 智能联结改造按钮 tooltip 卡片的内容流宽（LunaLib BaseRefitButton.getToolipWidth 默认
     * 300f 为含边框总宽，内容流宽 = 300 - 24，契约同 [SKILL_TOOLTIP_WIDTH]）。
     */
    const val REFIT_TOOLTIP_WIDTH = 276f

    /** SIC/LunaLib 宿主卡片内容水平内边距（宿主内容流无内缩，对齐原版船插 tooltip 约 10px 量级内边距）。 */
    const val CARD_CONTENT_INSET = 10f

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

    /**
     * 自动化标准块顶部两行（SIC SCBaseAutoPointsSkillPlugin.kt:39-40 原文原样式：
     * 整行 Misc.getHighlightColor() 高亮黄，与效果行同色；SIC 源码中位于主效果行之后，
     * 本项目按用户指令置于卡片内容最前）。
     */
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

    /** 无人舰点数机制固定段（SIC 自动化系技能 tooltip 统一规范，SCBaseAutoPointsSkillPlugin 口径）：灰底 + 三档核心倍率子串高亮黄 + 安全联锁子串负色红。倍率运行时读取，禁止写死。 */
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

    // ─── 展示格式化（与 ASTDCombatIntelSkill 同一口径） ───

    /** 0.2 → "20%"（百分号由值携带，模板只放占位符）。 */
    fun formatPercent(fraction: Float): String = "${(fraction * 100f).roundToInt()}%"

    /** 整数值不带小数点（120 而非 120.0），小数值原样（0.5）。 */
    fun formatNumber(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
}
