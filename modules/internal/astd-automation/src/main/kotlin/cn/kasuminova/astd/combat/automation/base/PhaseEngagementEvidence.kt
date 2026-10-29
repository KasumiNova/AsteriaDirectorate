package cn.kasuminova.astd.combat.automation.base

object PhaseEngagementEvidence {
    data class RangedAttack(
        val fire: PhaseCombatEvidence.FireEvent, val damage: Float,
        val targetId: String = fire.targetId.orEmpty(), val distance: Float = fire.distance,
    )
    data class CooldownAttack(
        val cycle: PhaseCycleEvidence.Cycle,
        val sample: PhaseCycleEvidence.Cooldown,
        val before: PhaseVolleyEvidence.Volley,
        val after: PhaseVolleyEvidence.Volley,
        val savedSeconds: Float,
        val damage: Float,
    )

    enum class RejectionCode {
        CYCLE_NOT_ATTRIBUTED, CYCLE_INVALID_ACTION, CYCLE_INVALID_RECOVER_MODE, CYCLE_NO_TARGET,
        CYCLE_SURFACE_BEFORE_START, CYCLE_END_BEFORE_SURFACE, CYCLE_UNSTABLE_TIME_MULT,
        CYCLE_INVALID_BASE_TIME_MULT, CYCLE_NO_COOLDOWN_SAMPLES,
        WEAPON_UNSTABLE, WEAPON_INVALID_REMAINING_AT_EXIT, WEAPON_INVALID_NATURAL_RATE,
        WEAPON_AMMO_NOT_RESTORED, WEAPON_INSUFFICIENT_EXTRA_PROGRESS,
        WEAPON_NO_BEFORE_VOLLEY, WEAPON_INTERMEDIATE_FIRE, WEAPON_NO_AFTER_VOLLEY,
        WEAPON_AFTER_VOLLEY_NOT_FULL_BURST, WEAPON_AFTER_VOLLEY_TOO_LATE, WEAPON_AFTER_VOLLEY_TARGET_MISMATCH,
        WEAPON_AFTER_VOLLEY_INVALID_TIME_MULT, WEAPON_AFTER_VOLLEY_REFIRE_DELAY_MISMATCH,
        WEAPON_AFTER_VOLLEY_COOLDOWN_MISMATCH, WEAPON_INTERMEDIATE_CYCLE, WEAPON_INSUFFICIENT_TIME_SAVED,
        WEAPON_INSUFFICIENT_DAMAGE
    }

    data class BeforeCandidate(
        val volley: PhaseVolleyEvidence.Volley?,
        val fullBurst: Boolean,
        val end: Float,
        val commandTime: Float,
        val allTargetMatch: Boolean,
        val targetId: String?
    )

    data class CooldownRejection(
        val code: RejectionCode,
        val weaponId: String = "",
        val slot: String = "",
        val detail: String = "",
        val floatValues: Map<String, Float> = emptyMap(),
        val beforeCandidate: BeforeCandidate? = null
    )

    data class CooldownEvaluation(
        val passed: List<CooldownAttack>,
        val rejections: List<Pair<PhaseCycleEvidence.Cycle, CooldownRejection>>
    )

    fun rangedAttacks(evidence: PhaseCombatEvidence, minimumRangeFraction: Float): List<RangedAttack> =
        evidence.fireEvents.mapNotNull { fire ->
            val target = fire.targetId ?: return@mapNotNull null
            if (!fire.attackWeapon || !fire.distance.isFinite() || !fire.distanceError.isFinite() ||
                fire.range <= 0f || fire.observationDelay > 0.1f) return@mapNotNull null
            val minimum = maxOf(fire.separation + 100f, fire.range * minimumRangeFraction)
            if (fire.distance - fire.distanceError < minimum ||
                fire.distance + fire.distanceError > fire.range + fire.separation) return@mapNotNull null
            val damage = evidence.fireEffectiveDamage(fire, target)
            if (damage <= 0f) null else RangedAttack(fire, damage)
        }

    fun carrierAttacks(evidence: PhaseCombatEvidence): List<RangedAttack> = buildList {
        for (fire in evidence.wingFireEvents) {
            if (!fire.attackWeapon || fire.carrierId == null || fire.observationDelay > 0.1f) continue
            for ((target, range) in fire.carrierRanges) {
                if (!range.distance.isFinite() || !range.error.isFinite() || range.distance - range.error <= 800f) continue
                val damage = evidence.wingHitEvents.filter {
                    it.fire === fire && it.targetId == target && it.timeSec in fire.timeSec..(fire.timeSec + 10f)
                }.sumOf { it.effectiveDamage.toDouble() }.toFloat()
                if (damage > 0f) add(RangedAttack(fire, damage, target, range.distance))
            }
        }
    }

    fun cooldownAttacks(evidence: PhaseCombatEvidence): List<CooldownAttack> =
        evaluateCooldownAttacks(evidence).passed

    fun evaluateCooldownAttacks(evidence: PhaseCombatEvidence): CooldownEvaluation {
        val passed = mutableListOf<CooldownAttack>()
        val rejections = mutableListOf<Pair<PhaseCycleEvidence.Cycle, CooldownRejection>>()

        for (cycle in evidence.phaseCycles.cycles) {
            if (!cycle.attributed) {
                rejections.add(cycle to CooldownRejection(RejectionCode.CYCLE_NOT_ATTRIBUTED))
                continue
            }
            if (cycle.action != "RECOVER") {
                rejections.add(cycle to CooldownRejection(RejectionCode.CYCLE_INVALID_ACTION, detail = cycle.action))
                continue
            }
            if (cycle.targetId == null) {
                rejections.add(cycle to CooldownRejection(RejectionCode.CYCLE_NO_TARGET))
                continue
            }
            if (cycle.recoverMode !in setOf("PHASE_COOLDOWN", "PHASE_FLUX")) {
                rejections.add(cycle to CooldownRejection(RejectionCode.CYCLE_INVALID_RECOVER_MODE, detail = cycle.recoverMode))
                continue
            }
            if (cycle.surface < cycle.start) {
                rejections.add(cycle to CooldownRejection(RejectionCode.CYCLE_SURFACE_BEFORE_START,
                    floatValues = mapOf("surface" to cycle.surface, "start" to cycle.start)))
                continue
            }
            if (cycle.end < cycle.surface) {
                rejections.add(cycle to CooldownRejection(RejectionCode.CYCLE_END_BEFORE_SURFACE,
                    floatValues = mapOf("end" to cycle.end, "surface" to cycle.surface)))
                continue
            }
            if (!cycle.stableTimeMult) {
                rejections.add(cycle to CooldownRejection(RejectionCode.CYCLE_UNSTABLE_TIME_MULT))
                continue
            }
            if (!cycle.baseTimeMult.isFinite() || cycle.baseTimeMult <= 0f) {
                rejections.add(cycle to CooldownRejection(RejectionCode.CYCLE_INVALID_BASE_TIME_MULT,
                    floatValues = mapOf("baseTimeMult" to cycle.baseTimeMult)))
                continue
            }
            if (cycle.cooldowns.isEmpty()) {
                rejections.add(cycle to CooldownRejection(RejectionCode.CYCLE_NO_COOLDOWN_SAMPLES))
                continue
            }

            for (cooldown in cycle.cooldowns) {
                val result = buildWeaponCooldownAttack(cycle, cooldown, evidence)
                if (result.rejection != null) {
                    rejections.add(cycle to result.rejection)
                    continue
                }
                passed.add(CooldownAttack(cycle, cooldown, result.before!!, result.after!!, result.saved!!, result.damage!!))
            }
        }

        return CooldownEvaluation(passed, rejections)
    }

    private data class WeaponCheckResult(
        val rejection: CooldownRejection? = null,
        val before: PhaseVolleyEvidence.Volley? = null,
        val after: PhaseVolleyEvidence.Volley? = null,
        val saved: Float? = null,
        val damage: Float? = null
    )

    private fun buildWeaponCooldownAttack(
        cycle: PhaseCycleEvidence.Cycle,
        cooldown: PhaseCycleEvidence.Cooldown,
        evidence: PhaseCombatEvidence
    ): WeaponCheckResult {
        val weaponId = cooldown.weapon.spec?.weaponId ?: "unknown"
        val slotId = cooldown.weapon.slot?.id ?: "none"

        if (!cooldown.stable) {
            return WeaponCheckResult(rejection = CooldownRejection(RejectionCode.WEAPON_UNSTABLE,
                weaponId, slotId, cooldown.firstInstabilityReason,
                floatValues = mapOf("instabilityTime" to cooldown.firstInstabilityTime,
                    "instabilityRefireDelay" to cooldown.instabilityRefireDelay,
                    "instabilityCooldown" to cooldown.instabilityCooldown)))
        }
        if (!cooldown.remainingAtExit.isFinite()) {
            return WeaponCheckResult(rejection = CooldownRejection(RejectionCode.WEAPON_INVALID_REMAINING_AT_EXIT,
                weaponId, slotId, "", mapOf("remainingAtExit" to cooldown.remainingAtExit)))
        }
        if (!cooldown.naturalRate.isFinite() || cooldown.naturalRate <= 0f) {
            return WeaponCheckResult(rejection = CooldownRejection(RejectionCode.WEAPON_INVALID_NATURAL_RATE,
                weaponId, slotId, "", mapOf("naturalRate" to cooldown.naturalRate)))
        }
        if (cooldown.resource == PhaseCycleEvidence.Resource.AMMO &&
            (cooldown.entryAmmo >= cooldown.requiredAmmo || cooldown.ammoAtExit < cooldown.requiredAmmo ||
                cooldown.remainingAtExit > 0f)) {
            return WeaponCheckResult(rejection = CooldownRejection(RejectionCode.WEAPON_AMMO_NOT_RESTORED,
                weaponId, slotId, "", mapOf(
                    "entryAmmo" to cooldown.entryAmmo.toFloat(), "requiredAmmo" to cooldown.requiredAmmo.toFloat(),
                    "ammoAtExit" to cooldown.ammoAtExit.toFloat(), "remainingAtExit" to cooldown.remainingAtExit,
                )))
        }

        val naturalProgress = (cycle.end - cycle.commandTime) * cycle.baseTimeMult * cooldown.naturalRate
        val extraProgress = cooldown.remaining - cooldown.remainingAtExit - naturalProgress
        val progressThreshold = cycle.baseTimeMult * cooldown.naturalRate * 0.5f
        if (extraProgress < progressThreshold) {
            return WeaponCheckResult(rejection = CooldownRejection(RejectionCode.WEAPON_INSUFFICIENT_EXTRA_PROGRESS,
                weaponId, slotId, "", mapOf("extraProgress" to extraProgress, "threshold" to progressThreshold)))
        }

        val before = evidence.volleys.volleys.filter {
            it.weapon === cooldown.weapon && it.end <= cycle.commandTime
        }.maxByOrNull { it.start }
        if (before == null || !before.complete ||
            before.fires.any { fire -> !fire.attackWeapon || fire.targetId != cycle.targetId }) {
            val candidate = if (before != null) {
                BeforeCandidate(before, before.complete, before.end, cycle.commandTime,
                    before.fires.all { fire -> fire.attackWeapon && fire.targetId == cycle.targetId }, cycle.targetId)
            } else {
                BeforeCandidate(null, false, -1f, cycle.commandTime, false, cycle.targetId)
            }
            return WeaponCheckResult(rejection = CooldownRejection(RejectionCode.WEAPON_NO_BEFORE_VOLLEY,
                weaponId, slotId, "", emptyMap(), candidate))
        }

        val intermediate = evidence.volleys.volleys.firstOrNull {
            it !== before && it.weapon === cooldown.weapon && it.start > before.start && it.start < cycle.end
        }
        if (intermediate != null) {
            return WeaponCheckResult(rejection = CooldownRejection(RejectionCode.WEAPON_INTERMEDIATE_FIRE,
                weaponId, slotId, "", mapOf("intermediateStart" to intermediate.start)))
        }

        val after = evidence.volleys.volleys.filter {
            it.weapon === cooldown.weapon && it.start >= cycle.end
        }.minByOrNull { it.start }
        if (after == null) {
            return WeaponCheckResult(rejection = CooldownRejection(RejectionCode.WEAPON_NO_AFTER_VOLLEY,
                weaponId, slotId))
        }

        if (!after.complete) {
            return WeaponCheckResult(rejection = CooldownRejection(RejectionCode.WEAPON_AFTER_VOLLEY_NOT_FULL_BURST,
                weaponId, slotId, "", mapOf("afterStart" to after.start, "afterEnd" to after.end,
                    "expectedShots" to after.expectedShots.toFloat(), "actualShots" to after.fires.sumOf { it.shots }.toFloat())))
        }
        if (after.start > cycle.end + 2f) {
            return WeaponCheckResult(rejection = CooldownRejection(RejectionCode.WEAPON_AFTER_VOLLEY_TOO_LATE,
                weaponId, slotId, "", mapOf("afterStart" to after.start, "cycleEnd" to cycle.end, "delta" to (after.start - cycle.end))))
        }

        for (fire in after.fires) {
            if (!fire.attackWeapon || fire.targetId != cycle.targetId) {
                return WeaponCheckResult(rejection = CooldownRejection(RejectionCode.WEAPON_AFTER_VOLLEY_TARGET_MISMATCH,
                    weaponId, slotId, "fireTargetId:${fire.targetId},cycleTargetId:${cycle.targetId},attackWeapon:${fire.attackWeapon}"))
            }
            if (!fire.timeMult.isFinite() || kotlin.math.abs(fire.timeMult - cycle.baseTimeMult) > 0.01f) {
                return WeaponCheckResult(rejection = CooldownRejection(RejectionCode.WEAPON_AFTER_VOLLEY_INVALID_TIME_MULT,
                    weaponId, slotId, "", mapOf("fireTimeMult" to fire.timeMult, "baseTimeMult" to cycle.baseTimeMult)))
            }
            if (kotlin.math.abs(fire.refireDelay - cooldown.refireDelay) > 0.001f) {
                return WeaponCheckResult(rejection = CooldownRejection(RejectionCode.WEAPON_AFTER_VOLLEY_REFIRE_DELAY_MISMATCH,
                    weaponId, slotId, "", mapOf("fireRefireDelay" to fire.refireDelay, "sampleRefireDelay" to cooldown.refireDelay)))
            }
            if (kotlin.math.abs(fire.cooldown - cooldown.cooldown) > 0.001f) {
                return WeaponCheckResult(rejection = CooldownRejection(RejectionCode.WEAPON_AFTER_VOLLEY_COOLDOWN_MISMATCH,
                    weaponId, slotId, "", mapOf("fireCooldown" to fire.cooldown, "sampleCooldown" to cooldown.cooldown)))
            }
        }

        val otherCycle = evidence.phaseCycles.cycles.firstOrNull {
            it !== cycle && it.start < after.end && (it.end < 0f || it.end > before.end)
        }
        if (otherCycle != null) {
            return WeaponCheckResult(rejection = CooldownRejection(RejectionCode.WEAPON_INTERMEDIATE_CYCLE,
                weaponId, slotId, "", mapOf("otherCycleStart" to otherCycle.start)))
        }

        val naturalReady = cycle.commandTime + cooldown.remaining / (cooldown.naturalRate * cycle.baseTimeMult)
        val saved = naturalReady - after.start
        if (saved < 0.5f) {
            return WeaponCheckResult(rejection = CooldownRejection(RejectionCode.WEAPON_INSUFFICIENT_TIME_SAVED,
                weaponId, slotId, "", mapOf("saved" to saved, "threshold" to 0.5f)))
        }

        val damage = evidence.hitEvents.filter { hit ->
            hit.targetId == cycle.targetId && after.fires.any { it === hit.fire } &&
                hit.timeSec in hit.fire.timeSec..(after.start + 10f)
        }.sumOf { it.effectiveDamage.toDouble() }.toFloat()

        if (!damage.isFinite() || damage < 100f) {
            return WeaponCheckResult(rejection = CooldownRejection(RejectionCode.WEAPON_INSUFFICIENT_DAMAGE,
                weaponId, slotId, "", mapOf("damage" to damage, "threshold" to 100f)))
        }

        return WeaponCheckResult(before = before, after = after, saved = saved, damage = damage)
    }

    fun windowDamage(evidence: PhaseCombatEvidence, window: PhaseCombatEvidence.EnemyWindow): Float =
        evidence.hitEvents.filter { it.enemyWindow == window && it.timeSec >= it.fire.timeSec }
            .sumOf { it.effectiveDamage.toDouble() }.toFloat()
}
