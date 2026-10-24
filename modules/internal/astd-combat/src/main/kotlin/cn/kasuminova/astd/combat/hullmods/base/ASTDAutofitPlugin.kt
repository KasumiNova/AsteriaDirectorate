package cn.kasuminova.astd.combat.hullmods.base

import cn.kasuminova.astd.combat.hullmods.base.ASTDAutofitPlugin.Companion.resolveAutofitGoalMode
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.PluginPick
import com.fs.starfarer.api.campaign.BaseCampaignPlugin
import com.fs.starfarer.api.campaign.CampaignPlugin.PickPriority
import com.fs.starfarer.api.characters.PersonAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.plugins.AutofitPlugin
import com.fs.starfarer.api.plugins.AutofitPlugin.AutofitPluginDelegate
import com.fs.starfarer.api.plugins.impl.CoreAutofitPlugin
import java.util.Random
import org.apache.log4j.Logger

class ASTDAutofitPlugin(
    fleetCommander: PersonAPI?,
    private val combinedAutofit: AutofitPlugin? = null,
) : CoreAutofitPlugin(fleetCommander) {

    private val logger: Logger = Logger.getLogger(ASTDAutofitPlugin::class.java)

    init {
        if (combinedAutofit != null) {
            options.clear()
            options.addAll(combinedAutofit.options)
        }
    }

    override fun setRandom(random: Random) {
        super.setRandom(random)
        combinedAutofit?.setRandom(random)
    }

    override fun getCreditCost(): Int = combinedAutofit?.creditCost ?: super.getCreditCost()

    override fun getRating(current: ShipVariantAPI, target: ShipVariantAPI, delegate: AutofitPluginDelegate): Float =
        combinedAutofit?.getRating(current, target, delegate) ?: super.getRating(current, target, delegate)

    override fun doFit(current: ShipVariantAPI, target: ShipVariantAPI, maxSMods: Int, delegate: AutofitPluginDelegate) {
        if (fittingModule) {
            performFit(current, target, maxSMods, delegate)
            return
        }
        val config = ASTDDualModeRegistry.configForVariant(current)
        val preFitMode = activeDualModeId(current, config)
        val preFitHadSwitcher = config != null && current.hasHullMod(config.switcherId)
        val member = delegate.fleetMember
        val preFitCaptain = if (preFitMode != null) member?.captain else null
        ASTDDualModeMirror.beginAutofitSuppression()
        try {
            performFit(current, target, maxSMods, delegate)
        } finally {
            ASTDDualModeMirror.endAutofitSuppression()
        }
        if (config == null) return

        if (preFitHadSwitcher && !current.hasHullMod(config.switcherId)) {
            current.addMod(config.switcherId)
        }

        val desiredMode = resolveAutofitGoalMode(current, target, config, preFitMode)
        if (desiredMode != null && activeDualModeId(current, config) != desiredMode) {
            current.activateDualMode(config, desiredMode, delegate.ship?.mutableStats)
            delegate.syncUIWithVariant(current)
        }
        val postFitMode = activeDualModeId(current, config)
        restoreCaptainIfModeUnchanged(member, preFitCaptain, preFitMode, postFitMode)
    }

    private fun performFit(
        current: ShipVariantAPI,
        target: ShipVariantAPI,
        maxSMods: Int,
        delegate: AutofitPluginDelegate,
    ) {
        if (combinedAutofit != null) {
            combinedAutofit.doFit(current, target, maxSMods, delegate)
        } else {
            super.doFit(current, target, maxSMods, delegate)
        }
    }

    private fun restoreCaptainIfModeUnchanged(
        member: FleetMemberAPI?,
        preFitCaptain: PersonAPI?,
        preFitMode: String?,
        postFitMode: String?,
    ) {
        if (member == null || preFitCaptain == null || preFitMode == null) return
        if (postFitMode != preFitMode) return
        if (member.captain != null) return
        val aiCoreId = preFitCaptain.aiCoreId
        if (aiCoreId != null) {
            val cargo = Global.getSector()?.playerFleet?.cargo
            if (cargo != null) {
                cargo.removeCommodity(aiCoreId, 1f)
            } else {
                logger.warn("ASTDAutofitPlugin: 还原 AI 核心舰长时玩家货舱不可达，核心退回件无法核销（$aiCoreId）")
            }
        }
        member.captain = preFitCaptain
    }

    companion object {
        fun activeDualModeId(variant: ShipVariantAPI?, config: ASTDDualModeConfig?): String? {
            if (variant == null || config == null) return null
            return when {
                variant.permaMods.contains(config.crewedModeId) -> config.crewedModeId
                variant.permaMods.contains(config.automatedModeId) -> config.automatedModeId
                else -> null
            }
        }

        fun resolveAutofitGoalMode(
            current: ShipVariantAPI?,
            target: ShipVariantAPI?,
            config: ASTDDualModeConfig?,
            preFitMode: String? = activeDualModeId(current, config),
        ): String? {
            if (config == null) return null
            return activeDualModeId(target, config) ?: preFitMode
        }
    }
}

object ASTDAutofitPluginPicker {

    fun pick(member: FleetMemberAPI?): PluginPick<AutofitPlugin>? {
        val variant = member?.variant ?: return null
        val config = ASTDDualModeRegistry.configForVariant(variant) ?: return null
        val dualModeRelevant = variant.hasHullMod(config.switcherId) ||
                variant.permaMods.contains(config.crewedModeId) ||
                variant.permaMods.contains(config.automatedModeId)
        if (!dualModeRelevant) return null

        val commander = member.fleetCommanderForStats ?: member.fleetCommander
        val combinedAutofit = ShipMasteryAutofitIntegration.createIfEnabled(member)
        return PluginPick(ASTDAutofitPlugin(commander, combinedAutofit), PickPriority.MOD_SPECIFIC)
    }
}

class ASTDCampaignPlugin : BaseCampaignPlugin() {

    override fun getId(): String = "asteria_directorate_campaign_plugin"

    override fun pickAutofitPlugin(member: FleetMemberAPI?): PluginPick<AutofitPlugin>? =
        ASTDAutofitPluginPicker.pick(member)
}
