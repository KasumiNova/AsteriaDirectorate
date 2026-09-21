package cn.kasuminova.astd.campaign.rules

import cn.kasuminova.astd.campaign.bounty.BountyKeys
import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.campaign.TextPanelAPI
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin
import com.fs.starfarer.api.util.Misc
import java.awt.Color

/**
 * rules.csv 命令插件：在 BeginFleetEncounter 时向对话文本面板输出赏金词缀信息面板。
 *
 * 读取舰队 memory 中的 [BountyKeys.MEM_AFFIXES]（逗号分隔的词缀 hullmod ID），
 * 依次在 TextPanelAPI 中以带颜色标签的格式展示词缀编目号、名称与描述。
 * 编目号即赏金文书"追加条款"栏的条款编号（affixes.md v3 叙事口径）。
 */
class AstdShowAffixPanel : BaseCommandPlugin() {

    companion object {
        private val COLOR_S = Color(100, 220, 255)  // S 型 - 青色
        private val COLOR_M = Color(255, 200, 50)   // M 型 - 金色
        private val COLOR_R = Color(255, 80, 80)    // R 型 - 红色

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

        // 解析编队词缀与旗舰专属词缀（memory 存储的是 hullmod ID 的 CSV）
        fun parse(csv: String?): List<AffixRegistry.AffixDef> =
            csv?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.mapNotNull { AffixRegistry.getByHullModId(it) }
                .orEmpty()

        val activeDefs = parse(entityMem.getString(BountyKeys.MEM_AFFIXES))
        val flagshipDefs = parse(entityMem.getString(BountyKeys.MEM_FLAGSHIP_AFFIXES))
        if (activeDefs.isEmpty() && flagshipDefs.isEmpty()) return false

        // 渲染面板
        val text = dialog.textPanel
        text.addPara(" ") // 空行分隔

        // 标题行
        text.setFontSmallInsignia()
        text.addPara(I18n[AffixRegistry.CATEGORY, "affix_panel.header"], HEADER_COLOR)

        // 简介
        text.addPara(I18n[AffixRegistry.CATEGORY, "affix_panel.intro"], DESC_COLOR)

        text.setFontSmallInsignia()

        // 逐条展示编队词缀（条款编号 = 编目号）
        for (def in activeDefs) {
            addAffixLine(text, def)
        }

        // 旗舰专属词缀分区（如四章中军旗舰 R-17 奇点驱动；文书条款单行列明舰位）
        if (flagshipDefs.isNotEmpty()) {
            text.addPara(I18n[AffixRegistry.CATEGORY, "affix_panel.flagship_header"], HEADER_COLOR)
            for (def in flagshipDefs) {
                addAffixLine(text, def)
            }
        }

        text.setFontInsignia()
        return true
    }

    /** 输出一条词缀行：编目号 + 名称 + 效果简述，编目号与名称按词缀类型着色。 */
    private fun addAffixLine(text: TextPanelAPI, def: AffixRegistry.AffixDef) {
        val typeColor = colorForType(def.type)
        val typeTag = "[${def.catalogNo}]"

        val line = "$typeTag ${def.displayName()} — ${def.description()}"
        val label = text.addPara(line, Misc.getTextColor())
        label.setHighlight(typeTag, def.displayName())
        label.setHighlightColors(typeColor, typeColor)
    }

    private fun colorForType(type: AffixRegistry.AffixType): Color = when (type) {
        AffixRegistry.AffixType.S -> COLOR_S
        AffixRegistry.AffixType.M -> COLOR_M
        AffixRegistry.AffixType.R -> COLOR_R
    }
}
