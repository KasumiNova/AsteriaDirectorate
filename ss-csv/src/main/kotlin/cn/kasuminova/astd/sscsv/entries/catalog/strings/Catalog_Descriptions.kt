package cn.kasuminova.astd.sscsv.entries.catalog.strings

import cn.kasuminova.astd.sscsv.entries.DescriptionEntry
import cn.kasuminova.astd.sscsv.i18n.SsI18n

/**
 * `data/strings/descriptions.csv`：武器/系统等“顶部描述文本”。
 *
 * 约定：
 * - 这些文本会直接进入 UI，尽量避免 ASCII `%`（推荐用全角 `％`）。
 */

private fun desc(id: String, key: String, fallback: String = ""): String =
    SsI18n.t("desc.$id.$key", fallback)

abstract class LocalizedDescription(
    final override val id: String,
    final override val type: String,
    private val notesId: String = id,
) : DescriptionEntry() {
    final override val text1: String = desc(id, "text1")
    final override val text2: String = desc(id, "text2")
    final override val text3: String = desc(id, "text3")
    final override val text4: String = desc(id, "text4")
    final override val text5: String = desc(id, "text5")
    final override val notes: String = desc(notesId, "notes")
}

object Desc_astd_arc_flare : LocalizedDescription("astd_arc_flare", "SHIP")
object Desc_astd_arc_flash : LocalizedDescription("astd_arc_flash", "SHIP")
object Desc_astd_arc_jet : LocalizedDescription("astd_arc_jet", "SHIP")
object Desc_astd_aurora_grid : LocalizedDescription("astd_aurora_grid", "SHIP")
object Desc_astd_dark_tide_nebula : LocalizedDescription("astd_dark_tide_nebula", "SHIP")
object Desc_astd_diffraction : LocalizedDescription("astd_diffraction", "SHIP")
object Desc_astd_echo_shimmer : LocalizedDescription("astd_echo_shimmer", "SHIP")
object Desc_astd_flickering_phantom : LocalizedDescription("astd_flickering_phantom", "SHIP")
object Desc_astd_gravitational_lens : LocalizedDescription("astd_gravitational_lens", "SHIP")
object Desc_astd_magnetic_storm_zigzag : LocalizedDescription("astd_magnetic_storm_zigzag", "SHIP")
object Desc_astd_magnetosphere_disturbance : LocalizedDescription("astd_magnetosphere_disturbance", "SHIP")
object Desc_astd_nebula_echo : LocalizedDescription("astd_nebula_echo", "SHIP")
object Desc_astd_negentropy_edge : LocalizedDescription("astd_negentropy_edge", "SHIP")
object Desc_astd_plasma_arch : LocalizedDescription("astd_plasma_arch", "SHIP")
object Desc_astd_radiation_belt : LocalizedDescription("astd_radiation_belt", "SHIP")
object Desc_astd_apex_logic : LocalizedDescription("astd_apex_logic", "SHIP")

object Desc_astd_arc_flare_overdrive_crewed : LocalizedDescription("astd_arc_flare_overdrive_crewed", "SHIP_SYSTEM")
object Desc_astd_arc_flare_overdrive_automated : LocalizedDescription("astd_arc_flare_overdrive_automated", "SHIP_SYSTEM")
object Desc_astd_arc_flare_overdrive : LocalizedDescription("astd_arc_flare_overdrive", "SHIP_SYSTEM")
object Desc_astd_arc_shared_flux_network : LocalizedDescription("astd_arc_shared_flux_network", "SHIP_SYSTEM")
object Desc_astd_plasma_armor_shield_boost : LocalizedDescription("astd_plasma_armor_shield_boost", "SHIP_SYSTEM")
object Desc_astd_limit_temporal_thruster : LocalizedDescription("astd_limit_temporal_thruster", "SHIP_SYSTEM")
object Desc_astd_echo_fixation_crewed : LocalizedDescription("astd_echo_fixation_crewed", "SHIP_SYSTEM")
object Desc_astd_echo_fixation_automated : LocalizedDescription("astd_echo_fixation_automated", "SHIP_SYSTEM")

object Desc_astd_gcp12 : LocalizedDescription("astd_gcp12", "WEAPON")
object Desc_astd_gcp8 : LocalizedDescription("astd_gcp8", "WEAPON", notesId = "astd_gcp12")
object Desc_astd_gcp4 : LocalizedDescription("astd_gcp4", "WEAPON", notesId = "astd_gcp12")
object Desc_astd_gcp2 : LocalizedDescription("astd_gcp2", "WEAPON", notesId = "astd_gcp12")
object Desc_astd_psi_omega : LocalizedDescription("astd_psi_omega", "WEAPON")
object Desc_astd_charge_needle : LocalizedDescription("astd_charge_needle", "WEAPON")
object Desc_astd_heavy_charge_needle : LocalizedDescription("astd_heavy_charge_needle", "WEAPON", notesId = "astd_charge_needle")
object Desc_astd_electric_drive_accelerator : LocalizedDescription("astd_electric_drive_accelerator", "WEAPON")
object Desc_astd_annihilation_vortex : LocalizedDescription("astd_annihilation_vortex", "WEAPON")
object Desc_astd_qiongjue_phase_railgun : LocalizedDescription("astd_qiongjue_phase_railgun", "WEAPON")
object Desc_astd_positron_shockwave : LocalizedDescription("astd_positron_shockwave", "WEAPON")
object Desc_astd_seven_stars : LocalizedDescription("astd_seven_stars", "WEAPON")
object Desc_astd_gemini_dem_launcher : LocalizedDescription("astd_gemini_dem_launcher", "WEAPON")
object Desc_astd_gemini_dem_pod : LocalizedDescription("astd_gemini_dem_pod", "WEAPON", notesId = "astd_gemini_dem_launcher")
object Desc_astd_heavy_ion_pulse : LocalizedDescription("astd_heavy_ion_pulse", "WEAPON")
object Desc_astd_stellar_mrm_launcher : LocalizedDescription("astd_stellar_mrm_launcher", "WEAPON")
object Desc_astd_stellar_mrm_pod : LocalizedDescription("astd_stellar_mrm_pod", "WEAPON", notesId = "astd_stellar_mrm_launcher")
