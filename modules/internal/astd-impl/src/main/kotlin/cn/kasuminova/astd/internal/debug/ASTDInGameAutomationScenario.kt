package cn.kasuminova.astd.internal.debug

import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isArcProductionEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isAvEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isChargeNeedleEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isEdaEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isGdEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isGravRiftScenarioEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isHipEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isPlEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isPsEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isQjEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isSmEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isSsEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isTrailPauseProbeEnabled
import java.nio.file.Path

object ASTDInGameAutomationScenario {
    const val SCENARIO_ID: String = "xc_001_starfall_echo_basic"
    const val ARC_PRODUCTION_SCENARIO_ID: String = "arc_production_ships_vfx_tooltip"
    const val CHARGE_NEEDLE_SCENARIO_ID: String = "charge_needle_basic"
    const val CHARGE_NEEDLE_WEAPON_ID: String = "astd_charge_needle"
    const val CHARGE_NEEDLE_HEAVY_WEAPON_ID: String = "astd_heavy_charge_needle"
    const val CHARGE_NEEDLE_PROJECTILE_SPEC_ID: String = "astd_charge_needle_shot"
    const val CHARGE_NEEDLE_HEAVY_PROJECTILE_SPEC_ID: String = "astd_heavy_charge_needle_shot"
    const val EDA_SCENARIO_ID: String = "electric_drive_basic"
    const val EDA_WEAPON_ID: String = "astd_electric_drive_accelerator"
    const val EDA_PROJECTILE_SPEC_ID: String = "astd_electric_drive_accelerator_shot"
    const val AV_SCENARIO_ID: String = "annihilation_vortex_basic"
    const val AV_WEAPON_ID: String = "astd_annihilation_vortex"
    const val QJ_SCENARIO_ID: String = "qiongjue_railgun_basic"
    const val QJ_WEAPON_ID: String = "astd_qiongjue_phase_railgun"
    const val QJ_PROJECTILE_SPEC_ID: String = "astd_qiongjue_phase_railgun_shot"
    const val PS_SCENARIO_ID: String = "positron_shockwave_basic"
    const val PS_WEAPON_ID: String = "astd_positron_shockwave"
    const val PS_PROJECTILE_SPEC_ID: String = "astd_positron_shockwave_shot"
    const val SS_SCENARIO_ID: String = "seven_stars_basic"
    const val SS_WEAPON_ID: String = "astd_seven_stars"
    const val GD_SCENARIO_ID: String = "gemini_dem_basic"
    const val GD_LAUNCHER_WEAPON_ID: String = "astd_gemini_dem_launcher"
    const val GD_POD_WEAPON_ID: String = "astd_gemini_dem_pod"
    const val GD_DUMMY_PROJECTILE_SPEC_ID: String = "astd_gemini_dem_dummy"
    const val HIP_SCENARIO_ID: String = "heavy_ion_pulse_basic"
    const val HIP_WEAPON_ID: String = "astd_heavy_ion_pulse"
    const val HIP_PROJECTILE_SPEC_ID: String = "astd_heavy_ion_pulse_shot"
    const val SM_SCENARIO_ID: String = "stellar_mrm_basic"
    const val SM_LAUNCHER_WEAPON_ID: String = "astd_stellar_mrm_launcher"
    const val SM_POD_WEAPON_ID: String = "astd_stellar_mrm_pod"
    const val SM_LAUNCHER_PROJECTILE_SPEC_ID: String = "astd_stellar_mrm_launcher_shot"
    const val SM_POD_PROJECTILE_SPEC_ID: String = "astd_stellar_mrm_pod_shot"
    const val CUIFENG_SCENARIO_ID: String = "cuifeng_torpedo_basic"
    const val CUIFENG_TORPEDO_WEAPON_ID: String = "astd_cuifeng_torpedo"
    const val CUIFENG_LAUNCHER_WEAPON_ID: String = "astd_cuifeng_launcher"
    const val CUIFENG_PROJECTILE_SPEC_ID: String = "astd_cuifeng_torpedo_shot"
    const val ICE_SHARD_SCENARIO_ID: String = "ice_shard_mirv_basic"
    const val ICE_SHARD_MIRV_WEAPON_ID: String = "astd_ice_shard_mirv"
    const val ICE_SHARD_POD_WEAPON_ID: String = "astd_ice_shard_mirv_pod"
    const val ICE_SHARD_MIRV_PROJECTILE_SPEC_ID: String = "astd_ice_shard_mirv_shot"
    const val PL_SCENARIO_ID: String = "piercing_lance_basic"
    const val PL_WEAPON_ID: String = "astd_piercing_lance"
    const val PL_PROJECTILE_SPEC_ID: String = "astd_piercing_lance_shot"
    const val TPP_SCENARIO_ID: String = "trail_pause_probe"
    const val GRG_SCENARIO_ID: String = "lens_grav_rift_zw103"
    const val GRG_SYSTEM_ID: String = "astd_grav_rift_generator"
    const val GRG_HULL_ID: String = "astd_zw_103"
    const val GRG_VARIANT_ID: String = "astd_zw_103_Standard"
    const val FGL_SCENARIO_ID: String = "lens_fighter_grav_link"
    const val FGL_SYSTEM_ID: String = "astd_fighter_grav_link"
    const val FGL_HULL_ID: String = "astd_zw_102"
    const val FGL_VARIANT_ID: String = "astd_zw_102_Hybrid"
    const val GS_SCENARIO_ID: String = "lens_grav_storm_zw002"
    const val GS_SYSTEM_ID: String = "astd_grav_storm"
    const val GS_HULL_ID: String = "astd_zw_002"
    const val GS_VARIANT_ID: String = "astd_zw_002_Standard"
    const val GSR_SCENARIO_ID: String = "lens_grav_replicator_zw101"
    const val GSR_SYSTEM_ID: String = "astd_grav_replicator"
    const val GSR_HULL_ID: String = "astd_zw_101"
    const val GSR_VARIANT_ID: String = "astd_zw_101_Standard"
    const val PF_SCENARIO_ID: String = "lens_phase_flank_zw101"
    const val PF_HULL_ID: String = "astd_zw_101"
    const val PF_VARIANT_ID: String = "astd_zw_101_Standard"
    const val PF_ENEMY_VARIANT_ID: String = "dominator_Assault"
    const val PF_ENEMY_HULL_ID: String = "dominator"

    const val PF2_SCENARIO_ID: String = "lens_phase_flank_zw002"
    const val PF2_HULL_ID: String = "astd_zw_002"
    const val PF2_VARIANT_ID: String = "astd_zw_002_Standard"
    const val PF3_SCENARIO_ID: String = "lens_phase_flank_zw103"
    const val PF3_HULL_ID: String = "astd_zw_103"
    const val PF3_VARIANT_ID: String = "astd_zw_103_Standard"
    const val PFC_SCENARIO_ID: String = "lens_phase_flank_zw101_hh"
    const val PFC_ENEMY_VARIANT_ID: String = "hammerhead_Balanced"
    const val PFC_ENEMY_HULL_ID: String = "hammerhead"

    const val PF4_SCENARIO_ID: String = "lens_phase_flank_zw103_brilliant"
    const val PF4_ENEMY_VARIANT_ID: String = "brilliant_AstdTorpedo"
    const val PF4_ENEMY_HULL_ID: String = "brilliant"

    const val PFB_SCENARIO_ID: String = "lens_phase_friendly_beam"
    const val PFB_HULL_ID: String = "astd_zw_101"
    const val PFB_VARIANT_ID: String = "astd_zw_101_Standard"
    const val PFB_ENEMY_VARIANT_ID: String = "dominator_Assault"
    const val PFB_ENEMY_HULL_ID: String = "dominator"
    const val PFB_FRIENDLY_HULL_ID: String = "wolf"
    const val PFB_FRIENDLY_VARIANT_ID: String = "wolf_Starting"

    const val PHASE_AI_LONG_RANGE_ID: String = "phase_ai_long_range_engagement"
    const val PHASE_AI_DEM_EVASION_ID: String = "phase_ai_dem_evasion"
    const val PHASE_AI_BURST_PROTECTION_ID: String = "phase_ai_burst_protection"
    const val PHASE_AI_FLANK_DAMAGE_ID: String = "phase_ai_flank_damage"
    const val PHASE_AI_RECOVERY_ID: String = "phase_ai_recovery"
    const val PHASE_AI_TARGET_TURN_ID: String = "phase_ai_target_turn"
    const val PHASE_AI_MULTI_ENEMY_ID: String = "phase_ai_multi_enemy"
    const val PHASE_AI_SEED_1_ID: String = "phase_ai_seed_1"
    const val PHASE_AI_SEED_2_ID: String = "phase_ai_seed_2"
    const val PHASE_AI_SEED_3_ID: String = "phase_ai_seed_3"
    const val PHASE_AI_SEED_4_ID: String = "phase_ai_seed_4"
    const val PHASE_AI_SEED_5_ID: String = "phase_ai_seed_5"
    const val PHASE_AI_SEED_6_ID: String = "phase_ai_seed_6"

    const val SHIP_ID: String = "astd_xc_001"
    const val VARIANT_ID: String = "astd_xc_001_Standard"
    const val WEAPON_ID: String = "astd_starfall_echo"
    const val PROJECTILE_SPEC_ID: String = "astd_starfall_echo_shot"
    const val XC002_SCENARIO_ID: String = "xc_002_rift_shift_basic"
    const val XC002_HULL_ID: String = "astd_xc_002"
    const val XC002_VARIANT_ID: String = "astd_xc_002_Standard"
    const val XC002_SYSTEM_ID: String = "astd_rift_shift"
    const val XC002_WEAPON_ID: String = "astd_starfall_wing"

    // SSOptimizer helper 与 tools/verify_ingame_vfx_automation.py 依赖该值，禁止单独改名
    const val VFX_PRESET_ID: String = "starfall_echo_shot"

    const val ENABLED_PROPERTY: String = "ssoptimizer.automation.enabled"
    const val SCENARIO_PROPERTY: String = "ssoptimizer.automation.scenario"
    const val OUTPUT_DIR_PROPERTY: String = "ssoptimizer.automation.outputDir"

    const val TELEMETRY_FILE: String = "astd-ingame-automation-telemetry.json"
    const val ASTD_TELEMETRY_FILE: String = "astd-ingame-automation-astd-telemetry.json"
    const val DIAGNOSTICS_FILE: String = "astd-ingame-automation-diagnostics.json"
    const val SCREENSHOT_ATTEMPT_FILE: String = "astd-ingame-automation-screenshot-attempt.txt"

    fun isEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == SCENARIO_ID
    }

    fun isArcProductionEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == ARC_PRODUCTION_SCENARIO_ID
    }

    fun isChargeNeedleEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == CHARGE_NEEDLE_SCENARIO_ID
    }

    fun isEdaEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == EDA_SCENARIO_ID
    }

    fun isAvEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == AV_SCENARIO_ID
    }

    fun isQjEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == QJ_SCENARIO_ID
    }

    fun isPsEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == PS_SCENARIO_ID
    }

    fun isSsEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == SS_SCENARIO_ID
    }

    fun isGdEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == GD_SCENARIO_ID
    }

    fun isHipEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == HIP_SCENARIO_ID
    }

    fun isSmEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == SM_SCENARIO_ID
    }

    fun isCuifengEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == CUIFENG_SCENARIO_ID
    }

    fun isIceShardMirvEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == ICE_SHARD_SCENARIO_ID
    }

    fun isPlEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == PL_SCENARIO_ID
    }

    fun isTrailPauseProbeEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == TPP_SCENARIO_ID
    }

    fun isGravRiftScenarioEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == GRG_SCENARIO_ID
    }

    fun isFighterGravLinkScenarioEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == FGL_SCENARIO_ID
    }

    fun isGravStormScenarioEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == GS_SCENARIO_ID
    }

    fun isGravReplicatorScenarioEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == GSR_SCENARIO_ID
    }

    fun isPhaseFlankScenarioEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == PF_SCENARIO_ID
    }

    fun isXc002Enabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == XC002_SCENARIO_ID
    }

    fun isPhaseFlankZw002Enabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == PF2_SCENARIO_ID
    }

    fun isPhaseFlankZw103Enabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == PF3_SCENARIO_ID
    }

    fun isPhaseFlankControlEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == PFC_SCENARIO_ID
    }

    fun isPhaseFlankBrilliantEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == PF4_SCENARIO_ID
    }

    fun isPhaseFriendlyBeamEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == PFB_SCENARIO_ID
    }

    private fun isScenarioEnabled(id: String): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == id
    }

    fun isPhaseAiLongRangeEnabled(): Boolean = isScenarioEnabled(PHASE_AI_LONG_RANGE_ID)
    fun isPhaseAiDemEvasionEnabled(): Boolean = isScenarioEnabled(PHASE_AI_DEM_EVASION_ID)
    fun isPhaseAiBurstProtectionEnabled(): Boolean = isScenarioEnabled(PHASE_AI_BURST_PROTECTION_ID)
    fun isPhaseAiFlankDamageEnabled(): Boolean = isScenarioEnabled(PHASE_AI_FLANK_DAMAGE_ID)
    fun isPhaseAiRecoveryEnabled(): Boolean = isScenarioEnabled(PHASE_AI_RECOVERY_ID)
    fun isPhaseAiTargetTurnEnabled(): Boolean = isScenarioEnabled(PHASE_AI_TARGET_TURN_ID)
    fun isPhaseAiMultiEnemyEnabled(): Boolean = isScenarioEnabled(PHASE_AI_MULTI_ENEMY_ID)
    fun isPhaseAiSeed1Enabled(): Boolean = isScenarioEnabled(PHASE_AI_SEED_1_ID)
    fun isPhaseAiSeed2Enabled(): Boolean = isScenarioEnabled(PHASE_AI_SEED_2_ID)
    fun isPhaseAiSeed3Enabled(): Boolean = isScenarioEnabled(PHASE_AI_SEED_3_ID)
    fun isPhaseAiSeed4Enabled(): Boolean = isScenarioEnabled(PHASE_AI_SEED_4_ID)
    fun isPhaseAiSeed5Enabled(): Boolean = isScenarioEnabled(PHASE_AI_SEED_5_ID)
    fun isPhaseAiSeed6Enabled(): Boolean = isScenarioEnabled(PHASE_AI_SEED_6_ID)

    fun outputDir(): Path {
        val explicit = System.getProperty(OUTPUT_DIR_PROPERTY)?.takeIf { it.isNotBlank() }
        if (explicit != null) return Path.of(explicit)

        val gameRoot = System.getProperty("user.dir")?.takeIf { it.isNotBlank() } ?: "."
        return Path.of(gameRoot, "ssoptimizer-automation-output")
    }
}
