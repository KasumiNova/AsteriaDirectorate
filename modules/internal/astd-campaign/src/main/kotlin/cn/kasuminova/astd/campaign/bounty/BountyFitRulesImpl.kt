package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.bounty.core.BountyFitRules
import com.fs.starfarer.api.combat.ShipAPI

class BountyFitRulesImpl : BountyFitRules {

    override fun smodPriority(): List<String> = SMOD_PRIORITY

    override fun extraModPriority(): List<String> = EXTRA_MOD_PRIORITY

    override fun reclaimBudget(size: ShipAPI.HullSize): Int = RECLAIM_BUDGETS[size] ?: 0

    override fun shuCandidates(size: ShipAPI.HullSize, carrier: Boolean): List<Pair<String, Float>> =
        SHU_HULLMOD_IDS.mapNotNull { id ->
            val weight = shuWeightOf(id, size, carrier)
            if (weight > 0f) id to weight else null
        }

    companion object {
        // SMod 统一优先级：扩展弹舱 > 目标定位系统 > 强化护盾 > 重型装甲 > 自适应相位线圈
        val SMOD_PRIORITY: List<String> = listOf(
            "magazines",
            "targetingunit",
            "hardenedshieldemitter",
            "heavyarmor",
            "adaptive_coils",
        )

        // 余 OP 普通船插优先级：附加辐能线圈 > 辐能配送器 > 辐散管道扩容 > 自动修复单元 > 炮塔装甲 > 强化舱壁 > 防爆气密门
        val EXTRA_MOD_PRIORITY: List<String> = listOf(
            "fluxcoil",
            "fluxdistributor",
            "fluxbreakers",
            "autorepair",
            "armoredweapons",
            "reinforcedhull",
            "blast_doors",
        )

        // OP 回收预算：主力舰 14 / 巡洋舰 7 / 驱逐舰 7 / 护卫舰 5
        val RECLAIM_BUDGETS: Map<ShipAPI.HullSize, Int> = mapOf(
            ShipAPI.HullSize.CAPITAL_SHIP to 14,
            ShipAPI.HullSize.CRUISER to 7,
            ShipAPI.HullSize.DESTROYER to 7,
            ShipAPI.HullSize.FRIGATE to 5,
        )

        const val SHU_HYPERSHUNT: String = "specialsphmod_hypershunt_upgrades"
        const val SHU_DRONE_REPLICATOR: String = "specialsphmod_combatdronereplicator_upgrades"
        const val SHU_PLASMA_DYNAMO: String = "specialsphmod_plasmadynamo_upgrades"
        const val SHU_SOIL_NANITES: String = "specialsphmod_soilnanites_extension"
        const val SHU_FUSION_LAMP: String = "specialsphmod_fusionlampreactor_extension"
        const val SHU_DEALMAKER: String = "specialsphmod_dealmakerholosuite_upgrades"
        const val SHU_CRYO_ENGINE: String = "specialsphmod_cryoarithmeticengine_upgrades"

        /** SHU 软联动全部候选 id（id 前缀同时用于已装特殊升级的检出）。 */
        val SHU_HULLMOD_IDS: List<String> = listOf(
            SHU_HYPERSHUNT,
            SHU_DRONE_REPLICATOR,
            SHU_PLASMA_DYNAMO,
            SHU_SOIL_NANITES,
            SHU_FUSION_LAMP,
            SHU_DEALMAKER,
            SHU_CRYO_ENGINE,
        )

        /**
         * SHU 候选权重（条件不满足按表调整为 0）：
         * 超分流器主力舰 5 否则 0；战机工厂航母 10 否则 0；等离子充能护盾 3；
         * 纳米蜂群 3；聚变电容 5；战术中继 5（驱逐舰 10）；量子散热器 3。
         */
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

        /**
         * SHU 船插的互斥普通船插（SHU 脚本内互斥清单的等效映射）。
         * 安装 SHU 船插时先移除对应冲突件；余 OP 填充阶段对命中条目跳过。
         */
        val SHU_MOD_CONFLICTS: Map<String, Set<String>> = mapOf(
            SHU_HYPERSHUNT to setOf("fluxbreakers"),
            SHU_PLASMA_DYNAMO to setOf("hardenedshieldemitter"),
            SHU_SOIL_NANITES to setOf("autorepair"),
            SHU_FUSION_LAMP to setOf("advancedoptics"),
        )

        /**
         * SMod 适用性等效判定。campaign 侧拿不到 ShipAPI，按船体特征等价游戏
         * isApplicableToShip 的关键分支：相位线圈仅相位舰、强化护盾须有护盾、
         * 扩展弹舱须具备导弹搭载能力；其余候选全舰种通用。
         */
        fun isSmodApplicable(hullmodId: String, phase: Boolean, hasShield: Boolean, missileCapable: Boolean): Boolean =
            when (hullmodId) {
                "adaptive_coils" -> phase
                "hardenedshieldemitter" -> hasShield
                "magazines" -> missileCapable
                else -> true
            }

        /**
         * OP 回收拆除方案（纯逻辑）：在预算 [budget] 内拆辐能寄存器/耗散通道凑出 [neededOp]。
         * 从存量较多的一侧交替拆除，平手先拆寄存器，避免单边拆光导致辐能系统瘸腿；
         * 单次拆除回收 [opPerUnit]，回收总量不超过预算。返回（拆寄存器数, 拆耗散通道数）。
         */
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
    }
}
