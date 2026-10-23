package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.lens.system.GravReplicatorTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import com.fs.starfarer.api.util.Misc
import org.boxutil.units.standard.entity.DistortionEntity
import org.boxutil.units.standard.entity.FlareEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GravReplicatorSystemStats : BaseShipSystemScript() {

    override fun apply(
        stats: MutableShipStatsAPI,
        id: String,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ) {
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk) return
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return

        renderJitter(ship, id, effectLevel)
        scanFiredProjectiles(engine, ship)
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        val ship = stats.entity as? ShipAPI ?: return
        ship.isJitterShields = false
    }

    private fun renderJitter(ship: ShipAPI, id: String, effectLevel: Float) {
        val level = effectLevel.coerceIn(0f, 1f)
        if (level <= 0f) return
        ship.isJitterShields = false
        ship.setJitterUnder(id, JITTER_UNDER, level, 25, 0f, 7f)
        ship.setJitter(id, JITTER, 0.6f * level, 3, 0f, 0f)
    }

    // 实体级 customData 惰性为 null 时 getCustomData() 返回一次性空表，首写必须走 setCustomData
    private fun scanFiredProjectiles(engine: CombatEngineAPI, ship: ShipAPI) {
        for (proj in engine.projectiles) {
            if (proj.source !== ship) continue
            if (proj.customData[SCAN_MARK_KEY] == true) continue
            proj.setCustomData(SCAN_MARK_KEY, true)
            if (proj is MissileAPI) continue
            if (proj.elapsed > SCAN_AGE_TOLERANCE) continue
            val weapon = proj.weapon ?: continue
            if (!isReplicableWeapon(weapon)) continue
            val weaponId = weapon.spec?.weaponId
            if (weaponId == null) {
                log.warn("引力空间复制器扫描到无 spec 的能量武器弹体（proj=${proj.projectileSpecId}，ship=${ship.id}），跳过登记")
                continue
            }

            // 初速用 朝向×moveSpeed 重建而不取 proj.velocity：BALLISTIC_AS_BEAM（MovingRay）的 velocity 字段不承载运动学，真实弹道速度 = 发射朝向 × spec 弹速
            val flightSeconds = resolveFlightSeconds(proj, weapon)
            val snapshotDir = Misc.getUnitVectorAtDegreeAngle(proj.facing)
            val snapshotSpeed = proj.moveSpeed
            val snapshotVelocity = Vector2f(snapshotDir.x * snapshotSpeed, snapshotDir.y * snapshotSpeed)
            queueOf(engine, ship).queue.add(
                ReplicaOrder(
                    weapon = weapon,
                    weaponId = weaponId,
                    source = proj,
                    facing = proj.facing,
                    velocity = snapshotVelocity,
                    damageAmount = proj.damageAmount,
                    fluxPerShot = weapon.fluxCostToFire,
                    flightSeconds = flightSeconds,
                    snapshotOrigin = Vector2f(proj.location),
                    endpointSnapshot = flightSeconds?.let { seconds ->
                        extrapolateEndpoint(proj.location, snapshotVelocity, seconds - proj.elapsed)
                    },
                ),
            )
        }
    }

    private fun isReplicableWeapon(weapon: WeaponAPI): Boolean =
        weapon.type == WeaponAPI.WeaponType.ENERGY && !weapon.isBeam && !weapon.isDecorative

    private fun queueOf(engine: CombatEngineAPI, ship: ShipAPI): ReplicaQueueProcessor {
        val existing = ship.customData[QUEUE_KEY] as? ReplicaQueueProcessor
        if (existing != null) return existing
        val created = ReplicaQueueProcessor(ship)
        ship.setCustomData(QUEUE_KEY, created)
        ship.addListener(created)
        return created
    }

    override fun getStatusData(
        index: Int,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ): ShipSystemStatsScript.StatusData? {
        if (index != 0) return null
        // CSV 配置 chargeUp=0 / down=0：IN/OUT 状态帧不存在，状态行只覆盖 ACTIVE
        if (state != ShipSystemStatsScript.State.ACTIVE) return null
        return ShipSystemStatsScript.StatusData(
            I18n[I18n.Categories.MOD, "system.grav_replicator.status.default.active"],
            false,
        )
    }

    private class ReplicaOrder(
        val weapon: WeaponAPI,
        val weaponId: String,
        val source: DamagingProjectileAPI,
        val facing: Float,
        val velocity: Vector2f,
        val damageAmount: Float,
        val fluxPerShot: Float,
        val flightSeconds: Float?,
        val snapshotOrigin: Vector2f,
        val endpointSnapshot: Vector2f?,
    ) {
        var copyIndex = 0
        var elapsed = 0f
    }

    private class ReplicaQueueProcessor(private val ship: ShipAPI) : AdvanceableListener {
        val queue = ArrayList<ReplicaOrder>()

        private val warnedFallbackWeaponIds = HashSet<String>()

        override fun advance(amount: Float) {
            if (!ship.isAlive || ship.isHulk) {
                queue.clear()
                return
            }
            val engine = Global.getCombatEngine() ?: return
            if (engine.isPaused) return

            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val order = iterator.next()
                order.elapsed += amount
                while (order.copyIndex < GravReplicatorTuning.COPY_COUNT &&
                    order.elapsed >= GravReplicatorTuning.copyDueTime(order.copyIndex)
                ) {
                    spawnReplica(engine, ship, order)
                    order.copyIndex++
                }
                if (order.copyIndex >= GravReplicatorTuning.COPY_COUNT) iterator.remove()
            }
        }

        private fun spawnReplica(engine: CombatEngineAPI, ship: ShipAPI, order: ReplicaOrder) {
            val values = GravReplicatorTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
            val weaponSpec = Global.getSettings().getWeaponSpec(order.weaponId)
            if (weaponSpec?.projectileSpec == null) {
                log.warn("引力空间复制器复制时源弹体 spec 已不可解析（weaponId=${order.weaponId}，ship=${ship.id}），本条复制单剩余复制取消")
                order.copyIndex = GravReplicatorTuning.COPY_COUNT
                return
            }
            if (!ship.allWeapons.contains(order.weapon) || order.weapon.isDisabled) {
                log.warn("引力空间复制器复制时源武器实例已失效（weaponId=${order.weaponId}，disabled=${order.weapon.isDisabled}，ship=${ship.id}），本条复制单剩余复制取消")
                order.copyIndex = GravReplicatorTuning.COPY_COUNT
                return
            }

            val point = randomSpawnPoint(ship)
            val facing = resolveReplicaTrajectory(engine, ship, order, point)
            // 第 6 参传零继承速度：spawnProjectile 对该参数按弹种叠加，传非零会让实际速率翻倍
            val spawned = engine.spawnProjectile(
                ship, order.weapon, order.weaponId,
                Vector2f(point), facing, Vector2f(),
            )
            if (spawned is DamagingProjectileAPI) {
                spawned.damageAmount = GravReplicatorTuning.replicaDamage(order.damageAmount, values.damageRatio)
                spawned.setCustomData(SCAN_MARK_KEY, true)
            } else {
                log.warn("引力空间复制器 spawnProjectile 未产出伤害弹体（weaponId=${order.weaponId}，ship=${ship.id}），本发复制无伤害结算")
            }
            ship.fluxTracker.increaseFlux(
                GravReplicatorTuning.replicaFlux(order.fluxPerShot, values.fluxRatio), false,
            )
            spawnReplicaVfx(engine, point, facing)
        }

        private fun randomSpawnPoint(ship: ShipAPI): Vector2f {
            val angle = MathUtils.getRandomNumberInRange(0f, 360f)
            val radius = ship.collisionRadius * MathUtils.getRandomNumberInRange(SPAWN_RING_MIN_FRACTION, SPAWN_RING_MAX_FRACTION)
            val rad = Math.toRadians(angle.toDouble())
            return Vector2f(
                ship.location.x + (cos(rad) * radius).toFloat(),
                ship.location.y + (sin(rad) * radius).toFloat(),
            )
        }

        private fun resolveReplicaTrajectory(
            engine: CombatEngineAPI,
            ship: ShipAPI,
            order: ReplicaOrder,
            point: Vector2f,
        ): Float {
            val speed = order.velocity.length()
            if (speed <= 0f) return order.facing
            val segment = resolveMainSegment(order)
            if (segment == null) {
                if (warnedFallbackWeaponIds.add(order.weaponId)) {
                    log.warn("引力空间复制器无法推导主射弹射程（weaponId=${order.weaponId}，ship=${ship.id}），该武器在本舰的后续复制均收敛到主射弹登记出生点（每舰每武器只告警一次）")
                }
                val fallback = ReplicaConvergenceMath.convergingVelocity(order.snapshotOrigin, point, speed)
                    ?: return order.facing
                return Misc.getAngleInDegrees(fallback)
            }
            val endpoint = raycastHullHit(engine, ship, segment) ?: segment.endpoint
            val velocity = ReplicaConvergenceMath.convergingVelocity(endpoint, point, speed)
                ?: return order.facing
            return Misc.getAngleInDegrees(velocity)
        }

        private class BallisticSegment(val origin: Vector2f, val endpoint: Vector2f)

        private fun resolveMainSegment(order: ReplicaOrder): BallisticSegment? {
            val source = order.source
            if (!source.isExpired) {
                val remainingRange = order.flightSeconds
                    ?.let { (it - source.elapsed).coerceAtLeast(0f) * order.velocity.length() }
                    ?: order.weapon.range.takeIf { it > 0f }
                    ?: return null
                return BallisticSegment(
                    Vector2f(source.location),
                    ReplicaConvergenceMath.terminalPoint(
                        source.location,
                        Misc.getUnitVectorAtDegreeAngle(source.facing),
                        remainingRange,
                    ),
                )
            }
            val snapshot = order.endpointSnapshot
            if (snapshot != null) {
                return BallisticSegment(Vector2f(order.snapshotOrigin), Vector2f(snapshot))
            }
            if (order.velocity.lengthSquared() <= 0f) return null
            val range = order.weapon.range
            if (range <= 0f) return null
            return BallisticSegment(
                Vector2f(order.snapshotOrigin),
                ReplicaConvergenceMath.terminalPoint(order.snapshotOrigin, order.velocity, range),
            )
        }

        private fun raycastHullHit(engine: CombatEngineAPI, ship: ShipAPI, segment: BallisticSegment): Vector2f? {
            val dx = segment.endpoint.x - segment.origin.x
            val dy = segment.endpoint.y - segment.origin.y
            val segLenSq = dx * dx + dy * dy
            if (segLenSq <= 0f) return null
            var bestT = Float.MAX_VALUE
            var bestPoint: Vector2f? = null
            for (target in engine.ships) {
                if (target == null || target === ship) continue
                if (target.owner == ship.owner || target.isFighter || target.isHulk || !target.isAlive) continue
                if (target.isPhased) continue
                val cx = target.location.x - segment.origin.x
                val cy = target.location.y - segment.origin.y
                val r = target.collisionRadius
                val tCenter = (cx * dx + cy * dy) / segLenSq
                val closestSq = cx * cx + cy * cy - tCenter * tCenter * segLenSq
                if (closestSq > r * r) continue
                val tHalfChord = sqrt((r * r - closestSq) / segLenSq)
                if (tCenter + tHalfChord < 0f) continue
                val t = (tCenter - tHalfChord).coerceAtLeast(0f)
                if (t > 1f || t >= bestT) continue
                bestT = t
                bestPoint = Vector2f(segment.origin.x + dx * t, segment.origin.y + dy * t)
            }
            return bestPoint
        }

        private fun spawnReplicaVfx(engine: CombatEngineAPI, point: Vector2f, facing: Float) {
            BoxUtilCombatVfx.ensureReady(engine)
            spawnFlare(engine, point, 0f, smooth = true)
            spawnFlare(engine, point, BoxUtilCombatVfx.normalizeFacingDeg(facing + 90f), smooth = false)

            val distortion = DistortionEntity()
            distortion.setGlobalTimer(0.5f, 0.5f, 0.5f)
            distortion.setInnerIn(0.35f, 0.35f)
            distortion.setInnerFull(0.35f, 0.35f)
            distortion.setInnerOut(0.35f, 0.35f)
            distortion.innerHardness = 0.90f
            distortion.ringHardness = 0.70f
            distortion.setSizeIn(40f, 40f)
            distortion.setSizeFull(60f, 60f)
            distortion.setSizeOut(80f, 80f)
            distortion.powerIn = 0.70f
            distortion.powerFull = 0.50f
            distortion.powerOut = 0f
            distortion.setStateVanilla(point, BoxUtilCombatVfx.normalizeFacingDeg(facing))
            val addState = BoxUtilCombatVfx.addEntity(engine, distortion)
            if (addState != 0) {
                log.warn("[ASTD] 引力空间复制器复制扭曲注册失败（addEntity 返回 $addState），本次扭曲视觉缺席，光斑照常")
                distortion.delete()
            }
        }

        private fun spawnFlare(engine: CombatEngineAPI, point: Vector2f, facing: Float, smooth: Boolean) {
            val flare = FlareEntity()
            flare.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
            flare.setAdditiveBlend()
            if (smooth) {
                flare.setSmooth()
                flare.setSize(FLARE_GLOW_SIZE, FLARE_GLOW_SIZE)
            } else {
                flare.setSharpDisc()
                flare.setSize(FLARE_PILLAR_LENGTH, FLARE_PILLAR_WIDTH)
            }
            flare.autoAspect()
            // 用 Color 重载：BoxUtil 的 setCoreColor(float,float,float,float) 有源码 bug（误写 fringe 槽位）
            flare.setCoreColor(FLARE_CORE)
            flare.setFringeColor(FLARE_FRINGE)
            flare.setGlobalTimer(0.05f, 0.05f, 0.5f)
            flare.setStateVanilla(point, facing)
            val addState = BoxUtilCombatVfx.addEntity(engine, flare)
            if (addState != 0) {
                log.warn("[ASTD] 引力空间复制器复制光斑注册失败（addEntity 返回 $addState，smooth=$smooth），本层缺失其余特效照常")
                flare.delete()
            }
        }
    }

    companion object {
        private val log = Global.getLogger(GravReplicatorSystemStats::class.java)

        private fun resolveFlightSeconds(proj: DamagingProjectileAPI, weapon: WeaponAPI): Float? {
            val maxRange = weapon.range
            val speed = proj.moveSpeed
            if (maxRange <= 0f || speed <= 0f) return null
            return maxRange / speed
        }

        private fun extrapolateEndpoint(location: Vector2f, velocity: Vector2f, remainingSeconds: Float): Vector2f {
            val remaining = remainingSeconds.coerceAtLeast(0f)
            return Vector2f(location.x + velocity.x * remaining, location.y + velocity.y * remaining)
        }

        private const val SCAN_MARK_KEY = "astd_grav_replicator_scanned"
        private const val QUEUE_KEY = "astd_grav_replicator_queue"
        private const val SCAN_AGE_TOLERANCE = 0.1f

        private const val SPAWN_RING_MIN_FRACTION = 0.5f
        private const val SPAWN_RING_MAX_FRACTION = 1.3f

        private const val FLARE_GLOW_SIZE = 42f
        private const val FLARE_PILLAR_LENGTH = 140f
        private const val FLARE_PILLAR_WIDTH = 26f
        private val FLARE_CORE = Color(235, 195, 255)
        private val FLARE_FRINGE = Color(170, 90, 255)

        private val JITTER_UNDER = Color(170, 110, 255, 155)
        private val JITTER = Color(170, 110, 255, 55)
    }
}
