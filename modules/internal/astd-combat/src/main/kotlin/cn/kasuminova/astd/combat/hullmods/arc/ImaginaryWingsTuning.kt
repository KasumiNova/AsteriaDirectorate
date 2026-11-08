package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

/**
 * 虚数之翼（星翼-级 astd_xc_002）全部机制数值。
 * 设计来源：docs/design/ships/blue/10-unique.md#星翼级、docs/design/weapons/blue/10-signature.md#坠星残翼。
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

    const val BARRAGE_MOTES_PER_WAVE = 2

    // ---------- 星羽追踪光矢 ----------

    val MOTE_DAMAGE = ScalingEntry(120f, 150f, 250f)

    const val MOTE_EMP = 100f

    const val MOTE_LAUNCH_SPEED = 350f

    const val MOTE_TRACK_DELAY = 0.25f

    const val MOTE_PURSUIT_SPEED = 1100f

    const val MOTE_FLIGHT_TIME = 2.5f

    const val MOTE_SALVO_COUNT = 6

    /** 光翼发射锚点（舰体相对坐标，x 前 / y 左）：左舷 3 枚，右舷镜像。 */
    val WING_ANCHORS: List<Vector2f> = listOf(
        Vector2f(0f, 68f),
        Vector2f(-35f, 76f),
        Vector2f(-62f, 68f),
    )

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

    /** 光翼锚点换算世界坐标：facing 旋转 + 舰体位置；side=1 左舷原样，side=-1 右舷镜像。 */
    fun anchorWorld(ship: com.fs.starfarer.api.combat.ShipAPI, anchor: Vector2f, side: Int): Vector2f {
        val rad = Math.toRadians(ship.facing.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()
        val lx = anchor.x
        val ly = anchor.y * side
        return Vector2f(
            ship.location.x + lx * cos - ly * sin,
            ship.location.y + lx * sin + ly * cos,
        )
    }

    /** 光翼外扩发射角：左舷朝向 facing+180∓jitter 的后外侧扇面（jitter 45°~75° 相对舰尾轴）。 */
    fun launchAngleDeg(facing: Float, side: Int): Float {
        val jitter = MathUtils.getRandomNumberInRange(45f, 75f)
        return facing + side * (180f - jitter)
    }
}
