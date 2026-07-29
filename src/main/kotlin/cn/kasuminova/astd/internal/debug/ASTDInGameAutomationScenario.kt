package cn.kasuminova.astd.internal.debug

import java.nio.file.Path

/**
 * Dev-only descriptor for the first in-game projectile VFX automation scenario.
 */
object ASTDInGameAutomationScenario {
    const val SCENARIO_ID: String = "arc_flare_aod7_basic"
    const val ARC_PRODUCTION_SCENARIO_ID: String = "arc_production_ships_vfx_tooltip"
    const val LENS_PHASE1_SCENARIO_ID: String = "lens_phase1_foundation"
    const val LENS_PHASE2_SCENARIO_ID: String = "lens_phase2_mechanisms"
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
    const val SHIP_ID: String = "astd_arc_flare"
    const val VARIANT_ID: String = "astd_arc_flare_Standard"
    const val WEAPON_ID: String = "astd_aod7"
    const val PROJECTILE_SPEC_ID: String = "astd_aod7_shot"
    // SSOptimizer 遥测契约标签（其 helper/verifier 硬编码的字面值）；运行期 preset 已随旧管线删除，此处仅作场景描述符。
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

    /**
     * 阶段二引力透镜级实机场景开关：镜像 [isLensPhase1Enabled]。
     * 仅当 automation 启用且场景属性显式为 [LENS_PHASE2_SCENARIO_ID] 时为 true。
     * 阶段二验证机制证据（定影场 / 认知撕裂 / 残影 / 深水标记 / 插件挂载）+ shader 提交计数。
     */
    fun isLensPhase2Enabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == LENS_PHASE2_SCENARIO_ID
    }

    /**
     * 电荷针刺实机场景开关：镜像 [isLensPhase1Enabled]。
     * 验证淤积叠层（层数/维持乘区/安全闸/衰减）、船体泄放电弧计数、弹匣节奏、双槽弹体 VFX 与 HUD 反馈。
     */
    fun isChargeNeedleEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == CHARGE_NEEDLE_SCENARIO_ID
    }

    /**
     * 电驱加速炮实机场景开关：镜像 [isChargeNeedleEnabled]。
     * 验证每触发 8 弹（LINKED 双管 × burst 4）、净空加速射程随辐能伸缩、
     * devMode HUD 状态条目、不稳定装药追加伤害浮字与敌版三档。
     */
    fun isEdaEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == EDA_SCENARIO_ID
    }

    /**
     * 湮灭涡旋实机场景开关：镜像 [isEdaEnabled]。
     * 验证双槽位装配（LARGE ENERGY / LARGE SYNERGY）、涡旋牵引/吸收遥测、停火坍缩（含命中计数与伤害数字通道）、
     * 空池保底 500、2s/9s 爆发循环、Hidden 束渲染（beam 宽归零）、HUD/浮字反馈、敌版三档（installScaleForTests）
     * 与宿主死亡不坍缩 + 池自回收 INFO。
     */
    fun isAvEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == AV_SCENARIO_ID
    }

    /**
     * 穷距相位轨道炮实机场景开关：镜像 [isAvEnabled]。
     * 验证持续演算叠层（同目标 +1/异目标折算/目标失效不折算/3s 窗口衰减）、同舰双穷距复合键隔离、
     * 伤害乘区（满层 975）、射速 spike（setRemainingCooldownTo 周期起点扣减，满层间隔约 1.23s）、
     * HUD 状态条目与浮字、命中小号锥面特效计数、敌版三档（installScaleForTests）与叠层期帧率。
     */
    fun isQjEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == QJ_SCENARIO_ID
    }

    /**
     * 正电子冲击波实机场景开关：镜像 [isQjEnabled]。
     * 验证无触碰体积（穿舰不爆）、满射程无条件自爆（引爆距离 ≈600）、舰船蹭波及但不触发近炸、
     * 近炸引爆成片清除导弹群、devMode 引爆计数浮字、锥面 VFX 计数与 PD hint 装配。
     */
    fun isPsEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == PS_SCENARIO_ID
    }

    fun outputDir(): Path {
        val explicit = System.getProperty(OUTPUT_DIR_PROPERTY)?.takeIf { it.isNotBlank() }
        if (explicit != null) return Path.of(explicit)

        val gameRoot = System.getProperty("user.dir")?.takeIf { it.isNotBlank() } ?: "."
        return Path.of(gameRoot, "ssoptimizer-automation-output")
    }
}
