package cn.kasuminova.astd.combat.hullmods.arc

import com.fs.starfarer.api.combat.ShipAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

internal object ASTDArcAuraUtil {
    const val ARC_JET_PASSIVE_FULL_RANGE = 1000f
    const val ARC_JET_PASSIVE_MAX_RANGE = 2000f
    const val ARC_JET_SYSTEM_FULL_RANGE = 750f
    const val ARC_JET_SYSTEM_MAX_RANGE = 1500f
    const val RADIATION_BELT_NETWORK_RANGE = 1200f
    const val EDGE_SCALE = 0.25f

    data class CandidateSummary(
        val id: String,
        val owner: Int,
        val location: Vector2f,
        val hullSize: ShipAPI.HullSize,
        val isHulk: Boolean = false,
        val isFighter: Boolean = false,
        val isDrone: Boolean = false,
        val scoreBias: Float = 0f,
    )

    fun selectTargets(
        sourceOwner: Int,
        sourceLocation: Vector2f,
        maxRange: Float,
        maxCount: Int,
        eligibleHullSizes: Set<ShipAPI.HullSize>,
        candidates: Iterable<CandidateSummary>,
    ): List<CandidateSummary> {
        if (maxCount <= 0 || maxRange <= 0f || eligibleHullSizes.isEmpty()) return emptyList()
        val maxRangeSq = maxRange * maxRange
        return candidates.asSequence()
            .filter { it.owner == sourceOwner }
            .filter { !it.isHulk && !it.isFighter && !it.isDrone }
            .filter { it.hullSize in eligibleHullSizes }
            .map { it to distanceSquared(sourceLocation, it.location) }
            .filter { (_, distSq) -> distSq <= maxRangeSq }
            .sortedWith(
                compareBy<Pair<CandidateSummary, Float>> { (summary, distSq) ->
                    (distSq / maxRangeSq) - summary.scoreBias
                }
                    .thenBy { it.first.id },
            )
            .take(maxCount)
            .map { it.first }
            .toList()
    }

    fun distanceFalloff(distance: Float, fullRange: Float, maxRange: Float, edgeScale: Float = EDGE_SCALE): Float {
        if (distance < 0f || fullRange < 0f || maxRange <= 0f || maxRange < fullRange) return 0f
        if (distance > maxRange) return 0f
        if (distance <= fullRange) return 1f
        val span = (maxRange - fullRange).coerceAtLeast(0.0001f)
        val t = ((distance - fullRange) / span).coerceIn(0f, 1f)
        return 1f + (edgeScale.coerceIn(0f, 1f) - 1f) * t
    }

    fun arcJetPassiveFalloff(distance: Float): Float =
        distanceFalloff(distance, ARC_JET_PASSIVE_FULL_RANGE, ARC_JET_PASSIVE_MAX_RANGE, EDGE_SCALE)

    fun arcJetSystemFalloff(distance: Float): Float =
        distanceFalloff(distance, ARC_JET_SYSTEM_FULL_RANGE, ARC_JET_SYSTEM_MAX_RANGE, EDGE_SCALE)

    fun radiationBeltNetworkFalloff(distance: Float): Float =
        if (distance in 0f..RADIATION_BELT_NETWORK_RANGE) 1f else 0f

    fun summaryFor(ship: ShipAPI): CandidateSummary = CandidateSummary(
        id = stableShipId(ship),
        owner = ship.owner,
        location = Vector2f(ship.location),
        hullSize = ship.hullSize,
        isHulk = ship.isHulk,
        isFighter = ship.isFighter,
        isDrone = ship.isDrone,
    )

    fun isArcProductionHull(ship: ShipAPI?, hullId: String): Boolean {
        val spec = ship?.hullSpec ?: return false
        val exact = try { spec.hullId } catch (_: Throwable) { null }
        val base = try { spec.baseHullId } catch (_: Throwable) { null }
        return exact == hullId || base == hullId
    }

    fun isASTDHull(ship: ShipAPI?): Boolean {
        val spec = ship?.hullSpec ?: return false
        val exact = try { spec.hullId } catch (_: Throwable) { null }
        val base = try { spec.baseHullId } catch (_: Throwable) { null }
        return exact?.startsWith("astd_") == true || base?.startsWith("astd_") == true
    }

    fun isArcProductionHull(ship: ShipAPI?): Boolean =
        isArcProductionHull(ship, ASTDArcProductionShipIds.HULL_ARC_JET) ||
            isArcProductionHull(ship, ASTDArcProductionShipIds.HULL_PLASMA_ARCH) ||
            isArcProductionHull(ship, ASTDArcProductionShipIds.HULL_RADIATION_BELT)

    private fun stableShipId(ship: ShipAPI): String {
        val variantId = try { ship.variant?.hullVariantId } catch (_: Throwable) { null }
        val hullId = try { ship.hullSpec?.hullId } catch (_: Throwable) { null }
        return "$variantId:$hullId:${System.identityHashCode(ship)}"
    }

    private fun distanceSquared(a: Vector2f, b: Vector2f): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    fun distance(a: Vector2f, b: Vector2f): Float = MathUtils.getDistance(a, b)
}
