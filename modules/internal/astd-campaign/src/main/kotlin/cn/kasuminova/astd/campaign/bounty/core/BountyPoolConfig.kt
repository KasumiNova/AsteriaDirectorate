package cn.kasuminova.astd.campaign.bounty.core

import com.fs.starfarer.api.combat.ShipAPI

data class BountyPoolConfig(
    val destroyerBestOf: Int = 1,
) {

    companion object {
        val DEFAULT: BountyPoolConfig = BountyPoolConfig()

        val OVERRIDES: Map<String, BountyPoolConfig> = mapOf(
            "astd_bounty_xc_002" to BountyPoolConfig(destroyerBestOf = 6),
        )

        fun forBountyKey(key: String?): BountyPoolConfig = OVERRIDES[key] ?: DEFAULT

        fun pickPoolSide(addedAstd: Int, addedRemnant: Int): PoolSide =
            if (addedAstd <= addedRemnant) PoolSide.ASTD else PoolSide.REMNANT

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

        val THREAT_TIERS: Map<String, Int> = mapOf(
            "astd_bounty_rogue_1" to 1,
            "astd_bounty_rogue_2" to 2,
            "astd_bounty_rogue_3" to 3,
            "astd_bounty_rogue_4" to 4,
            "astd_bounty_rogue_5" to 5,
            "astd_bounty_rogue_6" to 5,
            "astd_bounty_xc_002" to 5,
            "astd_bounty_zw_002" to 5,
            "astd_bounty_zw_001" to 5,
            "astd_bounty_xc_001" to 5,
        )

        fun threatTierOf(key: String?): Int = THREAT_TIERS[key] ?: 1

        val UNIQUE_FLAGSHIP_BOUNTIES: Set<String> = setOf(
            "astd_bounty_xc_001",
            "astd_bounty_xc_002",
            "astd_bounty_zw_001",
            "astd_bounty_zw_002",
        )

        const val ROGUE_FLAGSHIP_CORE: String = "alpha_core"

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

        /** 等级对齐原版 AICoreOfficerPluginImpl：alpha 7 / beta 5 / gamma 3。 */
        val VANILLA_CORE_LEVELS: Map<String, Int> = mapOf(
            "alpha_core" to 7,
            "beta_core" to 5,
            "gamma_core" to 3,
        )
    }
}

enum class PoolSide { ASTD, REMNANT }
