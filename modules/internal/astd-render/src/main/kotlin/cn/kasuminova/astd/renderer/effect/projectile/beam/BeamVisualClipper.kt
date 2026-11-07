package cn.kasuminova.astd.renderer.effect.projectile.beam

import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShieldAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lazywizard.lazylib.CollisionUtils
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.sqrt

// EveryFrameWeaponEffectPlugin.advance 先于武器推进（beam.to 重置为满射程）与碰撞裁剪执行，读到的 beam.from/to 为滞后或未裁剪端点；
// 自绘束体统一按本帧发射点、朝向与射程自行求遮挡端点，纯计算，不写回 beam。
object BeamVisualClipper {

    private const val MIN_VISUAL_LENGTH = 1f

    /**
     * @param beamCollisionClass 原版光束的碰撞类别；为 [CollisionClass.NONE] 或
     * [CollisionClass.RAY_FIGHTER]（战机挂载武器）时友军单位不遮挡束体，
     * 与原版碰撞矩阵中该两类光束仅与敌方舰船碰撞的行为一致。
     * @param missilesBlock 导弹是否遮挡束体；原版碰撞矩阵中光束仅与敌方导弹碰撞，
     * 友军导弹任何情况下都不遮挡。为 false 时所有导弹均不遮挡（非 PD 武器的穿透需求）。
     */
    fun resolve(
        engine: CombatEngineAPI,
        weapon: WeaponAPI,
        phasedShipsBlock: Boolean,
        beamCollisionClass: CollisionClass? = null,
        missilesBlock: Boolean = true,
    ): BeamLineUtil.BeamLine {
        val from: Vector2f = weapon.getFirePoint(0)
        val facing = weapon.currAngle
        val friendlyTransparent = beamCollisionClass == CollisionClass.NONE ||
                beamCollisionClass == CollisionClass.RAY_FIGHTER
        var line = clip(engine.ships, weapon.ship, from, facing, weapon.range, phasedShipsBlock, friendlyTransparent)
        var nearest = line.length

        // 原版光束会被敌方导弹拦截（PD 光束的本质用途），自绘端点必须同步裁剪
        if (missilesBlock) {
            val sourceOwner = weapon.ship?.owner
            for (missile in engine.missiles) {
                if (missile.collisionClass == CollisionClass.NONE || missile.isFizzling || missile.didDamage()) continue
                if (sourceOwner != null && missile.owner == sourceOwner) continue
                val loc = missile.location
                val d = rayCircleEnterDistance(
                    from.x, from.y, line.dirUnit.x, line.dirUnit.y, nearest, loc.x, loc.y, missile.collisionRadius,
                ) ?: continue
                if (d < nearest) nearest = d
            }
        }

        // 原版碰撞矩阵中 RAY/RAY_FIGHTER 对 ASTEROID 均为 ANY，陨石/残骸遮挡束体；NONE 类光束除外
        if (beamCollisionClass != CollisionClass.NONE) {
            for (asteroid in engine.asteroids) {
                if (asteroid.collisionClass == CollisionClass.NONE) continue
                val loc = asteroid.location
                val d = rayCircleEnterDistance(
                    from.x, from.y, line.dirUnit.x, line.dirUnit.y, nearest, loc.x, loc.y, asteroid.collisionRadius,
                ) ?: continue
                if (d < nearest) nearest = d
            }
        }

        if (nearest < line.length) {
            line = BeamLineUtil.fromRay(from, facing, nearest.coerceAtLeast(MIN_VISUAL_LENGTH))
        }
        return line
    }

    // phasedShipsBlock：原版光束穿过相位舰；为 true 时相位舰按舰体边界遮挡自绘束体。
    // friendlyTransparent：NONE/RAY_FIGHTER 类光束（战机武器）穿透友军；为 true 时友军舰船不遮挡。
    fun clip(
        ships: Iterable<ShipAPI>,
        source: ShipAPI?,
        from: Vector2f,
        facing: Float,
        range: Float,
        phasedShipsBlock: Boolean,
        friendlyTransparent: Boolean = false,
    ): BeamLineUtil.BeamLine {
        val ray = BeamLineUtil.fromRay(from, facing, range.coerceAtLeast(MIN_VISUAL_LENGTH))
        var nearest = ray.length
        for (ship in ships) {
            if (!blocksBeam(ship, source, phasedShipsBlock, friendlyTransparent)) continue
            val d = hitDistance(ship, ray, nearest) ?: continue
            if (d < nearest) nearest = d
        }
        if (nearest >= ray.length) return ray
        return BeamLineUtil.fromRay(ray.from, facing, nearest.coerceAtLeast(MIN_VISUAL_LENGTH))
    }

    fun rayCircleEnterDistance(
        fromX: Float, fromY: Float,
        dirUnitX: Float, dirUnitY: Float,
        length: Float,
        cx: Float, cy: Float, r: Float,
    ): Float? {
        if (length <= 0f || r <= 0f) return null
        val ox = cx - fromX
        val oy = cy - fromY
        val centerDistSq = ox * ox + oy * oy
        val rSq = r * r
        if (centerDistSq <= rSq) return 0f

        val proj = ox * dirUnitX + oy * dirUnitY
        if (proj <= 0f) return null
        val perpSq = centerDistSq - proj * proj
        if (perpSq > rSq) return null

        val enter = proj - sqrt((rSq - perpSq).coerceAtLeast(0f))
        return if (enter > length) null else enter
    }

    private fun blocksBeam(ship: ShipAPI, source: ShipAPI?, phasedShipsBlock: Boolean, friendlyTransparent: Boolean): Boolean {
        if (ship === source) return false
        if (ship.isPhased) return phasedShipsBlock
        if (ship.collisionClass == CollisionClass.NONE) return false
        if (source != null && ship.owner == source.owner) {
            // 原版光束不命中己方战机；NONE 类光束（战机挂载武器）穿透所有友军。
            if (ship.isFighter || friendlyTransparent) return false
        }
        return true
    }

    private fun hitDistance(ship: ShipAPI, ray: BeamLineUtil.BeamLine, limit: Float): Float? {
        val shield = shieldEnterDistance(ship, ray, limit)
        val loc = ship.location
        if (!CollisionUtils.getCollides(ray.from, pointAt(ray, limit), loc, ship.collisionRadius)) return shield
        val hull = hullEnterDistance(ship, ray, limit) ?: return shield
        return if (shield == null || hull < shield) hull else shield
    }

    private fun shieldEnterDistance(ship: ShipAPI, ray: BeamLineUtil.BeamLine, limit: Float): Float? {
        val shield = ship.shield ?: return null
        if (shield.type == ShieldAPI.ShieldType.NONE || shield.type == ShieldAPI.ShieldType.PHASE) return null
        if (!shield.isOn) return null
        val c = shield.location
        val r = shield.radius
        // 发射点位于护盾圆内时光束从内侧穿出，护盾不遮挡。
        if (MathUtils.getDistanceSquared(ray.from, c) <= r * r) return null
        val d = rayCircleEnterDistance(ray.from.x, ray.from.y, ray.dirUnit.x, ray.dirUnit.y, limit, c.x, c.y, r)
            ?: return null
        return if (shield.isWithinArc(pointAt(ray, d))) d else null
    }

    private fun hullEnterDistance(ship: ShipAPI, ray: BeamLineUtil.BeamLine, limit: Float): Float? {
        val loc = ship.location
        val bounds = ship.exactBounds ?: return rayCircleEnterDistance(
            ray.from.x, ray.from.y, ray.dirUnit.x, ray.dirUnit.y, limit, loc.x, loc.y, ship.collisionRadius,
        )
        bounds.update(loc, ship.facing)
        val hit = CollisionUtils.getCollisionPoint(ray.from, pointAt(ray, limit), ship) ?: return null
        return MathUtils.getDistance(ray.from, hit)
    }

    private fun pointAt(ray: BeamLineUtil.BeamLine, dist: Float): Vector2f =
        Vector2f(ray.from.x + ray.dirUnit.x * dist, ray.from.y + ray.dirUnit.y * dist)
}
