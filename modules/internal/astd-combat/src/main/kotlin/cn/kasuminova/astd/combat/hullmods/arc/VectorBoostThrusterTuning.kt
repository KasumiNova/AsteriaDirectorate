package cn.kasuminova.astd.combat.hullmods.arc

import kotlin.math.sqrt

/**
 * 矢量增压推进器的航速折算与硬辐能插值。
 * 航速转质量、航速转机动的上限固定为 100%，不进难度锚点。
 */
internal object VectorBoostThrusterTuning {

    const val SPEED_RATIO_CAP = 1f

    /** 满航速时质量与四项机动的加成上限。 */
    const val MAX_SPEED_BONUS_FRACTION = 1f

    /**
     * 零辐能加速满加成，单位为速度。
     * 设计只规定衰减曲线，没有给出绝对加成。原版 settings.json 的 zeroFluxEngineBoost 为 50，
     * 引擎把它写入 zeroFluxSpeedBoost 的 baseValue。本插满加成取同一幅度，
     * 用 modifyMult 每帧覆盖：硬辐能 0 时乘区为 1，速度加成为 50；80% 时乘区为 0.10，速度加成为 5。
     * 不另加 modifyFlat，避免和原版基数叠成双倍。
     */
    const val ZERO_FLUX_FULL_FLAT = 50f

    /** 硬辐能达到该比例时，零辐能加速衰减到满值的 10%。 */
    const val HARD_FLUX_DECAY_END = 0.80f

    /** 衰减终点及更高硬辐能下保留的满加成比例。 */
    const val ZERO_FLUX_FLOOR_FRACTION = 0.10f

    /**
     * 系统占用质量写入权时写入 engine.customData 的标记前缀，实际键为 前缀 + ship.id。
     * 该舰标记存在时本插本帧不写该舰质量，其他舰不受影响。系统侧按同一规则写删。
     */
    const val MASS_OWNER_KEY = "astd_gression_charge_mass_owner"

    /** 本插缓存的基准质量键前缀，写在 engine.customData，实际键为 前缀 + ship.id。 */
    const val MASS_BASE_KEY = "astd_vector_boost_thruster_base_mass"

    fun massOwnerKey(shipId: String): String = MASS_OWNER_KEY + shipId

    fun massBaseKey(shipId: String): String = MASS_BASE_KEY + shipId

    /** 该舰本帧是否让出质量写入权。 */
    fun isMassOwnedBySystem(customData: Map<String, Any?>, shipId: String): Boolean =
        customData.containsKey(massOwnerKey(shipId))

    /**
     * 返回基准质量并在缺失时缓存当前质量。缓存只在本插持有写入权时发生，所以当前质量就是未加成的基准。
     * 当前质量无效时返回 null，本帧不写。
     */
    fun resolveBaseMass(customData: MutableMap<String, Any?>, shipId: String, currentMass: Float): Float? {
        val key = massBaseKey(shipId)
        val cached = customData[key] as? Float
        if (cached != null && cached > 0f) return cached
        if (currentMass.isNaN() || currentMass <= 0f) return null
        customData[key] = currentMass
        return currentMass
    }

    /**
     * 卸载或残骸时移除缓存并返回应写回的基准。系统占用该舰时只清缓存，不写回，返回 null。
     */
    fun releaseBaseMass(customData: MutableMap<String, Any?>, shipId: String): Float? {
        val base = customData.remove(massBaseKey(shipId)) as? Float ?: return null
        if (isMassOwnedBySystem(customData, shipId)) return null
        return base.takeIf { it > 0f }
    }

    fun speedRatio(speed: Float, maxSpeed: Float): Float {
        if (speed.isNaN() || maxSpeed.isNaN() || maxSpeed <= 0f) return 0f
        return (speed / maxSpeed).coerceIn(0f, SPEED_RATIO_CAP)
    }

    fun maneuverPercent(speedRatio: Float): Float =
        speedRatio.coerceIn(0f, SPEED_RATIO_CAP) * MAX_SPEED_BONUS_FRACTION * 100f

    fun massMultiplier(speedRatio: Float): Float =
        1f + speedRatio.coerceIn(0f, SPEED_RATIO_CAP) * MAX_SPEED_BONUS_FRACTION

    /**
     * 硬辐能 0 为满加成，[HARD_FLUX_DECAY_END] 衰减到满值的 [ZERO_FLUX_FLOOR_FRACTION]，其间线性，更高保持该比例。
     */
    fun zeroFluxFlat(hardFluxLevel: Float): Float {
        val level = if (hardFluxLevel.isNaN()) 0f else hardFluxLevel.coerceAtLeast(0f)
        val fraction = if (level >= HARD_FLUX_DECAY_END) {
            ZERO_FLUX_FLOOR_FRACTION
        } else {
            val t = level / HARD_FLUX_DECAY_END
            1f - t * (1f - ZERO_FLUX_FLOOR_FRACTION)
        }
        return ZERO_FLUX_FULL_FLAT * fraction
    }

    /**
     * 覆盖原版 zeroFluxSpeedBoost 基数的乘区。满加成对应 1，衰减终点对应 [ZERO_FLUX_FLOOR_FRACTION]。
     */
    fun zeroFluxMult(hardFluxLevel: Float): Float = zeroFluxFlat(hardFluxLevel) / ZERO_FLUX_FULL_FLAT

    fun scaledMass(baseMass: Float, speedRatio: Float): Float = baseMass * massMultiplier(speedRatio)
}

internal fun vectorSpeed(x: Float, y: Float): Float {
    if (x.isNaN() || y.isNaN()) return 0f
    return sqrt(x * x + y * y)
}
