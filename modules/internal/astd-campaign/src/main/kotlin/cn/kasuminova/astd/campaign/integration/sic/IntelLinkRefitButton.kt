package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import lunalib.lunaRefit.BaseRefitButton
import org.apache.log4j.Logger
import second_in_command.SCUtils

class IntelLinkRefitButton : BaseRefitButton() {

    private val log: Logger = Global.getLogger(IntelLinkRefitButton::class.java)

    override fun getButtonName(member: FleetMemberAPI, variant: ShipVariantAPI): String =
        I18n[I18n.Categories.MOD, "ui.sic.intel_link.refit.button"]

    override fun getIconName(member: FleetMemberAPI, variant: ShipVariantAPI): String =
        "graphics/icons/skills/astd_skill_combat_intel.png"

    // LunaLib 以 Java 平台类型回调，market 在无市场上下文时为 null（RefitButtonAdder.market 可空），签名必须可空
    override fun shouldShow(member: FleetMemberAPI, variant: ShipVariantAPI, market: MarketAPI?): Boolean =
        SicTuning.intelLinkButtonVisible(
            skillActive = SCUtils.getPlayerData().isSkillActive(SicTuning.INTEL_LINK_SKILL_ID),
            hasMarker = variant.hasHullMod(SicTuning.INTEL_LINK_HULLMOD_ID),
            fighter = member.isFighterWing,
        )

    override fun isClickable(member: FleetMemberAPI, variant: ShipVariantAPI, market: MarketAPI?): Boolean {
        val cargo = Global.getSector().playerFleet?.cargo ?: return false
        return SicTuning.intelLinkButtonClickable(
            cores = cargo.getCommodityQuantity(SicTuning.INTEL_LINK_CORE_COMMODITY),
            storyPoints = Global.getSector().playerStats.storyPoints,
        )
    }

    override fun onClick(member: FleetMemberAPI, variant: ShipVariantAPI, event: InputEventAPI, market: MarketAPI?) {
        val cargo = Global.getSector().playerFleet?.cargo ?: return
        cargo.removeCommodity(SicTuning.INTEL_LINK_CORE_COMMODITY, 1f)
        Global.getSector().playerStats.spendStoryPoints(SicTuning.INTEL_LINK_STORY_POINT_COST, true, null, true, null)
        variant.addPermaMod(SicTuning.INTEL_LINK_HULLMOD_ID)
        refreshVariant()
        refreshButtonList()
        log.info("[ASTD] 智能联结改造完成：${member.shipName}（${variant.hullVariantId}）")
    }

    override fun hasTooltip(member: FleetMemberAPI, variant: ShipVariantAPI, market: MarketAPI?): Boolean = true

    override fun addTooltip(tooltip: TooltipMakerAPI, member: FleetMemberAPI, variant: ShipVariantAPI, market: MarketAPI?) {
        tooltip.hullmodCard(
            SicShared.REFIT_TOOLTIP_WIDTH,
            SicShared.CARD_THEME,
            I18n[I18n.Categories.MOD, "ui.sic.intel_link.refit.button"],
            topGapFill = 0f,
            contentInset = SicShared.CARD_CONTENT_INSET,
        ) {
            para("ui.sic.intel_link.refit.tooltip.effect", Misc.getHighlightColor())

            val cargo = Global.getSector().playerFleet?.cargo
            val cores = cargo?.getCommodityQuantity(SicTuning.INTEL_LINK_CORE_COMMODITY) ?: 0f
            val storyPoints = Global.getSector().playerStats.storyPoints
            para(
                "ui.sic.intel_link.refit.tooltip.cost",
                Misc.getHighlightColor(),
                8f,
                v("cores", cores.toInt()),
                v("points", storyPoints),
            )

            if (cores < 1f) {
                custom {
                    SicShared.negativeLine(
                        it,
                        I18n[I18n.Categories.MOD, "ui.sic.intel_link.refit.tooltip.missing_core"],
                        8f,
                    )
                }
            }
            if (storyPoints < SicTuning.INTEL_LINK_STORY_POINT_COST) {
                custom {
                    SicShared.negativeLine(
                        it,
                        I18n[I18n.Categories.MOD, "ui.sic.intel_link.refit.tooltip.missing_sp"],
                        8f,
                    )
                }
            }
        }
    }
}
