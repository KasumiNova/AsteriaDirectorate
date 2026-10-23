package cn.kasuminova.astd.combat.effect.arc.piercinglance

import cn.kasuminova.astd.api.combat.ConeImpactSpec
import cn.kasuminova.astd.api.combat.ConeTargetFilter
import cn.kasuminova.astd.combat.effect.arc.piercinglance.PiercingLanceConeStrike.buildConeSpec
import cn.kasuminova.astd.combat.effect.arc.piercinglance.PiercingLanceConeStrike.resolve
import cn.kasuminova.astd.impl.combat.ConeImpactHandler
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.sqrt

object PiercingLanceConeStrike {
    private val log = Global.getLogger(PiercingLanceConeStrike::class.java)

    private const val ZERO_VELOCITY_THRESHOLD = 1e-3f

    private val FRAG_TEXT_COLOR = Color(170, 215, 255)

    fun buildConeSpec(
        projectile: DamagingProjectileAPI,
        directTarget: CombatEntityAPI?,
        hitPoint: Vector2f,
    ): ConeImpactSpec? {
        val direction = resolveHitDirection(projectile, directTarget) ?: return null

        val source = projectile.weapon?.ship
        if (source == null) {
            log.debug("贯星之矛弹体无来源舰船（来源船同帧被毁？），难度取值按敌方口径，owner 回退弹体归属 ${projectile.owner}")
        }

        val arcDeg = PiercingLanceDifficulty.valueFor(source, PiercingLanceDifficulty.CONE_ARC)
        val range = PiercingLanceDifficulty.valueFor(source, PiercingLanceDifficulty.CONE_RANGE)
        val dmgMult = PiercingLanceDifficulty.valueFor(source, PiercingLanceDifficulty.CONE_DAMAGE)

        return ConeImpactSpec(
            origin = hitPoint,
            direction = direction,
            halfAngleDeg = arcDeg / 2f,
            range = range,
            damage = PiercingLanceDifficulty.PANEL_DAMAGE * dmgMult,
            damageType = DamageType.FRAGMENTATION,
            empDamage = PiercingLanceDifficulty.PANEL_DAMAGE * dmgMult,
            source = source,
            owner = source?.owner ?: projectile.owner,
            // 命中本体豁免：主伤害已由引擎结算。
            filter = ConeTargetFilter { it !== directTarget },
            hitShips = true,
            hitFighters = true,
            hitMissiles = true,
        )
    }

    fun resolve(engine: CombatEngineAPI, spec: ConeImpactSpec, directTarget: CombatEntityAPI?) {
        val hits = ConeImpactHandler.resolve(engine, spec)
        bumpTelemetry(engine, TELEMETRY_RESOLVE)
        engine.customData[TELEMETRY_LAST_HALF_ANGLE] = spec.halfAngleDeg
        engine.customData[TELEMETRY_LAST_RANGE] = spec.range
        engine.customData[TELEMETRY_LAST_DAMAGE] = spec.damage
        engine.customData[TELEMETRY_LAST_CONE_HITS] = hits.size

        if (directTarget != null && hits.any { it === directTarget }) {
            log.error("贯星之矛锥面结算命中本体豁免契约被破坏（target=$directTarget），锥面伤害重复落于本体")
            bumpTelemetry(engine, TELEMETRY_DIRECT_EXEMPT_VIOLATION)
        }

        for (target in hits) {
            engine.addFloatingDamageText(
                ConeImpactHandler.surfacePoint(spec.origin, target.location, target.collisionRadius),
                spec.damage,
                FRAG_TEXT_COLOR,
                target as? ShipAPI,
                spec.source,
            )
            bumpTelemetry(engine, TELEMETRY_CONE_HITS)
            bumpTelemetry(engine, TELEMETRY_FLOATY)
        }

        PiercingLanceVfx.spawnImpact(engine, spec)
    }

    private fun resolveHitDirection(projectile: DamagingProjectileAPI, directTarget: CombatEntityAPI?): Vector2f? {
        val velocity = projectile.velocity
        if (velocity != null) {
            val lenSq = velocity.x * velocity.x + velocity.y * velocity.y
            if (!lenSq.isNaN() && lenSq >= ZERO_VELOCITY_THRESHOLD) {
                val len = sqrt(lenSq)
                return Vector2f(velocity.x / len, velocity.y / len)
            }
        }

        // 命中瞬间弹体速度可能被引擎改写为零向量，回退到 source→target 方向。
        val source = projectile.weapon?.ship
        if (source != null && directTarget != null) {
            val dx = directTarget.location.x - source.location.x
            val dy = directTarget.location.y - source.location.y
            val lenSq = dx * dx + dy * dy
            if (!lenSq.isNaN() && lenSq >= ZERO_VELOCITY_THRESHOLD) {
                val len = sqrt(lenSq)
                return Vector2f(dx / len, dy / len)
            }
        }

        log.warn("贯星之矛命中矢量不可得（弹体速度近零且 source→target 方向不可用），放弃本次锥面结算（命中本体伤害已由引擎结算）")
        return null
    }

    private fun bumpTelemetry(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }

    const val TELEMETRY_RESOLVE = "astd_piercing_lance_resolve"
    const val TELEMETRY_CONE_HITS = "astd_piercing_lance_cone_hits"
    const val TELEMETRY_FLOATY = "astd_piercing_lance_floaty"
    const val TELEMETRY_LAST_CONE_HITS = "astd_piercing_lance_last_cone_hits"
    const val TELEMETRY_LAST_HALF_ANGLE = "astd_piercing_lance_last_half_angle"
    const val TELEMETRY_LAST_RANGE = "astd_piercing_lance_last_range"
    const val TELEMETRY_LAST_DAMAGE = "astd_piercing_lance_last_damage"
    const val TELEMETRY_DIRECT_EXEMPT_VIOLATION = "astd_piercing_lance_direct_exempt_violation"

    fun telemetryCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

    fun telemetryFloat(engine: CombatEngineAPI, key: String): Float = engine.customData[key] as? Float ?: -1f
}
