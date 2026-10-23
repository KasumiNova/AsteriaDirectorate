package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.GameState
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.campaign.listeners.RefitScreenListener
import com.fs.starfarer.api.fleet.FleetMemberAPI

class ASTDDualModeRefitListener : RefitScreenListener {

    private val log = Global.getLogger(ASTDDualModeRefitListener::class.java)

    override fun reportFleetMemberVariantSaved(member: FleetMemberAPI, dockedAt: MarketAPI?) {
        if (Global.getCurrentState() == GameState.TITLE) return
        val variant = member.variant ?: return
        if (!variant.isASTDShipVariant()) return
        val config = ASTDDualModeRegistry.configForVariant(variant) ?: return

        if (variant.hasHullMod(config.switcherId)) {
            variant.ensureASTDDualModeState(config, null)
        } else if (variant.hasASTDDualModeState(config)) {
            val target = if (variant.hasASTDDualModeAutomated(config)) config.crewedModeId else config.automatedModeId
            variant.activateDualMode(config, target, null)
            variant.addMod(config.switcherId)
            log.info("[ASTD] 装配提交收敛：${member.id} 拆即切补翻转到 $target")
        }

        if (member.fleetData?.fleet?.isPlayerFleet == true) {
            clearIncompatibleDualModeCaptain(member, config)
        }
    }
}
