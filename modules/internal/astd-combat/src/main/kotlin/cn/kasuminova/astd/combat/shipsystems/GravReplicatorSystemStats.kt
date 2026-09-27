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

/**
 * 引力空间复制器（Gravity Space Replicator，系统 id：astd_grav_replicator）——舜华级（ZW-101）舰船系统。
 *
 * 持续 2s、冷却 12s（CSV 口径由注册侧填写）。机制：
 *
 * 1. **激活辐能**：激活首帧（IN/ACTIVE 首个 apply 帧，覆盖 chargeUp=0 无 IN 帧的口径）
 *    产生舰船基础最大辐能容量 [GravReplicatorTuning.ACTIVATION_FLUX_FRACTION] 的软辐能
 *    （engine.customData 闩，unapply 清除，保证下次激活可再触发）。
 * 2. **弹道复制**：系统开启期间（IN/ACTIVE/OUT，apply 被调用的全部窗口）扫描
 *    engine.projectiles，本舰发射的能量武器实弹（武器 type=ENERGY、非光束、非装饰；
 *    导弹与实弹武器天然排除）逐发登记一条复制单（[ReplicaOrder]，记原朝向、初速、
 *    面板伤害与武器单发辐能快照）；每个弹体只登记一次（弹体 customData [SCAN_MARK_KEY]，
 *    复制体出生即带标记防二次复制）。已在飞行中的弹体（首次观测时 elapsed 超过
 *    [SCAN_AGE_TOLERANCE]）视为系统开启前发射，只标记不登记。
 * 3. **复制调度**：[ReplicaQueueProcessor]（挂在舰船上的 AdvanceableListener，逐舰一份，
 *    存于 ship.customData [QUEUE_KEY]）逐帧推进复制单：登记后 0.5s 复制第一发，
 *    再过 0.5s 复制第二发（共两发，[GravReplicatorTuning.copyDueTime]）。复制瞬间重新校验
 *    源弹体 spec 可复制性（武器 spec 与弹体 spec 仍可解析）；每发复制体伤害 = 原弹体
 *    伤害 × 难度比例（乘在 damageAmount 上），并给舰船附加 武器单发辐能 × 难度比例 的软辐能。
 *    舰船死亡/残骸化清空队列；队列随舰船实体回收，战斗结束不泄漏。
 * 4. **复制体出现位置与射向**：不锁定武器发射点——出现点在舰船中心周围
 *    碰撞半径 ×[SPAWN_RING_MIN_FRACTION, SPAWN_RING_MAX_FRACTION] 环带内均匀随机取点。
 *    若 ship.shipTarget 非 null 且存活，复制体初速向量取「出现点 → 预判命中点」方向 ×
 *    登记时快照的原弹速模长（预判命中点 = 目标位置 + 目标速度 × 距离/弹速），facing 与
 *    速度方向一致；无锁定目标时保持登记快照的原朝向/初速（仅位置随机化）。
 *
 * 特效：激活期间舰船紫色 jitter；每次复制在出现点放两个 FlareEntity（SMOOTH 圆斑 +
 * SHARP_DISC 光柱，0.5s 消散，紫色；光柱在复制体射向基础上再转 90° 作横向辉光）+
 * 一次小型扩散扭曲（0.5s/0.5s/0.5s，40→80su，按射向旋转）。
 */
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

        applyActivationFluxOnce(engine, ship, stats)
        if (engine.isPaused) return

        renderJitter(ship, id, effectLevel)
        scanFiredProjectiles(engine, ship)
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        val ship = stats.entity as? ShipAPI ?: return
        ship.isJitterShields = false
        val engine = Global.getCombatEngine() ?: return
        engine.customData.remove(ACTIVATION_LATCH_KEY_PREFIX + ship.id)
    }

    /** 激活辐能（一次性闩）：基础最大辐能容量 × [GravReplicatorTuning.ACTIVATION_FLUX_FRACTION] 软辐能。 */
    private fun applyActivationFluxOnce(engine: CombatEngineAPI, ship: ShipAPI, stats: MutableShipStatsAPI) {
        val key = ACTIVATION_LATCH_KEY_PREFIX + ship.id
        if (engine.customData[key] == true) return
        engine.customData[key] = true
        ship.fluxTracker.increaseFlux(GravReplicatorTuning.activationFlux(stats.fluxCapacity.baseValue), false)
    }

    /** 紫色 jitter（激活期间；IN/OUT 以 effectLevel 过渡，ACTIVE 满额）。 */
    private fun renderJitter(ship: ShipAPI, id: String, effectLevel: Float) {
        val level = effectLevel.coerceIn(0f, 1f)
        if (level <= 0f) return
        ship.isJitterShields = false
        ship.setJitterUnder(id, JITTER_UNDER, level, 25, 0f, 7f)
        ship.setJitter(id, JITTER, 0.6f * level, 3, 0f, 0f)
    }

    /**
     * 扫描本舰发射的能量武器实弹并登记复制单。
     * 每个弹体只处理一次（弹体 customData 标记；首写必须走 setCustomData——实体级
     * customData 惰性为 null 时 getCustomData() 返回一次性空表，直接 put 写入虚空）。
     */
    private fun scanFiredProjectiles(engine: CombatEngineAPI, ship: ShipAPI) {
        for (proj in engine.projectiles) {
            if (proj.source !== ship) continue
            if (proj.customData[SCAN_MARK_KEY] == true) continue
            proj.setCustomData(SCAN_MARK_KEY, true)
            if (proj is MissileAPI) continue
            // 系统开启前已在飞行的弹体（首次观测时已过扫描容忍窗）不登记
            if (proj.elapsed > SCAN_AGE_TOLERANCE) continue
            val weapon = proj.weapon ?: continue
            if (!isReplicableWeapon(weapon)) continue
            val weaponId = weapon.spec?.weaponId
            if (weaponId == null) {
                log.warn("引力空间复制器扫描到无 spec 的能量武器弹体（proj=${proj.projectileSpecId}，ship=${ship.id}），跳过登记")
                continue
            }

            queueOf(engine, ship).queue.add(
                ReplicaOrder(
                    weapon = weapon,
                    weaponId = weaponId,
                    facing = proj.facing,
                    velocity = Vector2f(proj.velocity),
                    damageAmount = proj.damageAmount,
                    fluxPerShot = weapon.fluxCostToFire,
                ),
            )
        }
    }

    /** 仅 type=ENERGY 的非光束、非装饰 projectile 武器参与复制（排除实弹、光束、导弹）。 */
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
        // CSV 口径 chargeUp=0 / down=0：IN/OUT 状态帧不存在，状态行只覆盖 ACTIVE
        if (state != ShipSystemStatsScript.State.ACTIVE) return null
        return ShipSystemStatsScript.StatusData(
            I18n[I18n.Categories.MOD, "system.grav_replicator.status.default.active"],
            false,
        )
    }

    /** 一条复制单：源弹体的 spec/朝向/初速/面板伤害/武器单发辐能快照 + 复制进度。 */
    private class ReplicaOrder(
        val weapon: WeaponAPI,
        val weaponId: String,
        val facing: Float,
        val velocity: Vector2f,
        val damageAmount: Float,
        val fluxPerShot: Float,
    ) {
        var copyIndex = 0
        var elapsed = 0f
    }

    /**
     * 复制调度器（逐舰一份，挂 ShipAPI listener + customData）：逐帧推进复制单队列，
     * 到期在舰船周界环带随机点 spawn 同类弹体（射向见 [spawnReplica]）。
     * 舰船死亡/残骸化清空队列（队列随舰船实体回收）。
     */
    private class ReplicaQueueProcessor(private val ship: ShipAPI) : AdvanceableListener {
        val queue = ArrayList<ReplicaOrder>()

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

        /**
         * 生成一发复制体：出现点在舰船中心周围 碰撞半径 ×[SPAWN_RING_MIN_FRACTION,
         * SPAWN_RING_MAX_FRACTION] 环带内均匀随机取点；若 ship.shipTarget 非 null 且存活，
         * 初速改为「出现点 → 预判命中点」方向 × 登记快照的弹速模长（预判命中点 = 目标位置 +
         * 目标速度 × 出现点距离/弹速），facing 与速度方向一致；无锁定目标时保持登记快照的
         * 原朝向/初速。复制瞬间重新校验源弹体可复制性——武器 spec 与弹体 spec 仍可解析，
         * 且武器实例仍有效（延迟 0.5s/1.0s 后武器可能已损毁/被移除：仍挂在舰船 allWeapons 中
         * 且未禁用）；校验失败丢弃本条复制单剩余复制并告警。复制体出生即带扫描标记防二次复制；
         * 每发复制给舰船附加 武器单发辐能 × 难度比例 的软辐能。
         */
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
            val (facing, velocity) = resolveReplicaTrajectory(ship, order, point)
            val spawned = engine.spawnProjectile(
                ship, order.weapon, order.weaponId,
                Vector2f(point), facing, velocity,
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

        /** 复制体出现点：舰船中心为圆心、碰撞半径 ×[SPAWN_RING_MIN_FRACTION, SPAWN_RING_MAX_FRACTION] 环带内均匀随机。 */
        private fun randomSpawnPoint(ship: ShipAPI): Vector2f {
            val angle = MathUtils.getRandomNumberInRange(0f, 360f)
            val radius = ship.collisionRadius * MathUtils.getRandomNumberInRange(SPAWN_RING_MIN_FRACTION, SPAWN_RING_MAX_FRACTION)
            val rad = Math.toRadians(angle.toDouble())
            return Vector2f(
                ship.location.x + (cos(rad) * radius).toFloat(),
                ship.location.y + (sin(rad) * radius).toFloat(),
            )
        }

        /**
         * 复制体射向结算：优先射向当前锁定目标并带预判量（预判命中点 = 目标位置 +
         * 目标速度 × 出现点到目标距离/弹速），方向取「出现点 → 预判命中点」、模长保持
         * 登记快照的原弹速；无锁定目标（或快照弹速为零）时保持原朝向/初速。
         */
        private fun resolveReplicaTrajectory(ship: ShipAPI, order: ReplicaOrder, point: Vector2f): Pair<Float, Vector2f> {
            val speed = order.velocity.length()
            val target = ship.shipTarget
            if (speed <= 0f || target == null || !target.isAlive || target.isHulk) {
                return order.facing to Vector2f(order.velocity)
            }
            val travelSeconds = Misc.getDistance(point, target.location) / speed
            val aimPoint = Vector2f(
                target.location.x + target.velocity.x * travelSeconds,
                target.location.y + target.velocity.y * travelSeconds,
            )
            val direction = Vector2f.sub(aimPoint, point, null)
            if (direction.lengthSquared() <= 0f) return order.facing to Vector2f(order.velocity)
            direction.normalise(direction)
            return Misc.getAngleInDegrees(direction) to Vector2f(direction.x * speed, direction.y * speed)
        }

        /**
         * 复制一次性视觉（出现点）：SMOOTH 圆形光斑 + SHARP_DISC 光柱（在复制体射向基础上
         * 再转 90° 作横向辉光，0.5s 消散，紫色）+ 小型扩散扭曲（0.5s/0.5s/0.5s，40→80su，
         * 按射向旋转）。
         */
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

        /** 激活辐能闩键前缀（engine.customData，键用 ship.id；unapply 清除）。 */
        private const val ACTIVATION_LATCH_KEY_PREFIX = "astd_grav_replicator_activated:"

        /** 弹体扫描标记键（弹体实体 customData，随实体回收；复制体出生即携带防二次复制）。 */
        private const val SCAN_MARK_KEY = "astd_grav_replicator_scanned"

        /** 逐舰复制调度器挂载键（ship.customData，随舰船实体回收）。 */
        private const val QUEUE_KEY = "astd_grav_replicator_queue"

        /** 系统开启前已飞行弹体的判定容忍窗（秒）：首次观测时 elapsed 超过本值只标记不登记。 */
        private const val SCAN_AGE_TOLERANCE = 0.1f

        /** 复制体出现环带（碰撞半径倍数）：舰船中心周围 [0.5, 1.3] 倍碰撞半径环带内均匀随机取点。 */
        private const val SPAWN_RING_MIN_FRACTION = 0.5f
        private const val SPAWN_RING_MAX_FRACTION = 1.3f

        // 复制光斑尺寸（紫色）
        private const val FLARE_GLOW_SIZE = 42f
        private const val FLARE_PILLAR_LENGTH = 140f
        private const val FLARE_PILLAR_WIDTH = 26f
        private val FLARE_CORE = Color(235, 195, 255)
        private val FLARE_FRINGE = Color(170, 90, 255)

        private val JITTER_UNDER = Color(170, 110, 255, 155)
        private val JITTER = Color(170, 110, 255, 55)
    }
}
