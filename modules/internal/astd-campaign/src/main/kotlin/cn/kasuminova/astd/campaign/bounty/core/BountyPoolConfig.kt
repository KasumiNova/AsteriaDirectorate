package cn.kasuminova.astd.campaign.bounty.core

import com.fs.starfarer.api.combat.ShipAPI

/**
 * 单个赏金的随机混编池配置。
 *
 * MagicBounty 数据侧的 fleet_preset_ships 只负责最小 ASTD 编成，
 * 舰队上限内的随机混编（ASTD 与余晖 1:1 交替）由代码侧完成，
 * 各赏金对混编倾向的差异化要求（航母压制 / 相位强化 / 驱逐舰优选）收敛在本配置。
 */
data class BountyPoolConfig(
    /** 航母系角色（carrierSmall/carrierLarge）权重倍率，小于 1 表示压制航母出场率。 */
    val carrierWeightMult: Float = 1f,
    /** 相位系角色（phaseMedium）权重倍率，大于 1 表示强化相位舰出场率。 */
    val phaseWeightMult: Float = 1f,
    /** ASTD 驱逐舰抽取的 best-of-K 次数，1 表示不优选，大于 1 时保留 K 次抽取中部署点最高者。 */
    val destroyerBestOf: Int = 1,
) {

    companion object {
        val DEFAULT: BountyPoolConfig = BountyPoolConfig()

        val OVERRIDES: Map<String, BountyPoolConfig> = mapOf(
            // 坠星主控：舰载机蜂群压制，避免航母稀释主力舰密度
            "astd_bounty_xc_001" to BountyPoolConfig(carrierWeightMult = 0.25f),
            // 密蒙主控：相位猎杀编队，相位舰三倍权重
            "astd_bounty_zw_002" to BountyPoolConfig(phaseWeightMult = 3f),
            // 星翼主控：远程火力编队，驱逐舰六次优选保留高部署点个体
            "astd_bounty_xc_002" to BountyPoolConfig(destroyerBestOf = 6),
        )

        fun forBountyKey(key: String?): BountyPoolConfig = OVERRIDES[key] ?: DEFAULT

        /** 混编池分边：随机部分 ASTD 与余晖 1:1 交替，ASTD 先出。 */
        fun pickPoolSide(addedAstd: Int, addedRemnant: Int): PoolSide =
            if (addedAstd <= addedRemnant) PoolSide.ASTD else PoolSide.REMNANT

        /** 角色权重应用池配置倍率（仅作用于 carrier/phase 前缀角色）。 */
        fun weightedRoles(base: List<Pair<String, Float>>, config: BountyPoolConfig): List<Pair<String, Float>> =
            base.map { (role, weight) ->
                role to when {
                    role.startsWith("carrier") -> weight * config.carrierWeightMult
                    role.startsWith("phase") -> weight * config.phaseWeightMult
                    else -> weight
                }
            }

        /** 取池中部署点最高的全部变体（destroyerBestOf 生效时余晖驱逐舰只从最高档位抽取）。 */
        fun topFleetPointsPicks(pool: List<RemnantPick>): List<RemnantPick> {
            val max = pool.maxOf { it.fleetPoints }
            return pool.filter { it.fleetPoints == max }
        }

        /**
         * ASTD 角色到舰级的映射，余晖混编按同舰级池抽取，保证两侧尺寸分布一致。
         */
        val ROLE_TO_SIZE: Map<String, ShipAPI.HullSize> = mapOf(
            "combatSmall" to ShipAPI.HullSize.FRIGATE,
            "combatMedium" to ShipAPI.HullSize.DESTROYER,
            "carrierSmall" to ShipAPI.HullSize.DESTROYER,
            "phaseMedium" to ShipAPI.HullSize.DESTROYER,
            "combatLarge" to ShipAPI.HullSize.CRUISER,
            "combatCapital" to ShipAPI.HullSize.CAPITAL_SHIP,
            "carrierLarge" to ShipAPI.HullSize.CAPITAL_SHIP,
        )

        /**
         * 余晖混编池：按舰级分组的显式变体清单。
         * remnant 势力没有 doctrine 角色配置，无法走 pickShipAndAddToFleet，只能按变体直接实例化。
         * fleetPoints 为原版 ship_data.csv 的部署点口径，供最高档过滤使用。
         */
        val REMNANT_POOLS: Map<ShipAPI.HullSize, List<RemnantPick>> = mapOf(
            ShipAPI.HullSize.CAPITAL_SHIP to listOf(
                RemnantPick("radiant_Standard", 30f),
                RemnantPick("nova_Standard", 24f),
            ),
            ShipAPI.HullSize.CRUISER to listOf(
                RemnantPick("apex_Standard", 18f),
                RemnantPick("brilliant_Standard", 16f),
            ),
            ShipAPI.HullSize.DESTROYER to listOf(
                RemnantPick("fulgent_Assault", 12f),
                RemnantPick("fulgent_Support", 12f),
                RemnantPick("scintilla_Strike", 12f),
                RemnantPick("scintilla_Support", 12f),
            ),
            ShipAPI.HullSize.FRIGATE to listOf(
                RemnantPick("lumen_Standard", 8f),
                RemnantPick("glimmer_Assault", 8f),
                RemnantPick("glimmer_Support", 8f),
            ),
        )

        /**
         * 各赏金的难度档（StandardCores.planFleetCores 的 threatTier 入参）。
         * 僚舰档位由 StandardCores 分档映射表决定；T≥5 后僚舰封顶 B50/A50，
         * 更高层级的压迫感由编成规模与池配置承载。
         */
        val THREAT_TIERS: Map<String, Int> = mapOf(
            "astd_bounty_rogue_1" to 1,
            "astd_bounty_rogue_2" to 2,
            "astd_bounty_rogue_3" to 3,
            "astd_bounty_rogue_4" to 4,
            "astd_bounty_rogue_5" to 5,
            "astd_bounty_rogue_6" to 5,
            "astd_bounty_xc_002" to 5,
            "astd_bounty_zw_002" to 5,
            "astd_bounty_xc_001" to 5,
        )

        /** 未知赏金键按最低难度档处理。 */
        fun threatTierOf(key: String?): Int = THREAT_TIERS[key] ?: 1

        /**
         * 旗舰允许使用 O 档核心的赏金（唯一舰主控节点）。
         * 其余赏金的旗舰为量产舰，T5 难度档下旗舰核心封顶 A 档（O 仅作唯一舰设定标尺）。
         */
        val UNIQUE_FLAGSHIP_BOUNTIES: Set<String> = setOf(
            "astd_bounty_xc_001",
            "astd_bounty_xc_002",
            "astd_bounty_zw_002",
        )
    }
}

/** 余晖混编池单条目：变体 id 与部署点。 */
data class RemnantPick(val variantId: String, val fleetPoints: Float)

/** 混编池分边结果。 */
enum class PoolSide { ASTD, REMNANT }
