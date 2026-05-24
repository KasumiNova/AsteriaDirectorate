package cn.kasuminova.astd.combat.hullmods.arc

import kotlin.math.floor

internal class ASTDPlasmaShieldGridState(finalMaxArmor: Float) {

    companion object {
        const val SECTOR_COUNT = 12
        const val DEGREES_PER_SECTOR = 30f
        const val SECTOR_MAX_ARMOR_FRACTION = 0.75f
        const val MIN_EFFECTIVE_FRACTION = 0.05f
        const val NORMAL_RECOVERY_PER_SECOND = 0.01f
        const val DISENGAGED_RECOVERY_PER_SECOND = 0.05f
        const val DISENGAGED_AFTER_SECONDS = 10f
    }

    private val maxIntegrity = (finalMaxArmor * SECTOR_MAX_ARMOR_FRACTION).coerceAtLeast(1f)
    private val integrity = FloatArray(SECTOR_COUNT) { maxIntegrity }
    private val timeSinceHit = FloatArray(SECTOR_COUNT) { DISENGAGED_AFTER_SECONDS }

    fun sectorForHitAngle(relativeAngle: Float): Int {
        val normalized = ((relativeAngle % 360f) + 360f) % 360f
        return floor(normalized / DEGREES_PER_SECTOR).toInt().coerceIn(0, SECTOR_COUNT - 1)
    }

    fun effectiveArmorFraction(sector: Int): Float =
        (integrity[sectorIndex(sector)] / maxIntegrity).coerceIn(MIN_EFFECTIVE_FRACTION, 1f)

    fun applyAbsorbedDamage(sector: Int, absorbedDamage: Float) {
        val idx = sectorIndex(sector)
        integrity[idx] = (integrity[idx] - absorbedDamage.coerceAtLeast(0f)).coerceAtLeast(0f)
        timeSinceHit[idx] = 0f
    }

    fun advance(amount: Float) {
        val dt = amount.coerceAtLeast(0f)
        if (dt <= 0f) return

        for (idx in 0 until SECTOR_COUNT) {
            timeSinceHit[idx] += dt
            val recoveryRate = if (timeSinceHit[idx] > DISENGAGED_AFTER_SECONDS) {
                DISENGAGED_RECOVERY_PER_SECOND
            } else {
                NORMAL_RECOVERY_PER_SECOND
            }
            integrity[idx] = (integrity[idx] + maxIntegrity * recoveryRate * dt).coerceAtMost(maxIntegrity)
        }
    }

    private fun sectorIndex(sector: Int): Int = sector.coerceIn(0, SECTOR_COUNT - 1)
}
