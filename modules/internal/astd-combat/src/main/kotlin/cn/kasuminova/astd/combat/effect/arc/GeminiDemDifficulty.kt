package cn.kasuminova.astd.combat.effect.arc

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

    /** payload 光束武器 id：动能（DEMScript 打击段结算光束）。 */
    const val KINETIC_PAYLOAD_ID = "astd_gemini_dem_kinetic_payload"

    /** payload 光束武器 id：高爆。 */
    const val HE_PAYLOAD_ID = "astd_gemini_dem_he_payload"

    /**
     * 同步冲击基准 = 双弹面板之和（1000 动能 + 1500 高爆）。
     * 面板改动须同步本值（注释双向绑定 warhead 行 damagePerShot：
     * `astd_gemini_dem_kinetic` 1000 + `astd_gemini_dem_he` 1500）。
     */
    const val SYNC_BASE_DAMAGE = 2500f

    /** 同步窗口：异种弹头命中时间差 ≤ 该秒数触发同步冲击（含边界）。 */
    const val SYNC_WINDOW_SECONDS = 1f

    /** 动能光束首伤帧追加 EMP 电弧道数。 */
    const val EMP_ARC_COUNT = 4

    /** 每道 EMP 电弧的 EMP 伤害。 */
    const val EMP_ARC_EMP_DAMAGE = 500f

    /** 同步冲击倍率：迟暮 25%（625）/ 砺刃 43.75%（≈1094）/ 破晓 100%（2500）。 */
    val SYNC_MULT = ScalingEntry(0.25f, 0.4375f, 1.0f, ScalingMap.LINEAR)

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
}
