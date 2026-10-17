package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.math.sqrt

/**
 * 引力坍缩炮的相位舰船遮挡判定：原版光束无视相位舰（不可碰撞、直接穿过），
 * GCP 的设定是「相位中的舰体会挡住引力空间束」——每帧沿光束 from→to 扫描相位中的舰船，
 * 取距离光束起点最近的遮挡交点作为实际生效终点（AOE 落点与自绘束体均使用该截断点）。
 *
 * 口径说明：BeamAPI 不提供 setLength/setTo 等截断手段，原版 beam 本体的直接命中伤害
 * 仍会穿过相位舰结算；这里截断的是 GCP 自绘束体与坍缩 AOE 的落点。
 * 相位舰本身处于相位无敌，作为遮挡物时不会受到本次 AOE/穿甲伤害
 * （AOE 结算循环对 isPhased 目标一律跳过，见 GravityCollapseOnHitHandler）。
 *
 * 遮挡物判定不分敌我：任何处于相位中的舰船（含友军）都会挡住光束，唯独开火来源舰除外。
 */
internal object GravityCollapsePhaseBeamBlocker {

    /**
     * 沿线段 from→to 扫描相位中的舰船，返回最近的遮挡交点；无遮挡返回 null。
     * 交点取船体碰撞圆沿线方向的入射点。
     */
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

    /**
     * 线段（起点 [fromX]/[fromY]，方向向量 [dirX]/[dirY]，长度 [length]）与圆（[cx]/[cy], 半径 [r]）
     * 的入射交点到起点的距离；不相交或交点不在线段范围内返回 null。
     * 起点在圆内时返回 0（立即被挡）。
     */
    fun segmentCircleEnterDistance(
        fromX: Float, fromY: Float,
        dirX: Float, dirY: Float,
        length: Float,
        cx: Float, cy: Float, r: Float,
    ): Float? {
        if (length <= 0f || r <= 0f) return null

        // 圆心到起点的向量在方向上的投影（length 的 t 参数空间为实际距离）。
        val invLen = 1f / length
        val ux = dirX * invLen
        val uy = dirY * invLen
        val ox = cx - fromX
        val oy = cy - fromY
        val proj = ox * ux + oy * uy
        // 圆心到直线的最近距离平方。
        val perpSq = ox * ox + oy * oy - proj * proj
        val rSq = r * r
        if (perpSq > rSq) return null

        val half = sqrt((rSq - perpSq).coerceAtLeast(0f))
        val enter = proj - half
        // 起点已在圆内：视为立即遮挡。
        if (enter <= 0f) return if (ox * ox + oy * oy <= rSq) 0f else null
        if (enter > length) return null
        return enter
    }
}
