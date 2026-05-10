package cn.kasuminova.astd.campaign.items

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.campaign.CargoStackAPI
import com.fs.starfarer.api.campaign.CargoTransferHandlerAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.campaign.econ.SubmarketAPI
import com.fs.starfarer.api.campaign.impl.items.BaseSpecialItemPlugin
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc

/**
 * 回声·校验核心（彩蛋物品）。
 *
 * 设计目标：
 * - 作为"等级 8 的核心物品"进入货舱
 * - 带 no_sell tag：尽量禁止出售
 * - tooltip 明确其剧情定位与约束
 */
class EchoCoreItemPlugin : BaseSpecialItemPlugin() {

    override fun init(stack: CargoStackAPI) {
        super.init(stack)
    }

    override fun getName(): String = spec.name

    override fun getPrice(market: MarketAPI, submarket: SubmarketAPI): Int = 0

    override fun hasRightClickAction(): Boolean = false

    override fun isTooltipExpandable(): Boolean = true

    override fun getTooltipWidth(): Float = 420f

    override fun createTooltip(
        tooltip: TooltipMakerAPI,
        expanded: Boolean,
        transferHandler: CargoTransferHandlerAPI,
        stackSource: Any,
    ) {
        val opad = 10f
        val h = Misc.getHighlightColor()
        val g = Misc.getGrayColor()
        val n = Misc.getNegativeHighlightColor()

        tooltip.addTitle(name)
        tooltip.addPara(
            I18n.j1("asteria_directorate", "echo_core.tooltip.level", "level", "8"),
            opad, h, "8"
        )
        tooltip.addPara(
            I18n.j1(
                "asteria_directorate", "echo_core.tooltip.kind",
                "kind", I18n.j("asteria_directorate", "echo_core.tooltip.kind.value")
            ),
            0f, h, I18n.j("asteria_directorate", "echo_core.tooltip.kind.value")
        )
        tooltip.addPara(
            I18n.j1(
                "asteria_directorate", "echo_core.tooltip.state",
                "state", I18n.j("asteria_directorate", "echo_core.tooltip.state.value")
            ),
            0f, h, I18n.j("asteria_directorate", "echo_core.tooltip.state.value")
        )

        if (expanded) {
            tooltip.addPara("", opad)
            tooltip.addPara(I18n.j("asteria_directorate", "echo_core.tooltip.expanded.0"), 0f, g)
            tooltip.addPara(I18n.j("asteria_directorate", "echo_core.tooltip.expanded.1"), 0f, g)
            tooltip.addPara(I18n.j("asteria_directorate", "echo_core.tooltip.expanded.2"), opad, g)
            tooltip.addPara(I18n.j("asteria_directorate", "echo_core.tooltip.expanded.3"), opad, n)
        }
    }
}
