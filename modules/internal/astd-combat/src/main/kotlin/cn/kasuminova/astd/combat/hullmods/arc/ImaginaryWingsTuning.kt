package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.api.difficulty.ScalingEntry

/**
 * 虚数之翼（星翼-级 astd_xc_002）全部机制数值。
 */
object ImaginaryWingsTuning {

    // ---------- 虚数充能池 ----------

    const val CHARGE_MAX = 100f

    const val CHARGE_REGEN_PER_SECOND = 5f

    const val HIT_GAIN = 3f

    const val HIT_GAIN_MIN_INTERVAL = 0.1f

    const val EMPOWERED_HIT_GAIN = 10f

    const val OVERDRAFT_THRESHOLD = 50f

    const val OVERDRAFT_COST = 50f

    const val SALVO_REFILL = 100f

    const val SALVO_COST = 30f

    // ---------- 星羽翼装弹幕 ----------

    const val BARRAGE_THRESHOLD = 20f

    const val BARRAGE_INTERVAL = 0.3f

    const val BARRAGE_COST = 8f

    const val BARRAGE_RANGE = 1100f

    // ---------- 星羽追踪光矢 ----------

    val MOTE_DAMAGE = ScalingEntry(120f, 150f, 250f)

    const val MOTE_EMP = 100f

    const val MOTE_LAUNCH_SPEED = 350f

    const val MOTE_TRACK_DELAY = 0.25f

    const val MOTE_PURSUIT_SPEED = 550f

    const val MOTE_FLIGHT_TIME = 2.5f

    /** 光矢接敌半径，与发射器射程字段一致：点防优先接管该范围内来袭的导弹与战机。 */
    const val MOTE_ENGAGE_RANGE = 1200f

    /** 追踪段横向机动强度，乘进速度矢量插值系数；值越小转得越钝。 */
    const val MOTE_TURN_AGILITY = 7f

    /** 光矢发射口取自舰体系统槽位，左右舷各此数量。 */
    const val WING_SLOT_PAIRS = 3

    // ---------- 虚数滑翔 ----------

    const val ZERO_FLUX_THRESHOLD = 0.2f

    val GLIDE_ROF_PERCENT = ScalingEntry(20f, 40f, 100f)

    val GLIDE_DAMAGE_REDUCTION = ScalingEntry(0.15f, 0.20f, 0.35f)

    // ---------- 超翼过载 ----------

    const val OVERDRIVE_SECONDS = 3.0f

    val OVERDRIVE_ROF_PERCENT = ScalingEntry(25f, 40f, 80f)

    // ---------- 星翼折跃 ----------

    const val SHIFT_DISTANCE = 400f

    /** 相位滑行时流倍率 2.5x，对应 PhaseCloakStats dynamic 键 phase_time_mult（上限 3x）。 */
    const val SHIFT_TIME_MULT_DYNAMIC = 0.75f

    const val SHIFT_TURN_MULT = 3f

    const val CLEAR_RADIUS = 250f

    const val CLEAR_DAMAGE_THRESHOLD = 600f

    val CLEAR_HEAVY_REDUCTION = ScalingEntry(0.5f, 0.6f, 0.8f)

    val SHIFT_AMMO_RELOAD = ScalingEntry(8f, 12f, 18f)

    val SHIFT_SOFT_FLUX = ScalingEntry(800f, 1200f, 2000f)
}
