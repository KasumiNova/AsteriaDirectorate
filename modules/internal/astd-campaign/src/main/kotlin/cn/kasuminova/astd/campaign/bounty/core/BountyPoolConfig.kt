package cn.kasuminova.astd.campaign.bounty.core

import com.fs.starfarer.api.combat.ShipAPI

/**
 * 单个赏金的随机混编池配置。
 *
 * MagicBounty 数据侧的 fleet_preset_ships 只负责最小 ASTD 编成，
 * 舰队上限内的随机混编（ASTD 与余晖 1:1 交替、舰级分布跟随 preset 构成）由代码侧完成。
 */
data class BountyPoolConfig(
    /** 驱逐舰抽取的 best-of-K 次数，1 表示不优选，大于 1 时保留 K 次抽取中部署点最高者。 */
    val destroyerBestOf: Int = 1,
) {

    companion object {
        val DEFAULT: BountyPoolConfig = BountyPoolConfig()

        val OVERRIDES: Map<String, BountyPoolConfig> = mapOf(
            // 星翼主控：远程火力编队，驱逐舰六次优选保留高部署点个体
            "astd_bounty_xc_002" to BountyPoolConfig(destroyerBestOf = 6),
        )

        fun forBountyKey(key: String?): BountyPoolConfig = OVERRIDES[key] ?: DEFAULT

        /** 混编池分边：随机部分 ASTD 与余晖 1:1 交替，ASTD 先出。 */
        fun pickPoolSide(addedAstd: Int, addedRemnant: Int): PoolSide =
            if (addedAstd <= addedRemnant) PoolSide.ASTD else PoolSide.REMNANT

        /**
         * ASTD 混编池：按舰级分组的正式 stock variant（contents/data/variants/）。
         * 显式变体池取代 doctrine 抽取：发布范围外舰体与唯一舰天然不入池。
         * 唯一舰（astd_xc_001/xc_002/zw_002）的装配仅作旗舰引用，不入随机池。
         */
        val ASTD_POOLS: Map<ShipAPI.HullSize, List<String>> = mapOf(
            ShipAPI.HullSize.CAPITAL_SHIP to listOf(
                "astd_xc_102_Standard",
                "astd_xc_102_Combat",
                "astd_zw_102_Fighter",
                "astd_zw_102_Bomber",
                "astd_zw_102_Hybrid",
            ),
            ShipAPI.HullSize.CRUISER to listOf(
                "astd_xc_101_Standard",
            ),
            ShipAPI.HullSize.DESTROYER to listOf(
                "astd_xc_103_Standard",
                "astd_zw_101_Standard",
                "astd_zw_103_Standard",
                "astd_zw_103_Strike",
            ),
            ShipAPI.HullSize.FRIGATE to listOf(
                "astd_lh_001_Standard",
                "astd_lh_001_Missile",
                "astd_lh_002_Standard",
                "astd_lh_002_Omega",
            ),
        )

        /**
         * 余晖混编池兜底清单：doctrine 动态发现（BountyFleetTunerImpl.discoverRemnantPools）
         * 在某舰级一无所获时启用，内容为原版 data/variants/remnant/ stock variant id。
         * 发现结果非空时以 doctrine 为准，本清单不参与混抽。
         */
        val REMNANT_POOLS: Map<ShipAPI.HullSize, List<String>> = mapOf(
            ShipAPI.HullSize.CAPITAL_SHIP to listOf(
                "radiant_Standard",
                "nova_Standard",
            ),
            ShipAPI.HullSize.CRUISER to listOf(
                "apex_Standard",
                "brilliant_Standard",
            ),
            ShipAPI.HullSize.DESTROYER to listOf(
                "fulgent_Assault",
                "fulgent_Support",
                "scintilla_Strike",
                "scintilla_Support",
            ),
            ShipAPI.HullSize.FRIGATE to listOf(
                "lumen_Standard",
                "glimmer_Assault",
                "glimmer_Support",
            ),
        )

        /**
         * 各赏金的难度档（StandardCores.planFleetCores 的 threatTier 入参）。
         * 仅三个唯一舰赏金消费该映射（rogue 系核心池见 [ROGUE_ESCORT_CORE_POOL]）；
         * T≥5 僚舰封顶 B50/A50。
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
         * 旗舰允许使用 astd O 档核心的赏金（唯一舰主控节点），其核心方案走 planFleetCores；
         * 其余赏金（rogue 系）旗舰固定原版 Alpha 核心，僚舰走 [ROGUE_ESCORT_CORE_POOL]。
         */
        val UNIQUE_FLAGSHIP_BOUNTIES: Set<String> = setOf(
            "astd_bounty_xc_001",
            "astd_bounty_xc_002",
            "astd_bounty_zw_002",
        )

        /** rogue 系赏金旗舰核心：固定原版 Alpha 核心。 */
        const val ROGUE_FLAGSHIP_CORE: String = "alpha_core"

        /**
         * rogue 系赏金僚舰核心池（commodity id + 权重）。
         * alpha/beta/gamma 三族各含原版核心、astd 制式核心、SMS 拟核，族内条目等权；
         * SMS 翘曲/结晶拟核低权重稀有出场。软联动：按 getCommoditySpec 存在性过滤，
         * 未安装 Ship Mastery System 时其条目自然消失，不构成 mod 硬依赖。
         */
        val ROGUE_ESCORT_CORE_POOL: List<Pair<String, Float>> = listOf(
            "alpha_core" to 3f,
            "astd_ai_core_a" to 3f,
            "sms_alpha_pseudocore" to 3f,
            "beta_core" to 3f,
            "astd_ai_core_b" to 3f,
            "sms_beta_pseudocore" to 3f,
            "gamma_core" to 3f,
            "astd_ai_core_g" to 3f,
            "sms_gamma_pseudocore" to 3f,
            "sms_fractured_gamma_core" to 3f,
            "sms_warped_pseudocore" to 2f,
            "sms_crystalline_pseudocore" to 2f,
        )

        /**
         * 核心军官等级表（原版 AICoreOfficerPluginImpl 各档对齐：alpha 7 / beta 5 / gamma 3）。
         * astd 制式核心等级见 StandardCores.Tier；SMS 核心等级不覆盖，由其模组插件默认。
         */
        val VANILLA_CORE_LEVELS: Map<String, Int> = mapOf(
            "alpha_core" to 7,
            "beta_core" to 5,
            "gamma_core" to 3,
        )
    }
}

/** 混编池分边结果。 */
enum class PoolSide { ASTD, REMNANT }
