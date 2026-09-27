package cn.kasuminova.astd.combat.effect.arc.geminidem

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingMap
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl

/**
 * 双子星 DEM 的机制数值锚点与 id 常量（规格 10 §2.1，对齐 `ElectricDriveAcceleratorDifficulty` 先例）。
 *
 * 动机：EMP 电弧总量与同步冲击增伤是本组的两条缩放数值机制；id 常量与难度锚点集中一处，
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

    /** 战机型发射武器 id（双子座轰炸联队内嵌）：按本 id 切换战机版弹头编成。 */
    const val FIGHTER_WEAPON_ID = "astd_gemini_dem_fighter"

    /**
     * 战机版隐藏弹头/弹体/payload id（数据驱动 ×0.75 削弱链，面板 750）：
     * 弹头 spec 独立（damagePerShot 750、behaviorSpec payloadWeaponId 指向战机版 payload），
     * 战机版 payload dps 动能 750 / 高爆 1125（750×1.5）。
     * 实机判例（2026-09-27）：DEMScript 打击段新建 FX drone 承载 payload 光束，
     * 弹头导弹 customData 不传递到 drone，实体标记通道不成立，只能走 spec 数据通道。
     */
    const val KINETIC_FIGHTER_WEAPON_ID = "astd_gemini_dem_kinetic_fighter"
    const val HE_FIGHTER_WEAPON_ID = "astd_gemini_dem_he_fighter"
    const val KINETIC_FIGHTER_PROJ_ID = "astd_gemini_dem_kinetic_fighter_msl"
    const val HE_FIGHTER_PROJ_ID = "astd_gemini_dem_he_fighter_msl"
    const val KINETIC_PAYLOAD_FIGHTER_ID = "astd_gemini_dem_kinetic_payload_fighter"
    const val HE_PAYLOAD_FIGHTER_ID = "astd_gemini_dem_he_payload_fighter"

    /**
     * 单弹头面板伤害（动能/高爆等额）：EMP 电弧伤害与同步增伤皆以本值为基准。
     * 面板改动须同步 catalog warhead/payload 行（`astd_gemini_dem_kinetic`/`_he` damagePerShot
     * 与 `astd_gemini_dem_kinetic_payload` dps 均为 1000，`astd_gemini_dem_he_payload` dps 为 150% 面板 = 1500）。
     */
    const val WARHEAD_PANEL_DAMAGE = 1000f

    /** 同步窗口：异种弹头命中时间差 ≤ 该秒数触发同步共振（含边界）。 */
    const val SYNC_WINDOW_SECONDS = 1f

    /** 动能光束命中期间 EMP 电弧打击间隔（秒）。 */
    const val EMP_ARC_INTERVAL = 0.2f

    /** 每轮打击的 EMP 电弧预算（道）：自 beam 首伤帧起固定打满即止，与照射时长解耦。 */
    const val EMP_ARC_COUNT = 5

    /** 单道 EMP 电弧占 EMP 总量的比例（[EMP_ARC_COUNT] 道 × 本比例 = 总量 100%）。 */
    const val EMP_ARC_SHARE_OF_TOTAL = 0.2f

    /**
     * EMP 电弧总量（面板倍率）：迟暮 100% / 砺刃 200% / 破晓 500%。
     * 单道 EMP = 面板 × 本倍率 × [EMP_ARC_SHARE_OF_TOTAL]（v1/v2/v5 = 200/400/1000）；
     * 玩家固定 v2，敌方按轨一 k_s（[resolve]）。
     */
    val EMP_TOTAL_FRACTION = ScalingEntry(1.0f, 2.0f, 5.0f, ScalingMap.LINEAR)

    /**
     * 同步共振增伤倍率（加算于 payload 光束后续伤害）：迟暮 +50% / 砺刃 +100% / 破晓 +250%。
     * 触发时对双弹头导弹的 energyWeaponDamageMult 施加乘区（payload 光束 type=ENERGY，导弹是 ShipAPI），
     * 实际结算略低于面板加成属可接受范畴。
     */
    val SYNC_DAMAGE_BONUS = ScalingEntry(0.5f, 1.0f, 2.5f, ScalingMap.LINEAR)

    /** 同步增伤写入导弹 stats 的乘区修饰 id。 */
    const val SYNC_STAT_MOD_ID = "astd_gemini_dem_sync"

    /**
     * 战机型削弱系数（乘算叠加于难度缩放之后）：战机版 spec 数值（弹头 750 / payload dps 750/1125）
     * 已按本系数折算落 catalog；脚本侧仅剩 EMP 电弧单道按本系数乘算（电弧伤害是脚本显式数值，不进 spec）。
     * 0.75 × 舰装面板 1000 = 战机型面板 750。
     */
    const val FIGHTER_DAMAGE_MULT = 0.75f

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

    /**
     * 难度统一取值：玩家来源（[sourceOwner] == 0）固定 v2，否则按轨一 k_s 映射。
     * 命中时每次调用（不缓存，对齐 `StellarMrmDifficulty` 同型入口）。
     */
    fun resolve(entry: ScalingEntry, sourceOwner: Int): Float =
        resolve(DifficultyTuningImpl, entry, sourceOwner)

    /** 可注入 [DifficultyTuning] 的取值入口（单元测试与运行共用同一路径）。 */
    fun resolve(tuning: DifficultyTuning, entry: ScalingEntry, sourceOwner: Int): Float =
        if (sourceOwner == 0) entry.v2 else tuning.value(entry)
}
