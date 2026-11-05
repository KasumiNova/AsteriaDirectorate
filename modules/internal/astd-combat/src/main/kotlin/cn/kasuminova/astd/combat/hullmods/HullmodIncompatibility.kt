package cn.kasuminova.astd.combat.hullmods

import cn.kasuminova.astd.combat.hullmods.arc.ASTDAdvancedEnergyIntegrationHullMod
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionShipIds
import cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityStabilizerHullMod
import cn.kasuminova.astd.combat.hullmods.lens.AdvancedEcmNetworkTuning
import com.fs.starfarer.api.impl.campaign.ids.HullMods

object HullmodIncompatibility {

    /**
     * 原版目标定位系船插互斥组（含历史别名 id）。原版的互斥只在候选方 isApplicableToShip 判定
     * （AdvancedTargetingCore 自身判定被注释，恒可装），程序化加装（赏金 SMod 管线 addPermaMod）
     * 不经过装配 UI 判定，必须在此补全为双向映射，否则会出现内建先进目标定位核心与 SMod
     * 目标定位系统叠加的非法组合。
     */
    val TARGETING_FAMILY: Set<String> = setOf(
        "targetingunit",
        "integratedtargetingunit",
        "dedicated_targeting_core",
        "dedicatedtargetingcore",
        "advancedcore",
        HullMods.DISTRIBUTED_FIRE_CONTROL,
    )

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
    ) + TARGETING_FAMILY.associateWith { TARGETING_FAMILY - it }

    fun forbiddenByController(controllerHullmodId: String): Set<String> =
        FORBIDDEN_BY_CONTROLLER[controllerHullmodId].orEmpty()

    fun forbiddenCandidates(presentHullmodIds: Collection<String>): Set<String> =
        presentHullmodIds.flatMapTo(mutableSetOf()) { forbiddenByController(it) }
}
