package cn.kasuminova.astd.internal.debug

import java.nio.file.Path

/**
 * Dev-only descriptor for the first in-game projectile VFX automation scenario.
 */
object ASTDInGameAutomationScenario {
    const val SCENARIO_ID: String = "arc_flare_aod7_basic"
    const val ARC_PRODUCTION_SCENARIO_ID: String = "arc_production_ships_vfx_tooltip"
    const val LENS_PHASE1_SCENARIO_ID: String = "lens_phase1_foundation"
    const val SHIP_ID: String = "astd_arc_flare"
    const val VARIANT_ID: String = "astd_arc_flare_Standard"
    const val WEAPON_ID: String = "astd_aod7"
    const val PROJECTILE_SPEC_ID: String = "astd_aod7_shot"
    const val VFX_PRESET_ID: String = "aod7_shot"

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

    /**
     * 阶段一引力透镜级实机场景开关：镜像 [isArcProductionEnabled]。
     * 仅当 automation 启用且场景属性显式为 [LENS_PHASE1_SCENARIO_ID] 时为 true。
     */
    fun isLensPhase1Enabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == LENS_PHASE1_SCENARIO_ID
    }

    fun outputDir(): Path {
        val explicit = System.getProperty(OUTPUT_DIR_PROPERTY)?.takeIf { it.isNotBlank() }
        if (explicit != null) return Path.of(explicit)

        val gameRoot = System.getProperty("user.dir")?.takeIf { it.isNotBlank() } ?: "."
        return Path.of(gameRoot, "ssoptimizer-automation-output")
    }
}
