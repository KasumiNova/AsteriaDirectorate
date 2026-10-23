package cn.kasuminova.astd.combat.hullmods

import cn.kasuminova.astd.combat.hullmods.arc.ASTDAdvancedEnergyIntegrationHullMod
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionShipIds
import cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityStabilizerHullMod
import cn.kasuminova.astd.combat.hullmods.lens.AdvancedEcmNetworkTuning
import com.fs.starfarer.api.impl.campaign.ids.HullMods

object HullmodIncompatibility {

    val FORBIDDEN_BY_CONTROLLER: Map<String, Set<String>> = mapOf(
        ASTDArcProductionShipIds.HULLMOD_ARC_ADVANCED_TARGETING_SYSTEM to setOf(
            "targetingunit",
            "integratedtargetingunit",
            "dedicated_targeting_core",
            "dedicatedtargetingcore",
            "advancedcore",
            "advancedoptics",
            "supercomputer",
            HullMods.DISTRIBUTED_FIRE_CONTROL,
        ),
        ASTDArcProductionShipIds.HULLMOD_PLASMA_ARMOR_SHIELD to setOf(
            HullMods.SHIELD_SHUNT,
            HullMods.HARDENED_SHIELDS,
        ),
        ASTDAdvancedEnergyIntegrationHullMod.MOD_ID to setOf(
            HullMods.ARMOREDWEAPONS,
            HullMods.TURRETGYROS,
        ),
        ASTDSingularityStabilizerHullMod.ID_ARC to setOf(HullMods.SAFETYOVERRIDES),
        ASTDSingularityStabilizerHullMod.ID_LENS to setOf(HullMods.SAFETYOVERRIDES),
        AdvancedEcmNetworkTuning.HULLMOD_ID to setOf(
            HullMods.ECM,
            HullMods.ECCM,
        ),
    )

    fun forbiddenByController(controllerHullmodId: String): Set<String> =
        FORBIDDEN_BY_CONTROLLER[controllerHullmodId].orEmpty()

    fun forbiddenCandidates(presentHullmodIds: Collection<String>): Set<String> =
        presentHullmodIds.flatMapTo(mutableSetOf()) { forbiddenByController(it) }
}
