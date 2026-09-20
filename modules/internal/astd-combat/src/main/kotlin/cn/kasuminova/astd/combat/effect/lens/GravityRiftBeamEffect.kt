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

/**
 * 引力裂隙光束的 BeamEffect（astd_grav_rift_beam.wpn 的 beamEffect 字段）
 * （purple/20-production.md §2，2026-09-20 二轮重做）。
 *
 * 布雷机制**直接复刻原版裂隙洪流发射极**（[com.fs.starfarer.api.impl.combat.RiftCascadeEffect]）：
 * 光束满亮度且命中目标期间，按 [GravityRiftTuning.SPAWN_INTERVAL]（0.1s）沿目标周边弧线
 * 布一枚裂隙雷（弧线行走 [getNextArcLoc]：命中护盾时间距 ×0.67、bounds 吸附、
 * 布点间距 [GravityRiftTuning.SPAWN_SPACING]），数量 = (光束射程 − 上帧命中长度)/200 + 1、
 * 上限 [GravityRiftTuning.MAX_RIFTS]。
 */
class GravityRiftBeamEffect : BeamEffectPlugin {

    private var arcFrom: Vector2f? = null
    private var prevMineLoc: Vector2f? = null
    private var doneSpawningMines = false
    private var spawned = 0
    private var numToSpawn = 0
    private var untilNextSpawn = 0f
    private var spawnDir = 0f

    /** 难度伤害区间（首帧惰性解析，取 drone 母舰；玩家舰固定 v2）。 */
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

    /** 弧线行走取下一个布雷点（复刻原版 RiftCascadeEffect.getNextArcLoc，仅换布雷器 id 与 sizeMult 来源）。 */
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
                    // 与上一枚间距不足：沿弧线补足剩余间距后继续行走
                    return walkArc(target, radiusOverride, beam, spacing - dist, start)
                }
            }
            return start
        }
        return walkArc(target, radiusOverride, beam, spacing, from)
    }

    /** 自 [from] 沿目标周边弧线行走 [perSpawn] 间距后的落点（含护盾减速、bounds 吸附）。 */
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

    /** 当前序位裂隙的伤害乘区（视觉尺寸与伤害共用，原版同一约定）。 */
    private fun currentSizeMult(): Float =
        GravityRiftTuning.riftDamageMult(GravityRiftTuning.riftDamage(damageMin, damageMax, spawned, numToSpawn))

    /** 裂隙地雷生成（对齐原版 RiftCascadeEffect.spawnMine 七步）。 */
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
