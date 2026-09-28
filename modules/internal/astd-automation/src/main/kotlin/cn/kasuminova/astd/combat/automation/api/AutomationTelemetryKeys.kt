package cn.kasuminova.astd.combat.automation.api

/**
 * 自动化烟测自定义遥测/标记键集中登记处。
 *
 * 收录范围：由自动化舞台自身写入或作为闩锁读取的 `engine.customData` 键、
 * 弹体 `customData` 键与 MutableStats 修饰键——拆分前散落在枢纽伴生对象中，此处集中以便发现。
 *
 * 注意边界：各武器/系统**生产侧**的遥测键（`TELEMETRY_*` / `TELE_*` 常量）
 * 归 astd-combat 各领域类所有（如 `StellarMrmMissileAI.TELE_FIRST_TARGET`、
 * `GravityRiftSystemStats.TELEMETRY_TARGET_KEY`），本对象只做消费引用，不重复定义。
 */
object AutomationTelemetryKeys {

    /** 引力磁暴激活闩锁键前缀（键 = 本前缀 + 母舰 id；gs 断言点 GS-C 释放闩对账）。 */
    const val GS_STORM_ACTIVATION_KEY: String = "astd_grav_storm_activation:"

    /** 引力电磁力场 MutableStats 修饰键前缀（gs 断言点 GS-E 力场收口对账）。 */
    const val GS_FIELD_MOD_ID_PREFIX: String = "astd_grav_em_field:"

    /** 引力空间折跃弹体三态标记键（弹体 customData；gsr 断言点 GSR-E/F folded/no_fold 统计）。 */
    const val GSR_FOLD_MARK_KEY: String = "astd_grav_space_fold_rolled"

    /** 重型离子脉冲舞台 EMP 承伤修饰键（hip 贯穿对照舞台的 stat 标记）。 */
    const val HIP_RESIST_MOD_ID: String = "astd_hip_automation_resist"

    /** XC-002 武器相位舞台结构冗余修饰键（穿透结算证据不掉 hulk 的奶回口径）。 */
    const val XC2_STAGE_MOD_ID: String = "astd_xc2_stage"

    /** 贯星之矛能量结算探针修饰键（pl MOUNT 相位 energyWeaponRangeBonus 生效断言）。 */
    const val PL_STAT_PROBE_ID: String = "astd_pl_automation_stat_probe"

    /** 子冰晶弹体 spec id（ice shard 诊断字段 iceShardSubShardsInPlay 的在场计数口径）。 */
    const val ICE_SHARD_SUB_SHARD_SPEC_ID: String = "astd_ice_shard_sub_msl"
}
