package cn.kasuminova.astd.combat.hullmods

import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

/**
 * 智能联结标记船插（astd_sc_intel_link，docs/design/skills/10-sic-skills.md 阶段四）。
 *
 * 纯标记 + 描述，无效果逻辑：由 SIC 联动「智能联结」天赋的改造按钮
 * （cn.kasuminova.astd.campaign.integration.sic.IntelLinkRefitButton，
 * LunaLib 额外改造选项）以 addPermaMod 安装；效果由 SIC 技能 IntelLinkSkill
 * 在入场时为本船军官附加精英化「菀星战斗智能」驱动，天赋撤下后下一场战斗自然失效。
 * 本类不引用任何 SIC / LunaLib 类型，无联动环境亦可安全加载。
 */
class ASTDIntelLinkHullMod : BaseHullMod() {

    companion object {
        private val THEME = HullmodThemes.ARC
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            para("ui.sic.intel_link.hullmod.desc")
            para(
                "ui.sic.intel_link.hullmod.requirement",
                hl("智能联结", HullmodTone.HIGHLIGHT),
            )
        }
    }

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor
}
