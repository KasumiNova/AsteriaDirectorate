package cn.kasuminova.astd.internal.debug

import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.LENS_PHASE1_SCENARIO_ID
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.LENS_PHASE2_SCENARIO_ID
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isArcProductionEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isAvEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isChargeNeedleEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isEdaEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isGdEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isGravRiftScenarioEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isHipEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isLensPhase1Enabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isPlEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isPsEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isQjEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isSmEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isSsEnabled
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.isTrailPauseProbeEnabled
import java.nio.file.Path

/**
 * Dev-only descriptor for the first in-game projectile VFX automation scenario.
 */
object ASTDInGameAutomationScenario {
    const val SCENARIO_ID: String = "xc_001_starfall_echo_basic"
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

    // 绕后验证矩阵扩展：密蒙巡洋 / 茑萝航母 / 锤头高速对照组（与 PF 共用一套插件相位机，
    // 各自 MissionDefinition 只决定舰队构成，判定模式由插件按场景 id 选择）。
    const val PF2_SCENARIO_ID: String = "lens_phase_flank_zw002"
    const val PF2_HULL_ID: String = "astd_zw_002"
    const val PF2_VARIANT_ID: String = "astd_zw_002_Standard"
    const val PF3_SCENARIO_ID: String = "lens_phase_flank_zw103"
    const val PF3_HULL_ID: String = "astd_zw_103"
    const val PF3_VARIANT_ID: String = "astd_zw_103_Standard"
    const val PFC_SCENARIO_ID: String = "lens_phase_flank_zw101_hh"
    const val PFC_ENEMY_VARIANT_ID: String = "hammerhead_Balanced"
    const val PFC_ENEMY_HULL_ID: String = "hammerhead"

    // 茑萝 vs 辉煌（台风鱼雷架满挂）生存判据场景：贴盾上浮 / 非相位硬吃高威胁投射物零容忍。
    const val PF4_SCENARIO_ID: String = "lens_phase_flank_zw103_brilliant"
    const val PF4_ENEMY_VARIANT_ID: String = "brilliant_AstdTorpedo"
    const val PF4_ENEMY_HULL_ID: String = "brilliant"

    // 友伤防御下潜实测：插件周期性生成友方直射弹瞄准本舰，验证 collectFriendly 防御链路。
    const val PFB_SCENARIO_ID: String = "lens_phase_friendly_beam"
    const val PFB_HULL_ID: String = "astd_zw_101"
    const val PFB_VARIANT_ID: String = "astd_zw_101_Standard"
    const val PFB_ENEMY_VARIANT_ID: String = "dominator_Assault"
    const val PFB_ENEMY_HULL_ID: String = "dominator"
    const val SHIP_ID: String = "astd_xc_001"
    const val VARIANT_ID: String = "astd_xc_001_Standard"
    const val WEAPON_ID: String = "astd_starfall_echo"
    const val PROJECTILE_SPEC_ID: String = "astd_starfall_echo_shot"
    const val XC002_SCENARIO_ID: String = "xc_002_rift_shift_basic"
    const val XC002_HULL_ID: String = "astd_xc_002"
    const val XC002_VARIANT_ID: String = "astd_xc_002_Standard"
    const val XC002_SYSTEM_ID: String = "astd_rift_shift"
    const val XC002_WEAPON_ID: String = "astd_starfall_wing"

    // SSOptimizer 遥测契约标签（其 helper/verifier 硬编码的字面值）；运行期 preset 已随旧管线删除，此处仅作场景描述符。
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

    /**
     * 阶段一决明级实机场景开关：镜像 [isArcProductionEnabled]。
     * 仅当 automation 启用且场景属性显式为 [LENS_PHASE1_SCENARIO_ID] 时为 true。
     */
    fun isLensPhase1Enabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == LENS_PHASE1_SCENARIO_ID
    }

    /**
     * 阶段二决明级实机场景开关：镜像 [isLensPhase1Enabled]。
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
     * 验证每触发 2 弹（双管交替 × burst 2）、净空加速射程随辐能伸缩、
     * devMode HUD 状态条目、不稳定装药追加伤害与敌版三档。
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
     * payload 光束结算读数（R2：动能 ≈1000 / 高爆 ≈1500 面板 + 每轮固定 5 道 EMP 电弧，单道 v2=400）、
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
     * 摧锋鱼雷实机场景开关：镜像 [isSmEnabled]。
     * 验证装配（小/中导弹槽 1600 射程 no_drop 两件套 tags）、反舰目标选择（仅舰船入选）、
     * 二段式调速器（截速帧 + 速度系数 0.5~1.0）、命中结算（自适应增伤 / 护盾命中硬辐推进 /
     * 150su 全额面板 AOE）与十字辉星 + 爆炸星云特效计数。
     */
    fun isCuifengEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == CUIFENG_SCENARIO_ID
    }

    /**
     * 源生冰晶 MIRV 实机场景开关：镜像 [isCuifengEnabled]。
     * 验证装配（小/中导弹槽 1600 射程、发射舱 burst 2、no_drop 两件套 tags）、
     * 母弹引信注册与 600su 分裂（分裂次数 / 15 枚子冰晶全数生成 / 分裂距离读数）、
     * 子冰晶命中舰体附着（周期伤害 + 命中点周围承伤增伤）
     * 与分裂爆发 / 附着星云特效计数。
     */
    fun isIceShardMirvEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == ICE_SHARD_SCENARIO_ID
    }

    /**
     * 贯星之矛实机场景开关：镜像 [isSmEnabled]。
     * 验证 HYBRID 双槽装配（大型实弹槽 onslaught WS 019 + 大型能量槽 champion WS 008）
     * 与能量结算探针（energyWeaponRangeBonus 生效 / ballisticWeaponRangeBonus 不生效）、
     * 2s 充能 + 5s 冷却 7s 循环（充能条可读 + 出膛间隔）、弹体 VFX 接管（Static Trail 拖尾）、
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

    /**
     * 拖尾暂停对照探针场景开关：镜像 [isPlEnabled]。
     * 复用坠星残响舞台，按 BeforePause / DuringPause / AfterResume 三帧截图对照，
     * 定位「暂停后射弹贴图/拖尾跳变」；相位机刻意不 unpause（其余场景分支均强制 unpause）。
     */
    fun isTrailPauseProbeEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == TPP_SCENARIO_ID
    }

    /**
     * 茑萝级引力裂隙发生器实机场景开关：镜像 [isTrailPauseProbeEnabled]。
     * 验证双甲板联队齐备（ion/lance 各 2 架在场）、引力相位甲板联动
     * （母舰 setPhased 驱动在外战机同步相位与退出后严格配对恢复）、
     * 战机辐能返还（注入量 ×0.6 软辐能回母舰，界 [×0.4, ×0.85]）、
     * 裂隙布雷（telemetry planned == riftCount(dist) 且 spawned == planned、
     * 敌舰 hitpoints 近炸结算下降 ≥2000）。
     */
    fun isGravRiftScenarioEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == GRG_SCENARIO_ID
    }

    /**
     * 飞蓬级战机引力联结器实机场景开关：镜像 [isGravRiftScenarioEnabled]。
     * 验证维度折叠甲板扩容（每甲板 extraDeploymentLimit 锚定 5 / 单联队在场 ≥3）、
     * 系统激活期战机时流 ×2.5 与四承伤 ×0.5、母舰软辐能持续产出（≥800）、
     * ACTIVE→OUT 召回（旧机 identity 全清 / 软→硬转化 hardFlux 上升 / 15s 内新机重新出击）。
     */
    fun isFighterGravLinkScenarioEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == FGL_SCENARIO_ID
    }

    /**
     * 密蒙级引力磁暴发生器实机场景开关：镜像 [isFighterGravLinkScenarioEnabled]。
     * 验证引力电磁力场满效压制（敌舰航速/转向 ×0.8、EMP 承伤 +0.5 位移）、
     * 充能代价（基础容量 ×20% 软辐能）与充能期全承伤 ×0.5、
     * 满充能自然释放（锥内电弧结算掉血 + 巡洋舰 2s 强制过载）、
     * 系统冷却后力场收口复原。
     */
    fun isGravStormScenarioEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == GS_SCENARIO_ID
    }

    /**
     * 舜华级引力空间复制器/折跃器实机场景开关：镜像 [isGravStormScenarioEnabled]。
     * 验证非冷却期光束承伤 ×0.75、激活代价（基础容量 ×10% 软辐能）、
     * 弹道复制（原发 +0.5s/+1.0s 各复制 1 发、复制体伤害 ×0.5、逐发复制附加 单发辐能 ×0.5 尖峰）、
     * 冷却期折跃停判与光束减免复原、冷却后折跃恢复（三态标记 folded/no_fold 与镜像离场）。
     */
    fun isGravReplicatorScenarioEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == GSR_SCENARIO_ID
    }

    /**
     * 舜华级相位绕后实机场景开关：镜像 [isGravReplicatorScenarioEnabled]。
     * 验证 GravityPhaseCloakAI 对低机动目标的绕后意图：满装配舜华（AI 驾驶）对
     * 满装配统治者级（AI 驾驶），两舰逐帧回血不结束战斗，统计下潜次数、
     * 相位中绕敌舰的方位角扫描幅度与每次上浮时相对敌舰舰艏的方位差——
     * 修复前（PHASE_ATTACK_RUN 无人管理）舜华下潜后原地罚站，上浮方位差恒小。
     */
    fun isPhaseFlankScenarioEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == PF_SCENARIO_ID
    }

    /**
     * XC-002 星翼（裂隙折跃/虚数之翼/坠星残翼）实机场景开关：镜像 [isPhaseFlankScenarioEnabled]。
     * 验证虚数之翼静止伤害乘区（−25% 锚点）与 3s 速度窗口峰值（砺刃 +100%）、
     * 裂隙折跃 800su 位移、裂隙接触持续掉血与闭合爆炸掉血、
     * 坠星残翼主弹/子射弹供给登记（StarfallWingOnFireEffect.projectileStates）
     * 与目标振频适应叠层。
     */
    fun isXc002Enabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == XC002_SCENARIO_ID
    }

    /** 相位绕后矩阵·密蒙巡洋场景开关：镜像 [isPhaseFlankScenarioEnabled]（敌舰同为统治者）。 */
    fun isPhaseFlankZw002Enabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == PF2_SCENARIO_ID
    }

    /** 相位绕后矩阵·茑萝航母场景开关：航母走位天然绕行，验证相位节奏健康（不憋死不卡潜）而非绕后幅度。 */
    fun isPhaseFlankZw103Enabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == PF3_SCENARIO_ID
    }

    /**
     * 相位绕后矩阵·高速对照组场景开关：舜华对锤头级（极速 90 > 低机动闸），
     * 验证绕后意图对高机动目标完全不布防（PHASE_ATTACK_RUN 全程零帧），防御性相位仍正常。
     */
    fun isPhaseFlankControlEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == PFC_SCENARIO_ID
    }

    /**
     * 茑萝 vs 辉煌生存判据场景开关：辉煌挂台风鱼雷发射架（reaper 级高威胁投射物），
     * 判据化两症状——贴盾上浮与非相位硬吃鱼雷任一发生即 Failed，生存窗 60s 零违规且
     * 相位节奏健康（下潜 ≥2）即 Completed。
     */
    fun isPhaseFlankBrilliantEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == PF4_SCENARIO_ID
    }

    /**
     * 友伤防御下潜实测场景开关：插件周期性向舜华投喂友方（owner 同侧）直射弹，
     * 验证 GravityPhaseCloakAI 的 collectFriendly 防御链路在实机中触发下潜规避友军火力。
     */
    fun isPhaseFriendlyBeamEnabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == PFB_SCENARIO_ID
    }

    fun outputDir(): Path {
        val explicit = System.getProperty(OUTPUT_DIR_PROPERTY)?.takeIf { it.isNotBlank() }
        if (explicit != null) return Path.of(explicit)

        val gameRoot = System.getProperty("user.dir")?.takeIf { it.isNotBlank() } ?: "."
        return Path.of(gameRoot, "ssoptimizer-automation-output")
    }
}
