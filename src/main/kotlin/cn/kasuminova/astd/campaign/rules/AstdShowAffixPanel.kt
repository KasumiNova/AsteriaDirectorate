package cn.kasuminova.astd.campaign.rules

import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.campaign.bounty.BountyKeys
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin
import com.fs.starfarer.api.util.Misc
import java.awt.Color

/**
 * rules.csv 命令插件：在 BeginFleetEncounter 时向对话文本面板输出赏金词缀信息面板。
 *
 * 读取舰队 memory 中的 [BountyKeys.MEM_AFFIXES]（逗号分隔的词缀 ID），
 * 依次在 TextPanelAPI 中以带颜色标签的格式展示词缀名称与描述。
 */
class AstdShowAffixPanel : BaseCommandPlugin() {

    companion object {
        private val COLOR_T1 = Color(100, 220, 255)  // Tier 1 - 青色
        private val COLOR_T2 = Color(255, 200, 50)   // Tier 2 - 金色
        private val COLOR_T3 = Color(255, 80, 80)    // Tier 3 - 红色

        private val HEADER_COLOR = Color(200, 180, 255) // 面板标题 - 淡紫
        private val DESC_COLOR: Color = Misc.getGrayColor()
    }

    override fun execute(
        ruleId: String,
        dialog: InteractionDialogAPI?,
        params: List<Misc.Token>,
        memoryMap: Map<String, MemoryAPI>,
    ): Boolean {
        if (dialog == null) return false

        val entityMem = memoryMap["entity"] ?: return false
        val affixCsv = entityMem.getString(BountyKeys.MEM_AFFIXES)
        if (affixCsv.isNullOrBlank()) return false

        // 解析词缀 ID 列表（MEM_AFFIXES 存储的是 hullmod ID）
        val activeDefs = affixCsv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { AffixRegistry.getByHullModId(it) }
        if (activeDefs.isEmpty()) return false

        // 渲染面板
        val text = dialog.textPanel
        text.addPara(" ") // 空行分隔

        // 标题行
        text.setFontSmallInsignia()
        text.addPara(I18n["asteria_directorate_bounty", "affix_panel.header"], HEADER_COLOR)

        // 简介
        text.addPara(I18n["asteria_directorate_bounty", "affix_panel.intro"], DESC_COLOR)

        text.setFontSmallInsignia()

        // 逐条展示词缀
        for (def in activeDefs) {
            val typeColor = colorForTier(def.tier)
            val typeTag = tagForTier(def.tier)

            val name = def.displayName()
            val desc = def.description()

            val line = "$typeTag $name — $desc"
            val label = text.addPara(line, Misc.getTextColor())
            // 高亮类型标签和词缀名称
            label.setHighlight(typeTag, name)
            label.setHighlightColors(typeColor, typeColor)
        }

        text.setFontInsignia()
        return true
    }

    private fun colorForTier(tier: Int): Color = when (tier.coerceIn(1, 3)) {
        1 -> COLOR_T1
        2 -> COLOR_T2
        3 -> COLOR_T3
        else -> COLOR_T1
    }

    private fun tagForTier(tier: Int): String = when (tier.coerceIn(1, 3)) {
        1 -> "[I]"
        2 -> "[II]"
        3 -> "[III]"
        else -> "[I]"
    }
}
