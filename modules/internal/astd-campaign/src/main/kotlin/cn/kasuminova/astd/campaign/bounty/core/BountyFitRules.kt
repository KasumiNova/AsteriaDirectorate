package cn.kasuminova.astd.campaign.bounty.core

import com.fs.starfarer.api.combat.ShipAPI

interface BountyFitRules {

    fun smodPriority(): List<String> = SMOD_PRIORITY

    fun extraModPriority(): List<String> = EXTRA_MOD_PRIORITY

    fun reclaimBudget(size: ShipAPI.HullSize): Int = RECLAIM_BUDGETS[size] ?: 0

    fun shuCandidates(size: ShipAPI.HullSize, carrier: Boolean): List<Pair<String, Float>> =
        SHU_HULLMOD_IDS.mapNotNull { id ->
            val weight = shuWeightOf(id, size, carrier)
            if (weight > 0f) id to weight else null
        }

    fun shuHullmodIds(): List<String> = SHU_HULLMOD_IDS

    fun shuWeightOf(hullmodId: String, size: ShipAPI.HullSize, carrier: Boolean): Float = when (hullmodId) {
        SHU_HYPERSHUNT -> if (size == ShipAPI.HullSize.CAPITAL_SHIP) 5f else 0f
        SHU_DRONE_REPLICATOR -> if (carrier) 10f else 0f
        SHU_PLASMA_DYNAMO -> 3f
        SHU_SOIL_NANITES -> 3f
        SHU_FUSION_LAMP -> 5f
        SHU_DEALMAKER -> if (size == ShipAPI.HullSize.DESTROYER) 10f else 5f
        SHU_CRYO_ENGINE -> 3f
        else -> 0f
    }

    fun shuConflicts(hullmodId: String?): Set<String> = SHU_MOD_CONFLICTS[hullmodId].orEmpty()

    fun isSmodBlockedByShu(smodId: String, installedShuId: String?): Boolean =
        installedShuId != null && smodId in shuConflicts(installedShuId)

    fun isSmodApplicable(hullmodId: String, phase: Boolean, hasShield: Boolean, missileCapable: Boolean): Boolean =
        when (hullmodId) {
            "adaptive_coils" -> phase
            "hardenedshieldemitter" -> hasShield
            "magazines" -> missileCapable
            else -> true
        }

    fun planOpReclaim(caps: Int, vents: Int, opPerUnit: Int, neededOp: Int, budget: Int): Pair<Int, Int> {
        if (opPerUnit <= 0 || neededOp <= 0 || budget < opPerUnit) return 0 to 0
        var capsLeft = caps
        var ventsLeft = vents
        var reclaimed = 0
        var removeCaps = 0
        var removeVents = 0
        while (reclaimed < neededOp && reclaimed + opPerUnit <= budget && (capsLeft > 0 || ventsLeft > 0)) {
            if (capsLeft >= ventsLeft && capsLeft > 0) {
                capsLeft--
                removeCaps++
            } else {
                ventsLeft--
                removeVents++
            }
            reclaimed += opPerUnit
        }
        return removeCaps to removeVents
    }

    companion object {
        val SMOD_PRIORITY: List<String> = listOf(
            "magazines",
            "targetingunit",
            "hardenedshieldemitter",
            "heavyarmor",
            "adaptive_coils",
        )

        val EXTRA_MOD_PRIORITY: List<String> = listOf(
            "fluxcoil",
            "fluxdistributor",
            "fluxbreakers",
            "autorepair",
            "armoredweapons",
            "reinforcedhull",
            "blast_doors",
        )

        val RECLAIM_BUDGETS: Map<ShipAPI.HullSize, Int> = mapOf(
            ShipAPI.HullSize.CAPITAL_SHIP to 14,
            ShipAPI.HullSize.CRUISER to 7,
            ShipAPI.HullSize.DESTROYER to 7,
            ShipAPI.HullSize.FRIGATE to 5,
        )

        const val SHU_ID_PREFIX: String = "specialsphmod_"

        const val SHU_HYPERSHUNT: String = "specialsphmod_hypershunt_upgrades"
        const val SHU_DRONE_REPLICATOR: String = "specialsphmod_combatdronereplicator_upgrades"
        const val SHU_PLASMA_DYNAMO: String = "specialsphmod_plasmadynamo_upgrades"
        const val SHU_SOIL_NANITES: String = "specialsphmod_soilnanites_upgrades"
        const val SHU_FUSION_LAMP: String = "specialsphmod_fusionlampreactor_upgrades"
        const val SHU_DEALMAKER: String = "specialsphmod_dealmakerholosuite_upgrades"
        const val SHU_CRYO_ENGINE: String = "specialsphmod_cryoarithmeticengine_upgrades"

        val SHU_HULLMOD_IDS: List<String> = listOf(
            SHU_HYPERSHUNT,
            SHU_DRONE_REPLICATOR,
            SHU_PLASMA_DYNAMO,
            SHU_SOIL_NANITES,
            SHU_FUSION_LAMP,
            SHU_DEALMAKER,
            SHU_CRYO_ENGINE,
        )

        /** SHU 各升级脚本互斥表的等效映射；战斗无人机复制器与 expanded_deck_crew 的互斥由 SHU 安装器自行清除，本表不重复。 */
        val SHU_MOD_CONFLICTS: Map<String, Set<String>> = mapOf(
            SHU_HYPERSHUNT to setOf("fluxbreakers"),
            SHU_PLASMA_DYNAMO to setOf(
                "hardenedshieldemitter",
                "stabilizedshieldemitter",
                "extendedshieldemitter",
                "shield_shunt",
            ),
            SHU_SOIL_NANITES to setOf("autorepair"),
            SHU_FUSION_LAMP to setOf("advancedoptics"),
        )
    }
}
