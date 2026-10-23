package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.api.difficulty.DifficultyTuning

internal object GravityCollapseDifficulty {

    private val log = AstdLog.logger

    @Volatile
    private var nullSourceWarned = false

    class ResolvedValues(
        val aoeDamageRatio: Float,
        val hullDamageRatio: Float,
        val mobilityReduction: Float,
        val mobilityDuration: Float,
        val phasedDamageRatio: Float,
    )

    fun resolve(
        tuning: DifficultyTuning,
        sourceOwner: Int?,
        config: GravityCollapseOnHitConfig,
        weaponId: String,
    ): ResolvedValues {
        if (sourceOwner == null && !nullSourceWarned) {
            nullSourceWarned = true
            log.warn("[ASTD] 引力坍缩炮：weapon.ship 为 null（weapon=$weaponId），难度数值保守取 v2 设计基准")
        }
        val isPlayer = sourceOwner == null || sourceOwner == 0
        return ResolvedValues(
            aoeDamageRatio = tuning.valueFor(config.aoeDamageRatio, isPlayer),
            hullDamageRatio = tuning.valueFor(config.hullDamageRatio, isPlayer),
            mobilityReduction = tuning.valueFor(config.mobilityReduction, isPlayer),
            mobilityDuration = tuning.valueFor(config.mobilityDuration, isPlayer),
            phasedDamageRatio = tuning.valueFor(config.phasedDamageRatio, isPlayer),
        )
    }

    fun hullDamageBasis(currentHitpoints: Float, maxHitpoints: Float, armamentUpgrade: Boolean): Float =
        (if (armamentUpgrade) maxHitpoints else currentHitpoints).coerceAtLeast(0f)

    fun resetWarnStateForTests() {
        nullSourceWarned = false
    }
}
