package cn.kasuminova.astd.combat.hullmods.arc

internal object ASTDArcProductionShipIds {
    const val HULL_ARC_JET = "astd_arc_jet"
    const val HULL_PLASMA_ARCH = "astd_plasma_arch"
    const val HULL_RADIATION_BELT = "astd_radiation_belt"

    const val HULLMOD_ARC_ADVANCED_FIRE_CONTROL = "astd_arc_advanced_fire_control"
    const val HULLMOD_ARC_SHARED_TACTICAL_NETWORK = "astd_arc_shared_tactical_network"
    const val HULLMOD_PLASMA_ARMOR_SHIELD = "astd_plasma_armor_shield"
    const val HULLMOD_IONIZED_RECOIL_ACCUMULATOR = "astd_ionized_recoil_accumulator"
    const val HULLMOD_ARC_ADVANCED_TARGETING_SYSTEM = "astd_arc_advanced_targeting_system"
    const val HULLMOD_DISTRIBUTED_PURSUIT_NETWORK = "astd_distributed_pursuit_network"

    const val SYSTEM_ARC_SHARED_FLUX_NETWORK = "astd_arc_shared_flux_network"
    const val SYSTEM_PLASMA_ARMOR_SHIELD_BOOST = "astd_plasma_armor_shield_boost"
    const val SYSTEM_LIMIT_TEMPORAL_THRUSTER = "astd_limit_temporal_thruster"

    const val STAT_ARC_ADVANCED_FIRE_CONTROL = "astd_arc_advanced_fire_control"
    const val STAT_ARC_SHARED_TACTICAL_NETWORK_SELF = "astd_arc_shared_tactical_network_self"
    const val STAT_ARC_SHARED_TACTICAL_NETWORK_AURA = "astd_arc_shared_tactical_network_aura"
    const val STAT_ARC_SHARED_FLUX_NETWORK = "astd_arc_shared_flux_network"
    const val STAT_PLASMA_ARMOR_SHIELD = "astd_plasma_armor_shield"
    const val STAT_PLASMA_ARMOR_SHIELD_BOOST = "astd_plasma_armor_shield_boost"
    const val STAT_IONIZED_RECOIL_ACCUMULATOR = "astd_ionized_recoil_accumulator"
    const val STAT_ARC_ADVANCED_TARGETING_SYSTEM = "astd_arc_advanced_targeting_system"
    const val STAT_DISTRIBUTED_PURSUIT_NETWORK = "astd_distributed_pursuit_network"
    const val STAT_LIMIT_TEMPORAL_THRUSTER = "astd_limit_temporal_thruster"

    const val DATA_ARC_SHARED_FLUX_TARGETS = "astd_arc_shared_flux_network_targets"
    const val DATA_PLASMA_SHIELD_GRID_STATE = "astd_plasma_shield_grid_state"
    const val DATA_PLASMA_SHIELD_BOOST_LEVEL = "astd_plasma_shield_boost_level"
    const val DATA_DISTRIBUTED_PURSUIT_TARGETS = "astd_distributed_pursuit_network_targets"

    val UNIQUE_HULLMOD_IDS = setOf(
        HULLMOD_ARC_ADVANCED_FIRE_CONTROL,
        HULLMOD_ARC_SHARED_TACTICAL_NETWORK,
        HULLMOD_PLASMA_ARMOR_SHIELD,
        HULLMOD_IONIZED_RECOIL_ACCUMULATOR,
        HULLMOD_ARC_ADVANCED_TARGETING_SYSTEM,
        HULLMOD_DISTRIBUTED_PURSUIT_NETWORK,
    )
}
