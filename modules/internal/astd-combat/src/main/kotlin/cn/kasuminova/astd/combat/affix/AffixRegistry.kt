package cn.kasuminova.astd.combat.affix

import cn.kasuminova.astd.combat.affix.AffixRegistry.EXCLUSIVE_PAIRS
import cn.kasuminova.astd.combat.affix.AffixRegistry.pickAffixes
import cn.kasuminova.astd.combat.affix.AffixRegistry.slotCounts
import cn.kasuminova.astd.internal.i18n.I18n
import java.util.Random
import kotlin.math.floor

object AffixRegistry {

    enum class AffixType {
        S, M, R
    }

    data class AffixDef(
        val id: String,
        val catalogNo: String,
        val type: AffixType,
        val hullModId: String,
        val phaseOnly: Boolean = false,
    ) {
        fun displayName(): String = I18n[CATEGORY, "affix.$id.name"]

        fun description(): String = I18n[CATEGORY, "affix.$id.desc"]
    }

    const val CATEGORY: String = "asteria_directorate_bounty"

    const val ID_IRONCLAD_PLATING: String = "astd_affix_ironclad_plating"
    const val ID_CRYO_FLUX_NETWORK: String = "astd_affix_cryo_flux_network"
    const val ID_FLUX_COIL_EXPANSION: String = "astd_affix_flux_coil_expansion"
    const val ID_POLARIZED_SHIELD: String = "astd_affix_polarized_shield"
    const val ID_ENGINE_OVERCLOCK: String = "astd_affix_engine_overclock"
    const val ID_DIMENSIONAL_SPECIALTY: String = "astd_affix_dimensional_specialty"
    const val ID_PHASE_COIL_TUNING: String = "astd_affix_phase_coil_tuning"
    const val ID_PHASE_COIL_DETUNING: String = "astd_affix_phase_coil_detuning"

    const val ID_RECURSIVE_TARGETING: String = "astd_affix_recursive_targeting"
    const val ID_REACTIVE_FLUX_ARMOR: String = "astd_affix_reactive_flux_armor"
    const val ID_PSPACE_DIVER: String = "astd_affix_pspace_diver"
    const val ID_ENGINE_FLUX_ISOLATION: String = "astd_affix_engine_flux_isolation"
    const val ID_SWARM_COORDINATION: String = "astd_affix_swarm_coordination"
    const val ID_PLASMA_ARMOR_SHIELD: String = "astd_affix_plasma_armor_shield"

    const val ID_GRID_DEEPENING: String = "astd_affix_grid_deepening"
    const val ID_AGGRESSIVE_SWARM_NETWORK: String = "astd_affix_aggressive_swarm_network"
    const val ID_SINGULARITY_DRIVE: String = "astd_affix_singularity_drive"

    val all: List<AffixDef> = listOf(
        AffixDef(ID_IRONCLAD_PLATING, "S-01", AffixType.S, ID_IRONCLAD_PLATING),
        AffixDef(ID_CRYO_FLUX_NETWORK, "S-02", AffixType.S, ID_CRYO_FLUX_NETWORK),
        AffixDef(ID_FLUX_COIL_EXPANSION, "S-03", AffixType.S, ID_FLUX_COIL_EXPANSION),
        AffixDef(ID_POLARIZED_SHIELD, "S-04", AffixType.S, ID_POLARIZED_SHIELD),
        AffixDef(ID_ENGINE_OVERCLOCK, "S-05", AffixType.S, ID_ENGINE_OVERCLOCK),
        AffixDef(ID_DIMENSIONAL_SPECIALTY, "S-06", AffixType.S, ID_DIMENSIONAL_SPECIALTY),
        AffixDef(ID_PHASE_COIL_TUNING, "S-07", AffixType.S, ID_PHASE_COIL_TUNING, phaseOnly = true),
        AffixDef(ID_PHASE_COIL_DETUNING, "S-08", AffixType.S, ID_PHASE_COIL_DETUNING, phaseOnly = true),
        AffixDef(ID_RECURSIVE_TARGETING, "M-09", AffixType.M, ID_RECURSIVE_TARGETING),
        AffixDef(ID_REACTIVE_FLUX_ARMOR, "M-10", AffixType.M, ID_REACTIVE_FLUX_ARMOR),
        AffixDef(ID_PSPACE_DIVER, "M-11", AffixType.M, ID_PSPACE_DIVER, phaseOnly = true),
        AffixDef(ID_ENGINE_FLUX_ISOLATION, "M-12", AffixType.M, ID_ENGINE_FLUX_ISOLATION),
        AffixDef(ID_SWARM_COORDINATION, "M-13", AffixType.M, ID_SWARM_COORDINATION),
        AffixDef(ID_PLASMA_ARMOR_SHIELD, "M-14", AffixType.M, ID_PLASMA_ARMOR_SHIELD),
        AffixDef(ID_GRID_DEEPENING, "R-15", AffixType.R, ID_GRID_DEEPENING),
        AffixDef(ID_AGGRESSIVE_SWARM_NETWORK, "R-16", AffixType.R, ID_AGGRESSIVE_SWARM_NETWORK),
        AffixDef(ID_SINGULARITY_DRIVE, "R-17", AffixType.R, ID_SINGULARITY_DRIVE),
    )

    val EXCLUSIVE_PAIRS: List<Set<String>> = listOf(
        setOf(ID_CRYO_FLUX_NETWORK, ID_FLUX_COIL_EXPANSION),
        setOf(ID_PHASE_COIL_TUNING, ID_PHASE_COIL_DETUNING),
        setOf(ID_GRID_DEEPENING, ID_FLUX_COIL_EXPANSION),
    )

    data class AffixSlots(val sCount: Int, val mCount: Int, val rSlots: Int)

    fun getById(id: String): AffixDef? = all.firstOrNull { it.id == id }

    fun getByHullModId(hullModId: String): AffixDef? = all.firstOrNull { it.hullModId == hullModId }

    fun validateFixedTable(ids: List<String>): List<String> {
        val violations = ArrayList<String>(4)
        val seen = HashSet<String>(ids.size)
        for (id in ids) {
            val def = getById(id)
            if (def == null) {
                violations += "未知词缀 id：$id"
                continue
            }
            if (!seen.add(id)) {
                violations += "词缀重复：$id"
            }
            if (def.phaseOnly) {
                violations += "相位限定词缀不允许进入固定表：$id"
            }
        }
        for (pair in EXCLUSIVE_PAIRS) {
            val present = pair.filter { it in seen }
            if (present.size > 1) {
                violations += "互斥词缀共存：${present.joinToString(" + ")}"
            }
        }
        return violations
    }

    fun slotCounts(kS: Float, allowR: Boolean): AffixSlots {
        val t = normalizeKS(kS)
        return AffixSlots(
            sCount = roundHalfUp(2f + 2f * t),
            mCount = roundHalfUp(1f + t),
            rSlots = if (allowR) roundHalfUp(1f + t) else 0,
        )
    }

    fun pickAffixes(kS: Float, allowR: Boolean, allowPhase: Boolean, seed: Long): List<AffixDef> {
        val t = normalizeKS(kS)
        val slots = slotCounts(kS, allowR)
        val rnd = Random(seed)
        val picked = ArrayList<AffixDef>(slots.sCount + slots.mCount + slots.rSlots)

        draw(AffixType.S, slots.sCount, slotChance = 1f, allowPhase, rnd, picked)
        draw(AffixType.M, slots.mCount, slotChance = 1f, allowPhase, rnd, picked)
        draw(AffixType.R, slots.rSlots, slotChance = 0.35f + 0.65f * t, allowPhase, rnd, picked)

        return picked
    }

    fun pickAffixes(kS: Float, rCount: Int, allowPhase: Boolean, seed: Long): List<AffixDef> {
        val slots = slotCounts(kS, allowR = false)
        val rnd = Random(seed)
        val picked = ArrayList<AffixDef>(slots.sCount + slots.mCount + rCount.coerceAtLeast(0))

        draw(AffixType.S, slots.sCount, slotChance = 1f, allowPhase, rnd, picked)
        draw(AffixType.M, slots.mCount, slotChance = 1f, allowPhase, rnd, picked)
        draw(AffixType.R, rCount.coerceIn(0, 2), slotChance = 1f, allowPhase, rnd, picked)

        return picked
    }

    fun normalizeKS(kS: Float): Float = (kS.coerceIn(1f, 5f) - 1f) / 4f

    private fun draw(
        type: AffixType,
        count: Int,
        slotChance: Float,
        allowPhase: Boolean,
        rnd: Random,
        picked: MutableList<AffixDef>,
    ) {
        if (count <= 0) return
        val pool = all.filter { it.type == type && (allowPhase || !it.phaseOnly) }
        if (pool.isEmpty()) return
        val candidates = pool.shuffled(rnd)
        var idx = 0
        repeat(count) {
            if (rnd.nextFloat() < slotChance) {
                while (idx < candidates.size && conflictsWithPicked(candidates[idx], picked)) idx++
                if (idx < candidates.size) {
                    picked += candidates[idx]
                    idx++
                }
            }
        }
    }

    private fun conflictsWithPicked(candidate: AffixDef, picked: List<AffixDef>): Boolean {
        if (picked.any { it.id == candidate.id }) return true
        return EXCLUSIVE_PAIRS.any { pair ->
            candidate.id in pair && picked.any { it.id in pair }
        }
    }

    private fun roundHalfUp(v: Float): Int = floor(v + 0.5f).toInt()
}
