package cn.kasuminova.astd.combat.effect.lens

import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning
import cn.kasuminova.astd.combat.shipsystems.GravityRiftSystemStats
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.BeamEffectPlugin
import com.fs.starfarer.api.combat.BoundsAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.impl.combat.RiftCascadeMineExplosion
import com.fs.starfarer.api.loading.MissileSpecAPI
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

// 布雷机制复刻原版裂隙洪流发射极 RiftCascadeEffect。
class GravityRiftBeamEffect : BeamEffectPlugin {

    private var arcFrom: Vector2f? = null
    private var prevMineLoc: Vector2f? = null
    private var doneSpawningMines = false
    private var spawned = 0
    private var numToSpawn = 0
    private var untilNextSpawn = 0f
    private var spawnDir = 0f

    private var damageMin = -1f
    private var damageMax = -1f

    override fun advance(amount: Float, engine: CombatEngineAPI, beam: BeamAPI) {
        if (beam.brightness < 1f) return
        if (doneSpawningMines) return

        if (damageMin < 0f) {
            val mothership = beam.source?.aiFlags?.getCustom(ShipwideAIFlags.AIFlags.DRONE_MOTHERSHIP) as? ShipAPI
            if (mothership == null) {
                log.error("引力裂隙光束缺少 DRONE_MOTHERSHIP 旗标（drone=${beam.source?.id}），裂隙伤害按玩家 v2 档解析")
            }
            val values = GravityRiftTuning.resolve(DifficultyTuningImpl, mothership == null || mothership.owner == 0)
            damageMin = values.damageMin
            damageMax = values.damageMax
        }

        if (numToSpawn <= 0 && beam.damageTarget != null) {
            numToSpawn = GravityRiftTuning.riftCount(beam.weapon.range - beam.lengthPrevFrame)
            untilNextSpawn = 0f
        }

        untilNextSpawn -= amount
        if (untilNextSpawn > 0f) return

        val perSpawn = GravityRiftTuning.SPAWN_SPACING
        val ship = beam.source
        var spawnedMine = false
        if (beam.length > beam.weapon.range - 10f) {
            // 未命中任何目标（光束打满射程）：在射线终点布雷（原版同款分支）
            val angle = Misc.getAngleInDegrees(beam.from, beam.rayEndPrevFrame)
            val loc = Misc.getUnitVectorAtDegreeAngle(angle)
            loc.scale(beam.length)
            Vector2f.add(loc, beam.from, loc)
            spawnMine(engine, ship, loc)
            spawnedMine = true
        } else if (beam.damageTarget != null) {
            val arcTo = getNextArcLoc(engine, beam, perSpawn)
            val from = arcFrom
            if (from != null) {
                val dist = Misc.getDistance(from, arcTo)
                if (dist < GravityRiftTuning.SPAWN_SPACING * 2f) {
                    val arc = engine.spawnEmpArcVisual(
                        from, null, arcTo, null, beam.width, beam.fringeColor, Color.white,
                    )
                    arc.coreWidthOverride = maxOf(20f, beam.width * 0.67f)
                }
            }
            spawnMine(engine, ship, arcTo)
            spawnedMine = true
            arcFrom = arcTo
        }

        untilNextSpawn = GravityRiftTuning.SPAWN_INTERVAL
        if (spawnedMine) {
            spawned++
            val mothership = beam.source?.aiFlags?.getCustom(ShipwideAIFlags.AIFlags.DRONE_MOTHERSHIP) as? ShipAPI
            if (mothership != null) {
                val key = GravityRiftSystemStats.TELEMETRY_MINES_KEY + mothership.id
                engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
            }
            if (spawned >= numToSpawn) doneSpawningMines = true
        }
    }

    // 复刻原版 RiftCascadeEffect.getNextArcLoc。
    private fun getNextArcLoc(engine: CombatEngineAPI, beam: BeamAPI, perSpawn: Float): Vector2f {
        var target = beam.damageTarget
        var radiusOverride = -1f
        if (target is ShipAPI && target.parentStation != null && target.stationSlot != null) {
            radiusOverride = Misc.getDistance(beam.rayEndPrevFrame, target.parentStation.location)
            target = target.parentStation
        }

        var spacing = perSpawn
        val from = arcFrom
        if (from == null) {
            val start = Vector2f(beam.rayEndPrevFrame)
            arcFrom = start
            val beamAngle = Misc.getAngleInDegrees(beam.from, beam.rayEndPrevFrame)
            val beamSourceToTarget = Misc.getAngleInDegrees(beam.from, target.location)
            spawnDir = Misc.getClosestTurnDirection(beamAngle, beamSourceToTarget)
            if (spawnDir == 0f) spawnDir = 1f

            val prev = prevMineLoc
            if (prev != null) {
                val dist = Misc.getDistance(start, prev)
                if (dist < spacing) {
                    return walkArc(target, radiusOverride, beam, spacing - dist, start)
                }
            }
            return start
        }
        return walkArc(target, radiusOverride, beam, spacing, from)
    }

    private fun walkArc(
        target: CombatEntityAPI,
        radiusOverride: Float,
        beam: BeamAPI,
        perSpawn: Float,
        from: Vector2f,
    ): Vector2f {
        val targetLoc = target.location
        var targetRadius = target.collisionRadius
        if (radiusOverride >= 0f) targetRadius = radiusOverride

        val hitShield = target.shield != null && target.shield.isWithinArc(beam.rayEndPrevFrame)
        val spacing = if (hitShield) perSpawn * 0.67f else perSpawn

        val prevAngle = Misc.getAngleInDegrees(targetLoc, from)
        var anglePerSegment = 360f * spacing / (6.28f * targetRadius)
        if (anglePerSegment > 90f) anglePerSegment = 90f

        val angle = prevAngle + anglePerSegment * spawnDir
        var arcTo = Misc.getUnitVectorAtDegreeAngle(angle)
        arcTo.scale(targetRadius)
        Vector2f.add(targetLoc, arcTo, arcTo)
        var actualRadius = Global.getSettings().getTargetingRadius(arcTo, target, hitShield)
        if (radiusOverride >= 0f) actualRadius = radiusOverride
        actualRadius += 30f + 50f * Math.random().toFloat()

        arcTo = Misc.getUnitVectorAtDegreeAngle(angle)
        arcTo.scale(actualRadius)
        Vector2f.add(targetLoc, arcTo, arcTo)

        if (target is ShipAPI && !hitShield) {
            val bounds = target.exactBounds
            if (bounds != null) {
                var best: Vector2f? = null
                var bestDist = Float.MAX_VALUE
                for (segment: BoundsAPI.SegmentAPI in bounds.segments) {
                    val test = Misc.getDistance(segment.p1, arcTo)
                    if (test < bestDist) {
                        bestDist = test
                        best = segment.p1
                    }
                }
                val spec = Global.getSettings().getWeaponSpec(MINELAYER_ID)?.projectileSpec
                if (best != null && spec is MissileSpecAPI) {
                    val explosionRadius = spec.behaviorJSON
                        .optJSONObject("explosionSpec")
                        ?.optDouble("coreRadius", 100.0)
                        ?.toFloat()?.times(currentSizeMult()) ?: 100f
                    val dir = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(best, arcTo))
                    dir.scale(explosionRadius * 0.9f)
                    Vector2f.add(best, dir, dir)
                    arcTo = dir
                }
            }
        }
        return arcTo
    }

    // 视觉尺寸与伤害共用同一乘区（原版约定）。
    private fun currentSizeMult(): Float =
        GravityRiftTuning.riftDamageMult(GravityRiftTuning.riftDamage(damageMin, damageMax, spawned, numToSpawn))

    // 对齐原版 RiftCascadeEffect.spawnMine。
    private fun spawnMine(engine: CombatEngineAPI, source: ShipAPI?, mineLoc: Vector2f) {
        val mine = engine.spawnProjectile(
            source, null, MINELAYER_ID, mineLoc, (Math.random() * 360.0).toFloat(), null,
        ) as MissileAPI
        val sizeMult = currentSizeMult()
        mine.setCustomData(RiftCascadeMineExplosion.SIZE_MULT_KEY, sizeMult)
        if (source != null) {
            engine.applyDamageModifiersToSpawnedProjectileWithNullWeapon(
                source, WeaponAPI.WeaponType.ENERGY, false, mine.damage,
            )
        }
        mine.damage.modifier.modifyMult(MINE_SIZE_MULT_MOD_ID, sizeMult)
        mine.velocity.scale(0f)
        mine.fadeOutThenIn(MINE_FADE_IN)
        mine.flightTime = mine.maxFlightTime
        mine.addDamagedAlready(source)
        mine.isNoMineFFConcerns = true
        prevMineLoc = mineLoc
    }

    companion object {
        private val log = Global.getLogger(GravityRiftBeamEffect::class.java)

        private const val MINELAYER_ID = "astd_grav_rift_minelayer"

        /** 地雷伤害乘区修饰句柄（对齐原版 "mine_sizeMult"）。 */
        private const val MINE_SIZE_MULT_MOD_ID = "mine_sizeMult"

        /** 地雷淡入时长（秒，对齐原版 0.05）。 */
        private const val MINE_FADE_IN = 0.05f
    }
}
