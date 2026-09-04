package cn.kasuminova.astd.combat.lens.marks

/**
 * 引力透镜级标记闭环的稳定 ID 与基础数值常量。
 *
 * 动机：buffId 同时用作 StackingShipBuffs 的 modifierId 与 customData key，
 * 必须全局唯一且稳定；数值集中此处便于平衡。
 */
object LensMarkIds {

    /** 误差标记（Drift Mark）：通用增伤标记。 */
    const val DRIFT_BUFF_ID: String = "astd_lens_drift_mark"

    /** 深水标记（Deep Water Mark）：电战压制标记。 */
    const val DEEP_WATER_BUFF_ID: String = "astd_lens_deep_water_mark"

    /** 两类标记每层持续时间（秒，spec §1.1：每层 5s，叠加刷新）。 */
    const val MARK_DURATION_SEC: Float = 5f

    /** 引力透镜旗舰 hullId：深水标记"对引力透镜伤害下降"按此判定来源。 */
    const val GRAVITATIONAL_LENS_HULL_ID: String = "astd_gravitational_lens"
}
