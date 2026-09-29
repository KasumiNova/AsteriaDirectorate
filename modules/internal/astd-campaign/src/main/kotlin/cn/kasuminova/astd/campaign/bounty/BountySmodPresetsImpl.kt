package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.bounty.core.BountySmodPresets

class BountySmodPresetsImpl : BountySmodPresets {

    override fun prioritiesFor(hullId: String, phase: Boolean, carrier: Boolean, capital: Boolean): List<String> =
        HULL_OVERRIDES[hullId] ?: when {
            phase -> PHASE_PRIORITY
            carrier -> CARRIER_PRIORITY
            capital -> CAPITAL_PRIORITY
            else -> GENERIC_PRIORITY
        }

    companion object {
        // 候选均为原版船插 id（本模组可安装船插均无 SMod 效果，不参与内插）。
        // 各清单内部不含互斥组合，按优先级从高到低排列；维护时直接增删条目即可。

        val GENERIC_PRIORITY: List<String> = listOf(
            "targetingunit",
            "hardenedshieldemitter",
            "fluxdistributor",
            "heavyarmor",
            "armoredweapons",
            "reinforcedhull",
        )

        val CAPITAL_PRIORITY: List<String> = listOf(
            "heavyarmor",
            "targetingunit",
            "hardenedshieldemitter",
            "armoredweapons",
            "fluxdistributor",
            "reinforcedhull",
        )

        val PHASE_PRIORITY: List<String> = listOf(
            "ex_phase_coils",
            "phase_anchor",
            "fluxdistributor",
            "fluxcoil",
            "stabilizedshieldemitter",
            "reinforcedhull",
        )

        val CARRIER_PRIORITY: List<String> = listOf(
            "expanded_deck_crew",
            "recovery_shuttles",
            "targetingunit",
            "hardenedshieldemitter",
            "fluxdistributor",
            "reinforcedhull",
        )

        // 赏金旗舰专属清单：与其导入装配（contents/data/variants/ 正式 stock variant）已装配的普通船插错开，
        // 保证 SMod 槽位能被新候选占满。
        val HULL_OVERRIDES: Map<String, List<String>> = mapOf(
            "astd_xc_001" to listOf("heavyarmor", "reinforcedhull", "blast_doors", "fluxcoil"),
            "astd_xc_002" to listOf("armoredweapons", "reinforcedhull", "heavyarmor", "turretgyros"),
            "astd_zw_002" to listOf("adaptive_coils", "stabilizedshieldemitter", "reinforcedhull", "hardenedshieldemitter"),
        )
    }
}
