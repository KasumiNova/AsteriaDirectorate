package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import com.fs.starfarer.api.combat.DamageType
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

object StarfallWingTuning {
    const val WEAPON_ID = "astd_starfall_wing"
    const val SHOT_SPEC_ID = "astd_starfall_wing_shot"
    const val SHUTTLE_SPEC_ID = "astd_starfall_wing_shuttle"
    const val SHUTTLE_LAUNCHER_ID = "astd_starfall_wing_shuttle_launcher"
    const val SHOT_STATE_KEY = "astd_starfall_wing_shot_state"
    const val VISUAL_SCALE_KEY = "astd_projectile_visual_scale"
    const val BASE_SPEED_KEY = "astd_projectile_base_speed"
    const val CHARGE_FRACTION = 0.1f
    val DAMAGE_FACTOR = ScalingEntry(0.75f, 1f, 1.75f)
    val ARC_MIN = ScalingEntry(1f, 2f, 5f)
    val ARC_MAX = ScalingEntry(2f, 4f, 10f)
    val POWER_MIN = ScalingEntry(0.4f, 0.5f, 0.8f)
    val POWER_MAX = ScalingEntry(1.4f, 1.5f, 1.8f)
    val CHARGE_CONVERSION = ScalingEntry(0.07f, 0.1f, 0.19f)
    val HIT_GAIN = ScalingEntry(3f, 4f, 7f)
    val RED_HULL_DAMAGE = ScalingEntry(0.01f, 0.02f, 0.05f)
    val GOLD_HARD_FLUX = ScalingEntry(0.005f, 0.01f, 0.025f)
    const val TURN_RATE = 240f
    const val SWAY_DEGREES = 20f
    const val TARGET_SCAN_INTERVAL = 0.1f

    enum class ShuttleType(val damageType: DamageType, val damageFactor: Float, val radius: Float, val vfxId: String) {
        BLUE(DamageType.ENERGY, 1f, 150f, "astd_starfall_wing_blue"),
        RED(DamageType.HIGH_EXPLOSIVE, 1f, 150f, "astd_starfall_wing_red"),
        GOLD(DamageType.KINETIC, 1f, 150f, "astd_starfall_wing_gold"),
        PURPLE(DamageType.FRAGMENTATION, 3f, 300f, "astd_starfall_wing_purple"),
    }

    fun power(randomUnit: Float, spentCharge: Float, minimum: Float, maximum: Float, conversion: Float): Float {
        require(randomUnit in 0f..1f && spentCharge >= 0f && minimum > 0f && maximum >= minimum && conversion >= 0f)
        return minimum + (maximum - minimum) * randomUnit + spentCharge * conversion
    }

    fun sizeFactor(power: Float): Float {
        if (power <= 2f) return power
        // 对递减的尺寸增长率积分，避免跨越衰减档位时弹体反而缩小。
        return 2f + 0.2f / ln(2f) * (1f - 0.5f.pow((power - 2f) / 0.2f))
    }

    fun speedFactor(power: Float): Float = 1f + (power - 1f) * 0.5f

    fun turnRate(speed: Float): Float = TURN_RATE * maxOf(1f, speed / 1100f)

    fun turnToward(facing: Float, targetFacing: Float, maxTurn: Float): Float {
        val difference = ((targetFacing - facing) % 360f + 540f) % 360f - 180f
        return ((facing + difference.coerceIn(-maxTurn, maxTurn)) % 360f + 360f) % 360f
    }

    fun arcOffset(progress: Float, phase: Float): Float {
        if (progress <= 0f || progress >= 0.45f) return 0f
        val side = sin(phase)
        val amplitude = (if (side < 0f) -1f else 1f) * (0.6f + 0.4f * kotlin.math.abs(side))
        return sin(progress / 0.45f * PI.toFloat()) * amplitude * SWAY_DEGREES
    }

    fun approachOffset(progress: Float, phase: Float, targetDistance: Float, remainingRange: Float, speed: Float = 0f): Float {
        if (remainingRange <= targetDistance || targetDistance <= maxOf(150f, speed * 0.1f)) return 0f
        val budgetAngle = Math.toDegrees(acos((targetDistance / remainingRange).coerceIn(0f, 1f)).toDouble()).toFloat() * 0.35f
        val offset = arcOffset(progress, phase)
        return offset.coerceIn(-min(SWAY_DEGREES, budgetAngle), min(SWAY_DEGREES, budgetAngle))
    }

}
