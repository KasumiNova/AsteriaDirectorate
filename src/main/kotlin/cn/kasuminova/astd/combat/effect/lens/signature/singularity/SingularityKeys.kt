package cn.kasuminova.astd.combat.effect.lens.signature.singularity

/** 与“奇点投射器”导弹相关的 customData key 常量。 */
internal object SingularityKeys {
    /** 存储导弹原始 damageAmount（用于在飞行中动态置零/恢复，以及 OnHitEffect 取原值）。 */
    const val MISSILE_ORIGINAL_DAMAGE: String = "astd_singularity_orig_damage"

    /** 标记：该导弹已经命中过目标（用于避免“命中后又被管理器当作击落自爆”）。 */
    const val MISSILE_HAS_HIT: String = "astd_singularity_has_hit"

    /** 导弹：重定向次数（用于 VFX 触发“重定向脉冲”提示）。 */
    const val MISSILE_RETARGET_COUNT: String = "astd_singularity_retarget_count"

    /** 导弹：最近一次重定向发生的战斗时间戳（秒，engine.getTotalElapsedTime(false)）。 */
    const val MISSILE_LAST_RETARGET_AT: String = "astd_singularity_retarget_at"

    /** 目标舰船：战斗内永久最大船体下降累计值（su/hp）。 */
    const val TARGET_MAX_HULL_LOSS: String = "astd_singularity_max_hull_loss"

    /** 目标舰船：首次受到该效果时记录的“基准 maxHitpoints”。 */
    const val TARGET_BASE_MAX_HULL: String = "astd_singularity_base_max_hull"

    /** 战斗插件实例 key（engine.customData）。 */
    const val ENGINE_BATTLE_PLUGIN: String = "astd_singularity_battle_plugin"
}
