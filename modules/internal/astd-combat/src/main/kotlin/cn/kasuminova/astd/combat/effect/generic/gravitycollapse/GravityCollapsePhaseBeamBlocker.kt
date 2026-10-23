package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.math.sqrt

// 原版光束无视相位舰（不可碰撞、直接穿过），且 BeamAPI 不提供截断手段；
// 本对象只为自绘束体与坍缩 AOE 计算遮挡截断点，原版 beam 本体的直接命中仍会穿过相位舰结算。
internal object GravityCollapsePhaseBeamBlocker {

    fun findNearestBlockPoint(
        engine: CombatEngineAPI,
        from: Vector2f,
        to: Vector2f,
        source: ShipAPI?,
    ): Vector2f? {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = sqrt(dx * dx + dy * dy)
        if (length <= 0.01f) return null

        var nearestT: Float? = null
        for (ship in engine.ships) {
            if (source != null && ship === source) continue
            if (!ship.isPhased) continue

            val t = segmentCircleEnterDistance(
                from.x, from.y, dx, dy, length,
                ship.location.x, ship.location.y, ship.collisionRadius,
            ) ?: continue
            if (nearestT == null || t < nearestT) nearestT = t
        }

        return nearestT?.let { Vector2f(from.x + dx * it / length, from.y + dy * it / length) }
    }

    fun segmentCircleEnterDistance(
        fromX: Float, fromY: Float,
        dirX: Float, dirY: Float,
        length: Float,
        cx: Float, cy: Float, r: Float,
    ): Float? {
        if (length <= 0f || r <= 0f) return null

        val invLen = 1f / length
        val ux = dirX * invLen
        val uy = dirY * invLen
        val ox = cx - fromX
        val oy = cy - fromY
        val proj = ox * ux + oy * uy
        val perpSq = ox * ox + oy * oy - proj * proj
        val rSq = r * r
        if (perpSq > rSq) return null

        val half = sqrt((rSq - perpSq).coerceAtLeast(0f))
        val enter = proj - half
        if (enter <= 0f) return if (ox * ox + oy * oy <= rSq) 0f else null
        if (enter > length) return null
        return enter
    }
}
