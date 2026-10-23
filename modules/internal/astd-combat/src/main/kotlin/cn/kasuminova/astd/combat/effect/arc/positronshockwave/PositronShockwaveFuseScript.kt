package cn.kasuminova.astd.combat.effect.arc.positronshockwave

import cn.kasuminova.astd.api.combat.ConeDamageAdjust
import cn.kasuminova.astd.api.combat.ConeImpactSpec
import cn.kasuminova.astd.api.combat.ConeTargetFilter
import cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveFuseScript.Companion.detonate
import cn.kasuminova.astd.combat.effect.base.SicWeaponUpgradeFlags
import cn.kasuminova.astd.impl.combat.ConeImpactHandler
import cn.kasuminova.astd.impl.render.ConeImpactVfx
import cn.kasuminova.astd.impl.render.ConeImpactVfxSpec
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.atan2

class PositronShockwaveFuseScript(
    private val projectile: DamagingProjectileAPI,
    private val source: ShipAPI?,
    private val spec: PositronShockwaveDifficulty.Resolved,
    private val maxRange: Float,
    private val spawnLoc: Vector2f,
) : BaseEveryFrameCombatPlugin() {
    private var done = false
    private var zeroVelocityWarned = false

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (done) return
        val engine = Global.getCombatEngine() ?: run {
            done = true
            return
        }
        if (engine.isPaused) return
        if (!engine.isEntityInPlay(projectile)) {
            done = true
            return
        }

        val loc = projectile.location
        val vel = projectile.velocity
        if (vel.lengthSquared() < ZERO_VELOCITY_THRESHOLD) {
            if (!zeroVelocityWarned) {
                zeroVelocityWarned = true
                log.warn("正电子冲击波弹体速度近零（|v|²=${vel.lengthSquared()}），本帧跳过引信判定（方向矢量无意义，不自爆不静默）")
            }
            return
        }
        val dir = Vector2f(vel)
        dir.normalise()
        val fuseOwner = source?.owner ?: projectile.owner

        if (engine.customData[TELEMETRY_FIRST_FRAME_LOGGED] != true) {
            engine.customData[TELEMETRY_FIRST_FRAME_LOGGED] = true
            log.info(
                "正电子引信脚本首帧运行：elapsed=${projectile.elapsed}, moveSpeed=${projectile.moveSpeed}, " +
                        "maxRange=$maxRange, fading=${projectile.isFading}, halfAngle=${spec.halfAngleDeg}, " +
                        "coneRange=${spec.range}, damage=${spec.damage}",
            )
        }

        val fuseRange = spec.range * PositronShockwaveDifficulty.FUSE_RANGE_RATIO
        var detonate = CombatUtils.getEntitiesWithinRange(loc, fuseRange).any { e ->
            e !== projectile &&
                    PositronShockwaveDifficulty.isFuseTarget(e, fuseOwner) &&
                    ConeImpactHandler.isInsideCone(loc, dir.x, dir.y, spec.halfAngleDeg, fuseRange, e.location, e.collisionRadius)
        }

        // 原版弹体寿命钳制为 range/projSpeed，淡出与满射程同帧发生，满射程判定必须先执行。
        var maxRangeDetonate = false
        if (!detonate) {
            maxRangeDetonate = PositronShockwaveDifficulty.reachedMaxRange(
                projectile.elapsed,
                projectile.moveSpeed,
                maxRange,
            )
            detonate = maxRangeDetonate
        }

        if (!detonate && projectile.isFading) {
            log.warn(
                "正电子冲击波弹体淡出先于引信触发（elapsed=${projectile.elapsed}, " +
                        "moveSpeed=${projectile.moveSpeed}, maxRange=$maxRange），按满射程路径就地引爆",
            )
            detonateAndFinish(engine, loc, dir, fuseOwner, fuse = false)
            return
        }

        if (detonate) detonateAndFinish(engine, loc, dir, fuseOwner, fuse = !maxRangeDetonate)
    }

    private fun detonateAndFinish(
        engine: CombatEngineAPI,
        loc: Vector2f,
        dir: Vector2f,
        fuseOwner: Int,
        fuse: Boolean,
    ) {
        detonate(
            engine, projectile, loc, dir, source, spec, fuseOwner,
            detonateTelemetryKey = if (fuse) TELEMETRY_DETONATE_FUSE else TELEMETRY_DETONATE_MAX_RANGE,
            spawnLoc = spawnLoc,
        )
        done = true
    }

    companion object {
        private val log = Global.getLogger(PositronShockwaveFuseScript::class.java)

        fun detonate(
            engine: CombatEngineAPI,
            projectile: DamagingProjectileAPI,
            loc: Vector2f,
            dir: Vector2f,
            source: ShipAPI?,
            spec: PositronShockwaveDifficulty.Resolved,
            fuseOwner: Int,
            detonateTelemetryKey: String,
            spawnLoc: Vector2f? = null,
        ) {
            // 引信脚本与撞舰 OnHit 可能同帧竞态，按弹体一次性 claim 只爆一次。
            val claimKey = TELEMETRY_DETONATE_CLAIM_PREFIX + System.identityHashCode(projectile)
            if (engine.customData[claimKey] == true) return
            engine.customData[claimKey] = true
            var dealtDamage = spec.damage
            val targets = ConeImpactHandler.resolve(
                engine,
                ConeImpactSpec(
                    origin = loc,
                    direction = dir,
                    halfAngleDeg = spec.halfAngleDeg,
                    range = spec.range,
                    damage = spec.damage,
                    damageType = DamageType.FRAGMENTATION,
                    empDamage = 0f,
                    source = source,
                    owner = fuseOwner,
                    filter = ConeTargetFilter { e -> e.owner != fuseOwner },
                    hitShips = true,
                    hitFighters = true,
                    hitMissiles = true,
                    damageAdjust = ConeDamageAdjust { hits ->
                        val swarm = hits.count { e ->
                            e is MissileAPI || (e as? ShipAPI)?.let { it.isFighter || it.isDrone } == true
                        }
                        (spec.damage * (1f + swarm * spec.densityBonus)).also { dealtDamage = it }
                    },
                ),
            )
            val fighterExtraHe = PositronShockwaveDifficulty.fighterExtraHeDamage(
                dealtDamage, SicWeaponUpgradeFlags.isArmamentUpgradeActive(source),
            )
            if (fighterExtraHe > 0f) {
                for (target in targets) {
                    if (target !is ShipAPI || !target.isFighter || target.isHulk) continue
                    engine.applyDamage(
                        target, loc, fighterExtraHe, DamageType.HIGH_EXPLOSIVE, 0f, true, false, source, true,
                    )
                }
            }
            bumpTelemetry(engine, detonateTelemetryKey)
            if (spawnLoc != null) {
                engine.customData[TELEMETRY_LAST_DETONATE_DIST] = Misc.getDistance(spawnLoc, loc)
            }
            for (target in targets) {
                when {
                    target is MissileAPI -> bumpTelemetry(engine, TELEMETRY_CONE_MISSILE_HITS)
                    target is ShipAPI && target.isFighter -> bumpTelemetry(engine, TELEMETRY_CONE_FIGHTER_HITS)
                    target is ShipAPI -> bumpTelemetry(engine, TELEMETRY_CONE_SHIP_HITS)
                }
            }

            ConeImpactVfx.spawn(
                engine,
                ConeImpactVfxSpec(
                    origin = Vector2f(loc),
                    facingDeg = Math.toDegrees(atan2(dir.y.toDouble(), dir.x.toDouble())).toFloat(),
                    halfAngleDeg = spec.halfAngleDeg,
                    length = spec.range,
                    coreColor = CONE_CORE_COLOR,
                    fringeColor = CONE_FRINGE_COLOR,
                ),
            )
            bumpTelemetry(engine, TELEMETRY_CONE_VFX)
            engine.spawnExplosion(loc, ZERO, FLASH_COLOR, spec.range * 0.25f, 0.15f)
            Global.getSoundPlayer().playSound("explosion_flak", 1f, 0.9f, loc, ZERO)

            if (Global.getSettings().isDevMode && source?.owner == 0 && targets.isNotEmpty()) {
                engine.addFloatingText(loc, "近炸命中 ×${targets.size}", 16f, FLOATY_COLOR, source, 0f, 0f)
                bumpTelemetry(engine, TELEMETRY_FLOATY)
            }

            engine.removeEntity(projectile)
        }

        private const val TELEMETRY_DETONATE_CLAIM_PREFIX = "astd_positron_detonate_claim:"

        private fun bumpTelemetry(engine: CombatEngineAPI, key: String) {
            engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
        }

        private const val ZERO_VELOCITY_THRESHOLD = 1e-3f

        private val ZERO = Vector2f()

        private val CONE_CORE_COLOR = Color(165, 210, 255)

        private val CONE_FRINGE_COLOR = Color(70, 130, 255)

        private val FLASH_COLOR = Color(140, 200, 255, 90)

        private val FLOATY_COLOR = Color(180, 220, 255)

        const val TELEMETRY_DETONATE_FUSE = "astd_positron_detonate_fuse"
        const val TELEMETRY_DETONATE_MAX_RANGE = "astd_positron_detonate_max_range"
        const val TELEMETRY_DETONATE_IMPACT = "astd_positron_detonate_impact"
        const val TELEMETRY_CONE_VFX = "astd_positron_cone_vfx"
        const val TELEMETRY_CONE_SHIP_HITS = "astd_positron_cone_ship_hits"
        const val TELEMETRY_CONE_MISSILE_HITS = "astd_positron_cone_missile_hits"
        const val TELEMETRY_CONE_FIGHTER_HITS = "astd_positron_cone_fighter_hits"
        const val TELEMETRY_FLOATY = "astd_positron_floaty"
        const val TELEMETRY_LAST_DETONATE_DIST = "astd_positron_last_detonate_dist"
        const val TELEMETRY_FIRST_FRAME_LOGGED = "astd_positron_first_frame_logged"
        const val TELEMETRY_ONFIRE_LOGGED = "astd_positron_onfire_logged"

        fun telemetryCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

        fun telemetryFloat(engine: CombatEngineAPI, key: String): Float = engine.customData[key] as? Float ?: -1f
    }
}
