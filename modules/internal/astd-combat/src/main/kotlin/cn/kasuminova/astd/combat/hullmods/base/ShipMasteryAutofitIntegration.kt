package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.plugins.AutofitPlugin
import shipmastery.plugin.SModAutofitCampaignPlugin
import shipmastery.plugin.SModAutofitCampaignPluginSP

object ShipMasteryAutofitIntegration {

    const val MOD_ID: String = "shipmasterysystem"

    fun createIfEnabled(member: FleetMemberAPI?): AutofitPlugin? {
        if (!Global.getSettings().modManager.isModEnabled(MOD_ID)) return null
        return SModAutofitCampaignPlugin(null).pickAutofitPlugin(member)?.plugin
            ?: SModAutofitCampaignPluginSP().pickAutofitPlugin(member)?.plugin
    }
}
