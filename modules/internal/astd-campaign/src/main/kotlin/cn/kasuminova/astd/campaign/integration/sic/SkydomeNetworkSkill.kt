package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.campaign.bounty.StandardCores
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.impl.campaign.ids.Personalities
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import second_in_command.SCData
import second_in_command.specs.SCBaseSkillPlugin

class SkydomeNetworkSkill : SCBaseSkillPlugin() {

    override fun getAffectsString(): String =
        I18n[I18n.Categories.MOD, "ui.sic.skydome_network.affects"]

    override fun addTooltip(data: SCData, tooltip: TooltipMakerAPI) {
        val points = SicShared.autoPointsFor(SicTuning.SKYDOME_NETWORK_AUTO_POINTS, isPlayer = true)
        val reduction = DifficultyTuningImpl.valueFor(SicTuning.SKYDOME_NETWORK_CORE_MULT_REDUCTION, true)

        tooltip.hullmodCard(
            SicShared.SKILL_TOOLTIP_WIDTH,
            SicShared.CARD_THEME,
            null,
            topGapFill = 0f,
            contentInset = SicShared.CARD_CONTENT_INSET,
        ) {
            custom { SicShared.autoPointsHeader(it, 8f) }
            para(
                "ui.sic.skydome_network.line.points",
                Misc.getHighlightColor(),
                8f,
                v("points", points),
            )
            para(
                "ui.sic.skydome_network.line.core_mult",
                Misc.getHighlightColor(),
                8f,
                v("mult", SicShared.formatNumber(reduction)),
            )
            para("ui.sic.skydome_network.sub.core_mult", Misc.getHighlightColor(), 2f)
            para("ui.sic.skydome_network.line.personality", Misc.getHighlightColor())
            para("ui.sic.skydome_network.sub.personality", Misc.getHighlightColor(), 2f)
            custom { SicShared.autoPointsFooter(it, 10f) }
            custom { SicShared.grayLine(it, I18n[I18n.Categories.MOD, "ui.sic.skydome_network.flavor"], 10f) }
        }
    }

    override fun applyEffectsBeforeShipCreation(
        data: SCData,
        stats: MutableShipStatsAPI,
        variant: ShipVariantAPI,
        hullSize: ShipAPI.HullSize,
        id: String,
    ) {
        val captain = stats.fleetMember?.captain ?: return
        val tier = StandardCores.byCommodity(captain.aiCoreId) ?: return
        val reduction = DifficultyTuningImpl.valueFor(SicTuning.SKYDOME_NETWORK_CORE_MULT_REDUCTION, data.isPlayer)
        stats.dynamic.getStat(SicTuning.SC_AUTO_POINTS_MULT_STAT)
            .modifyMult(id, SicTuning.coreMultFactor(tier.autoPointsMult, reduction))
    }

    override fun advance(data: SCData, amunt: Float) {
        if (data.isNPC) return
        SicShared.applyAutoPoints(spec.id, spec.name, SicTuning.SKYDOME_NETWORK_AUTO_POINTS, data.isPlayer)
        applyDoctrinePersonalities()
    }

    override fun onActivation(data: SCData) {
        if (data.isNPC) return
        SicShared.applyAutoPoints(spec.id, spec.name, SicTuning.SKYDOME_NETWORK_AUTO_POINTS, data.isPlayer)
        applyDoctrinePersonalities()
    }

    override fun onDeactivation(data: SCData) {
        if (data.isNPC) return
        SicShared.removeAutoPoints(spec.id)
        restoreDoctrinePersonalities()
    }

    private fun applyDoctrinePersonalities() {
        val target = SicTuning.doctrinePersonality(Global.getSector().playerFaction.doctrine.aggression)
        for (member in Global.getSector().playerFleet.fleetData.membersListCopy) {
            val captain = member.captain ?: continue
            if (!captain.isAICore) continue
            val current = captain.personalityAPI.id
            if (current == target) continue
            val marked = captain.memoryWithoutUpdate.contains(SicTuning.DOCTRINE_PERSONALITY_MEM_KEY)
            if (current != Personalities.RECKLESS && !marked) continue
            captain.setPersonality(target)
            captain.memoryWithoutUpdate.set(SicTuning.DOCTRINE_PERSONALITY_MEM_KEY, target)
        }
    }

    private fun restoreDoctrinePersonalities() {
        for (member in Global.getSector().playerFleet.fleetData.membersListCopy) {
            val captain = member.captain ?: continue
            val memory = captain.memoryWithoutUpdate
            if (!memory.contains(SicTuning.DOCTRINE_PERSONALITY_MEM_KEY)) continue
            val applied = memory.getString(SicTuning.DOCTRINE_PERSONALITY_MEM_KEY)
            memory.unset(SicTuning.DOCTRINE_PERSONALITY_MEM_KEY)
            if (captain.personalityAPI.id == applied) {
                captain.setPersonality(Personalities.RECKLESS)
            }
        }
    }
}
