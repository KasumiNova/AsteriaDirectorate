package cn.kasuminova.astd.combat.description

import cn.kasuminova.astd.api.difficulty.DifficultyTuning

internal object WeaponDescriptionParameters {
    private val resolvers: Map<String, (DifficultyTuning, Boolean) -> Map<String, String>> = linkedMapOf(
        "astd_starfall_echo" to WeaponArcParameters::echo,
        "astd_starfall_wing" to WeaponArcParameters::wing,
        "astd_gcp12" to { tuning, player -> WeaponLensParameters.collapse("astd_gcp12", tuning, player) },
        "astd_gcp8" to { tuning, player -> WeaponLensParameters.collapse("astd_gcp8", tuning, player) },
        "astd_gcp4" to { tuning, player -> WeaponLensParameters.collapse("astd_gcp4", tuning, player) },
        "astd_gcp2" to { tuning, player -> WeaponLensParameters.collapse("astd_gcp2", tuning, player) },
        "astd_charge_needle" to WeaponArcParameters::needle,
        "astd_heavy_charge_needle" to WeaponArcParameters::needle,
        "astd_charge_needle_fighter" to WeaponArcParameters::needle,
        "astd_electric_drive_accelerator" to WeaponArcParameters::accelerator,
        "astd_annihilation_vortex" to WeaponLensParameters::vortex,
        "astd_qiongjue_phase_railgun" to WeaponArcParameters::qiongjue,
        "astd_positron_shockwave" to WeaponArcParameters::positron,
        "astd_seven_stars" to WeaponArcParameters::sevenStars,
        "astd_gemini_dem_launcher" to WeaponArcParameters::gemini,
        "astd_gemini_dem_pod" to WeaponArcParameters::gemini,
        "astd_heavy_ion_pulse" to WeaponArcParameters::ion,
        "astd_stellar_mrm_launcher" to WeaponLensParameters::stellar,
        "astd_stellar_mrm_pod" to WeaponLensParameters::stellar,
        "astd_piercing_lance" to WeaponArcParameters::lance,
        "astd_cuifeng_torpedo" to WeaponArcParameters::cuifeng,
        "astd_cuifeng_launcher" to WeaponArcParameters::cuifeng,
        "astd_ice_shard_mirv" to WeaponLensParameters::ice,
        "astd_ice_shard_mirv_pod" to WeaponLensParameters::ice,
    )

    val ids: Set<String> = resolvers.keys

    fun resolve(id: String, tuning: DifficultyTuning, isPlayer: Boolean): Map<String, String> =
        requireNotNull(resolvers[id]) { "Unregistered weapon description: $id" }(tuning, isPlayer)
}
