package cn.kasuminova.astd.combat.effect.arc.sevenstars

import cn.kasuminova.astd.combat.effect.arc.sevenstars.SevenStarsTargetSelector.collectPdCandidates
import cn.kasuminova.astd.combat.effect.arc.sevenstars.SevenStarsTargetSelector.nearestHostileShip
import cn.kasuminova.astd.combat.effect.arc.sevenstars.SevenStarsTargetSelector.select
import cn.kasuminova.astd.combat.effect.arc.sevenstars.SevenStarsTargetSelector.selectNearestShip
import cn.kasuminova.astd.combat.effect.arc.sevenstars.SevenStarsTargetSelector.sortPdCandidates
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f

object SevenStarsTargetSelector {

    data class Candidate(
        val entity: CombatEntityAPI,
        val hitpoints: Float,
        val distSq: Float,
    )

    fun collectPdCandidates(
        entities: List<CombatEntityAPI>,
        from: Vector2f,
        jumpRange: Float,
        owner: Int,
        inPlay: (CombatEntityAPI) -> Boolean,
    ): List<Candidate> {
        val rangeSq = jumpRange * jumpRange
        val candidates = ArrayList<Candidate>()
        for (entity in entities) {
            val eligible = when (entity) {
                is MissileAPI -> entity.owner != owner && inPlay(entity) && !entity.isExpired
                is ShipAPI -> entity.isFighter && !entity.isHulk && entity.isAlive && entity.owner != owner
                else -> false
            }
            if (!eligible) continue
            val distSq = distSq(from, entity.location)
            if (distSq > rangeSq) continue
            candidates += Candidate(entity, entity.hitpoints.coerceAtLeast(0f), distSq)
        }
        return candidates
    }

    fun sortPdCandidates(candidates: List<Candidate>, aoeDamage: Float): List<Candidate> =
        candidates.sortedWith(
            compareByDescending<Candidate> { it.hitpoints <= aoeDamage }
                .thenBy { it.distSq },
        )

    fun selectNearestShip(entities: List<CombatEntityAPI>, from: Vector2f, owner: Int): ShipAPI? =
        entities
            .asSequence()
            .filterIsInstance<ShipAPI>()
            .filter { !it.isFighter && !it.isHulk && it.isAlive && it.owner != owner }
            .minByOrNull { distSq(from, it.location) }

    fun select(
        engine: CombatEngineAPI,
        from: Vector2f,
        jumpRange: Float,
        owner: Int,
        aoeDamage: Float,
    ): CombatEntityAPI? {
        val entities = ArrayList<CombatEntityAPI>(engine.missiles.size + engine.ships.size)
        entities += engine.missiles
        entities += engine.ships
        val candidates = collectPdCandidates(entities, from, jumpRange, owner) { engine.isEntityInPlay(it) }
        return sortPdCandidates(candidates, aoeDamage).firstOrNull()?.entity
    }

    fun nearestHostileShip(engine: CombatEngineAPI, from: Vector2f, owner: Int): ShipAPI? =
        selectNearestShip(engine.ships, from, owner)

    private fun distSq(a: Vector2f, b: Vector2f): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}
