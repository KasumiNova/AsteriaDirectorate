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
    const val PL_SCENARIO_ID: String = "piercing_lance_basic"
    const val PL_WEAPON_ID: String = "astd_piercing_lance"
    const val PL_PROJECTILE_SPEC_ID: String = "astd_piercing_lance_shot"
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

    /**
     * “七星”折跃发射器实机场景开关：镜像 [isPsEnabled]。
     * 验证装配（大能量槽/800 射程/PD hint）、未击杀断链消散（增压导弹存活 → 无续跳无终结）、
     * 折跃链连跳（chainJumpsMax ∈ [3,7]、十字闪光/折跃电弧计数、穿舰无触碰伤害）、
     * 无处可去终结（无舰消散）、对舰单段终结（玩家恒 50% 无 EMP）、
     * 破晓敌版多段终结（installScaleForTests(5) + 敌版携带，segments>=2 + 逐段 EMP 电弧）。
     */
    fun isSsEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == SS_SCENARIO_ID
    }

    /**
     * 双子星 DEM 实机场景开关：镜像 [isSsEnabled]。
     * 验证双槽装配（中/大导弹槽）、齐射双弹（dummy 拦截 + TrackAI 供目标 R1 + DEMScript 接管打击）、
     * payload 光束结算读数（R2：动能 ≈1000 + 4 道 EMP 电弧 / 高爆 ≈1500）、
     * 同步冲击（异种配对 ≤1s 窗口追加能量伤害 + 白闪，玩家恒 v2）、击落一枚无同步、
     * 敌版破晓档同步（installScaleForTests(5) + 敌版携带发射舱）与 12s 节奏（ammo 2/4）。
     */
    fun isGdEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == GD_SCENARIO_ID
    }

    /**
     * 重型离子脉冲实机场景开关：镜像 [isGdEnabled]。
     * 验证装配（大能量槽/700 射程/双炮管 ALTERNATING/ammo 40）、命中护盾无电弧、
     * 船体泄放电弧（遥测计数 + 频率口径）、弹匣节奏（40 发倾泻/装填）、
     * 难度隔离（installScaleForTests(5) 玩家恒 v2 无贯穿 + 敌版 k_s=2 无贯穿 / k_s=5 贯穿浮字）、
     * §2.5 待验证项（贯穿追加量对 mult≈0 目标是否被二次减免：玩家舰武器瘫痪观测面）。
     */
    fun isHipEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == HIP_SCENARIO_ID
    }

    /**
     * 辉星 MRM 实机场景开关：镜像 [isHipEnabled]。
     * 验证装配（小/中导弹槽 2500 射程 4/10 OP no_drop 两件套 tags）、优先追猎（首目标=战机）、
     * 发射舱单次两发、命中战机机体（增伤/全部武器 EMP/逐武器电弧/武器熄火）、
     * 撞击舰船与护盾爆炸恒触发（50su AOE）、撞线者死（低结构敌导弹同归于尽 vs
     * 增压 700HP 导弹仅爆炸不移除）、不主动拦导弹（目标选择遥测无导弹型）、
     * 敌版三档（installScaleForTests 1/2/5 → 爆炸倍率 0.5/1.0/2.5）与多发齐射 FPS。
     */
    fun isSmEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == SM_SCENARIO_ID
    }

    /**
     * 贯星之矛实机场景开关：镜像 [isSmEnabled]。
     * 验证 HYBRID 双槽装配（大型实弹槽 onslaught WS 019 + 大型能量槽 champion WS 008）
     * 与能量结算探针（energyWeaponRangeBonus 生效 / ballisticWeaponRangeBonus 不生效）、
     * 2s 充能 + 5s 冷却 7s 循环（充能条可读 + 出膛间隔）、弹体 VFX 接管（texTrail + bloom 弹头）、
     * 命中单体三层特效（顶点闪光/大光柱/锥面计数，锥内无连带浮字）、
     * 命中集群锥面结算（破片浮字 + 本体豁免契约零破坏）、
     * 敌版三档（installScaleForTests 1/2/5 → 半角 20/25/40、锥长 300/375/600、伤害 2500/3125/5000）
     * 与破晓档 600su/80° 粗筛帧率。
     */
    fun isPlEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == PL_SCENARIO_ID
    }

    fun outputDir(): Path {
        val explicit = System.getProperty(OUTPUT_DIR_PROPERTY)?.takeIf { it.isNotBlank() }
        if (explicit != null) return Path.of(explicit)

        val gameRoot = System.getProperty("user.dir")?.takeIf { it.isNotBlank() } ?: "."
        return Path.of(gameRoot, "ssoptimizer-automation-output")
    }
}
