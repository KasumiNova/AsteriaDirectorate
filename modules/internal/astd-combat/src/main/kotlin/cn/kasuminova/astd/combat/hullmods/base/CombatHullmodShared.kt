package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs

fun aliveEnemies(engine: CombatEngineAPI, ship: ShipAPI): List<ShipAPI> =
    engine.ships.filter {
        it !== ship && it.owner != ship.owner && it.isAlive && !it.isHulk && !it.isFighter && !it.isDrone
    }

fun <T> reconcileTracked(tracked: MutableSet<T>, seen: Collection<T>, unmodify: (T) -> Unit) {
    tracked.removeAll { target ->
        val stale = target !in seen
        if (stale) unmodify(target)
        stale
    }
    tracked += seen
}

fun ShipAPI?.hasHullId(hullId: String): Boolean {
    val s = this ?: return false
    return s.hullSpec?.hullId == hullId || s.hullSpec?.baseHullId == hullId
}

fun signedPercentOf(delta: Float): String {
    val pct = delta * 100f
    val rounded = Math.round(pct * 10f) / 10f
    val body = if (rounded == Math.floor(rounded.toDouble()).toFloat()) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
    return if (pct > 0f) "+$body%" else "$body%"
}

fun unsignedPercentOf(fraction: Float): String =
    signedPercentOf(fraction).removePrefix("+")

fun isPhaseShip(stats: MutableShipStatsAPI): Boolean =
    stats.variant?.hullSpec?.isPhase == true

private const val FRONT_ARMOR_FRACTION = 0.30f
private const val SIDE_ARMOR_FRACTION_MIN = 0.20f
private const val SIDE_ARMOR_FRACTION_MAX = 0.30f
private const val REAR_ARMOR_FRACTION_MIN = 0.10f
private const val REAR_ARMOR_FRACTION_MAX = 0.20f

fun directionalArmorFraction(ship: ShipAPI, hitPoint: Vector2f): Float =
    directionalArmorFraction(
        ship, hitPoint,
        FRONT_ARMOR_FRACTION,
        SIDE_ARMOR_FRACTION_MIN, SIDE_ARMOR_FRACTION_MAX,
        REAR_ARMOR_FRACTION_MIN, REAR_ARMOR_FRACTION_MAX,
    )

fun directionalArmorFraction(
    ship: ShipAPI,
    hitPoint: Vector2f,
    front: Float,
    sideMin: Float,
    sideMax: Float,
    rearMin: Float,
    rearMax: Float,
): Float {
    val hitAngle = Misc.getAngleInDegrees(ship.location, hitPoint)
    val relative = (((hitAngle - ship.facing) % 360f) + 540f) % 360f - 180f
    val offFront = abs(relative)
    return when {
        offFront <= 30f -> front
        offFront <= 90f -> lerpArmor(sideMax, sideMin, (offFront - 30f) / 60f)
        else -> lerpArmor(rearMax, rearMin, (offFront - 90f) / 90f)
    }
}

private fun lerpArmor(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

class DepartedShipCleanupWatcher(
    private val engine: CombatEngineAPI,
    private val ship: ShipAPI,
    private val logTag: String,
    private val cleanup: () -> Unit,
) : BaseEveryFrameCombatPlugin() {

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (!engine.isCombatOver && engine.isEntityInPlay(ship)) return
        cleanup()
        log.info("[ASTD] $logTag：舰船离场或战斗结束（ship=${ship.id}），stat 修饰已收口")
        engine.removePlugin(this)
    }

    private companion object {
        val log = Global.getLogger(DepartedShipCleanupWatcher::class.java)
    }
}
