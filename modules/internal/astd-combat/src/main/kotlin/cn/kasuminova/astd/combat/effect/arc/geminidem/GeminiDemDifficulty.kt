package cn.kasuminova.astd.combat.effect.arc.geminidem

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingMap

/**
 * 双子星 DEM 的机制数值锚点与 id 常量（规格 10 §2.1，对齐 `ElectricDriveAcceleratorDifficulty` 先例）。
 *
 * 动机：双弹同步冲击是本组唯一缩放数值机制；id 常量与难度锚点集中一处，
 * 供 Salvo/TrackAI/PayloadBeam/SyncHandler 四类接线引用，面板改动时单点核对。
 *
 * 数值缩放口径（90 计划全局约定）：敌方按轨一 k_s 三锚点映射；玩家来源（owner == 0）固定 v2。
 */
object GeminiDemDifficulty {

    /** 隐藏弹头武器 id：动能（脚本 spawn 的动能导弹所属武器）。 */
    const val KINETIC_WEAPON_ID = "astd_gemini_dem_kinetic"

    /** 隐藏弹头武器 id：高爆。 */
    const val HE_WEAPON_ID = "astd_gemini_dem_he"

    /** 弹头弹体 spec id（拖尾管线登记键，对齐 catalog 生成器 geminiDemWarheadProjSpec）：动能。 */
    const val KINETIC_PROJ_ID = "astd_gemini_dem_kinetic_msl"

    /** 弹头弹体 spec id（拖尾管线登记键）：高爆。 */
    const val HE_PROJ_ID = "astd_gemini_dem_he_msl"

    /** payload 光束武器 id：动能（DEMScript 打击段结算光束）。 */
    const val KINETIC_PAYLOAD_ID = "astd_gemini_dem_kinetic_payload"

    /** payload 光束武器 id：高爆。 */
    const val HE_PAYLOAD_ID = "astd_gemini_dem_he_payload"

    /**
     * 单弹头面板伤害（动能/高爆等额）：EMP 电弧单道伤害与同步增伤皆以本值为基准。
     * 面板改动须同步 catalog warhead/payload 行（`astd_gemini_dem_kinetic`/`_he` damagePerShot
     * 与 `astd_gemini_dem_*_payload` dps，均为 1250）。
     */
    const val WARHEAD_PANEL_DAMAGE = 1250f

    /** 同步窗口：异种弹头命中时间差 ≤ 该秒数触发同步共振（含边界）。 */
    const val SYNC_WINDOW_SECONDS = 1f

    /** 动能光束命中期间 EMP 电弧打击间隔（秒）。 */
    const val EMP_ARC_INTERVAL = 0.1f

    /** 每道 EMP 电弧的 EMP 伤害 = 单弹面板 × 本比例。 */
    const val EMP_ARC_EMP_FRACTION = 0.1f

    /**
     * 同步共振增伤倍率（加算于 payload 光束后续伤害）：迟暮 +50% / 砺刃 +100% / 破晓 +250%。
     * 触发时对双弹头导弹的 energyWeaponDamageMult 施加乘区（payload 光束 type=ENERGY，导弹是 ShipAPI），
     * 实际结算略低于面板加成属可接受范畴。
     */
    val SYNC_DAMAGE_BONUS = ScalingEntry(0.5f, 1.0f, 2.5f, ScalingMap.LINEAR)

    /** 同步增伤写入导弹 stats 的乘区修饰 id。 */
    const val SYNC_STAT_MOD_ID = "astd_gemini_dem_sync"

    /** 同步共振紫色光束视觉的持续时长（秒，自触发时刻起算；略长于 payload 光束 1s 照射）。 */
    const val SYNC_VISUAL_DURATION = 1.2f

    /** 追踪段目标搜索半径（su）：shipTarget 为空时的最近敌舰兜底搜索范围。 */
    const val TRACK_TARGET_RANGE = 2500f

    /** 齐射双弹垂直错位距离（su）：沿发射朝向垂直方向 ±该值。 */
    const val SALVO_LATERAL_OFFSET = 12f

    /** 齐射双弹朝向散布（度）：±该值。 */
    const val SALVO_FACING_SPREAD_DEG = 2f

    /** 弹头保险时间（秒）：生成后该时间才可触发/碰撞结算。 */
    const val WARHEAD_ARMING_TIME = 0.3f

    /** customData 键：齐射批次号（仅日志/调试关联用，不参与同步判定）。 */
    const val SALVO_KEY = "astd_gemini_salvo"

    /** engine.customData 键：同步登记表（目标 id → 首击记录）。 */
    const val SYNC_REGISTRY_KEY = "astd_gemini_sync_registry"

    /** 弹头 demDrone 实体 customData 键：紫色共振视觉到期时刻（战斗总秒）。键在实体自身表上，规避引擎级表按 id 碰撞（FX drone id 为空串）。 */
    const val SYNC_VISUAL_KEY = "astd_gemini_dem_sync_visual"
}
