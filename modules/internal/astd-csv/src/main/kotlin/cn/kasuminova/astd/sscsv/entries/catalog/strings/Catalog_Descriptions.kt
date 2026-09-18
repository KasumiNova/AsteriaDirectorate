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

object Desc_astd_xc_001 : LocalizedDescription("astd_xc_001", "SHIP")
object Desc_astd_xc_104 : LocalizedDescription("astd_xc_104", "SHIP")
object Desc_astd_xc_102 : LocalizedDescription("astd_xc_102", "SHIP")
object Desc_astd_zl_101 : LocalizedDescription("astd_zl_101", "SHIP")
object Desc_astd_zw_002 : LocalizedDescription("astd_zw_002", "SHIP")
object Desc_astd_zw_103 : LocalizedDescription("astd_zw_103", "SHIP")
object Desc_astd_zl_103 : LocalizedDescription("astd_zl_103", "SHIP")
object Desc_astd_zw_001 : LocalizedDescription("astd_zw_001", "SHIP")
object Desc_astd_zl_102 : LocalizedDescription("astd_zl_102", "SHIP")
object Desc_astd_zw_102 : LocalizedDescription("astd_zw_102", "SHIP")
object Desc_astd_zw_101 : LocalizedDescription("astd_zw_101", "SHIP")
object Desc_astd_xc_002 : LocalizedDescription("astd_xc_002", "SHIP")
object Desc_astd_xc_101 : LocalizedDescription("astd_xc_101", "SHIP")
object Desc_astd_xc_103 : LocalizedDescription("astd_xc_103", "SHIP")
object Desc_astd_zl_001 : LocalizedDescription("astd_zl_001", "SHIP")

object Desc_astd_xc_001_overdrive_crewed : LocalizedDescription("astd_xc_001_overdrive_crewed", "SHIP_SYSTEM")
object Desc_astd_xc_001_overdrive_automated : LocalizedDescription("astd_xc_001_overdrive_automated", "SHIP_SYSTEM")
object Desc_astd_xc_001_overdrive : LocalizedDescription("astd_xc_001_overdrive", "SHIP_SYSTEM")
object Desc_astd_arc_shared_flux_network : LocalizedDescription("astd_arc_shared_flux_network", "SHIP_SYSTEM")
object Desc_astd_plasma_armor_shield_boost : LocalizedDescription("astd_plasma_armor_shield_boost", "SHIP_SYSTEM")
object Desc_astd_limit_temporal_thruster : LocalizedDescription("astd_limit_temporal_thruster", "SHIP_SYSTEM")
object Desc_astd_echo_fixation_crewed : LocalizedDescription("astd_echo_fixation_crewed", "SHIP_SYSTEM")
object Desc_astd_echo_fixation_automated : LocalizedDescription("astd_echo_fixation_automated", "SHIP_SYSTEM")
object Desc_astd_gravity_phase : LocalizedDescription("astd_gravity_phase", "SHIP_SYSTEM")
object Desc_astd_fighter_grav_link : LocalizedDescription("astd_fighter_grav_link", "SHIP_SYSTEM")

object Desc_astd_lh_001 : LocalizedDescription("astd_lh_001", "SHIP")
object Desc_astd_lh_002 : LocalizedDescription("astd_lh_002", "SHIP")
object Desc_astd_lh_001_burst_flow : LocalizedDescription("astd_lh_001_burst_flow", "SHIP_SYSTEM")
object Desc_astd_lh_002_vision_shift : LocalizedDescription("astd_lh_002_vision_shift", "SHIP_SYSTEM")
object Desc_astd_lh_stardust_launcher_arc : LocalizedDescription("astd_lh_stardust_launcher_arc", "WEAPON")
object Desc_astd_lh_stardust_launcher_lens : LocalizedDescription("astd_lh_stardust_launcher_lens", "WEAPON", notesId = "astd_lh_stardust_launcher_arc")

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
object Desc_astd_piercing_lance : LocalizedDescription("astd_piercing_lance", "WEAPON")

object Desc_astd_executor_core : LocalizedDescription("astd_executor_core", "RESOURCE")
object Desc_astd_executor_core_combat : LocalizedDescription("astd_executor_core_combat", "RESOURCE")
object Desc_astd_executor_core_admin : LocalizedDescription("astd_executor_core_admin", "RESOURCE")

object Desc_astd_ai_core_g : LocalizedDescription("astd_ai_core_g", "RESOURCE")
object Desc_astd_ai_core_b : LocalizedDescription("astd_ai_core_b", "RESOURCE")
object Desc_astd_ai_core_a : LocalizedDescription("astd_ai_core_a", "RESOURCE")
object Desc_astd_ai_core_o : LocalizedDescription("astd_ai_core_o", "RESOURCE")

object Desc_astd_reserved_station : LocalizedDescription("astd_reserved_station", "CUSTOM")
