package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.impl.combat.PhaseCloakStats
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

class RiftShiftSystemStats : PhaseCloakStats() {

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        super.apply(stats, id, state, effectLevel)
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk) return
        // ACTIVE 边沿触发：IN 边沿会恒差一个 chargeUp，导致相位窗口与折跃位移错位
        if (state != ShipSystemStatsScript.State.ACTIVE) return
        val engine = Global.getCombatEngine() ?: return
        val triggerKey = TRIGGER_KEY_PREFIX + System.identityHashCode(ship)
        if (engine.customData[triggerKey] == true) return
        engine.customData[triggerKey] = true
        openRift(engine, ship)
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        super.unapply(stats, id)
        val ship = stats.entity as? ShipAPI ?: return
        val engine = Global.getCombatEngine() ?: return
        engine.customData.remove(TRIGGER_KEY_PREFIX + System.identityHashCode(ship))
    }

    // 原版 PhaseCloakStats.maintainStatus 硬编码英文状态文本，此处按相同结构输出 I18n 文本
    override fun maintainStatus(playerShip: ShipAPI, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val cloak = playerShip.phaseCloak ?: playerShip.system ?: return
        val engine = Global.getCombatEngine()
        val icon = cloak.specAPI.iconSpriteName

        if (effectLevel > PhaseCloakStats.VULNERABLE_FRACTION) {
            engine.maintainStatusForPlayerShip(
                STATUSKEY2, icon, cloak.displayName,
                I18n[I18n.Categories.MOD, "system.rift_shift.status.0"], false,
            )
        }

        val rift = engine.customData[RIFT_KEY_PREFIX + System.identityHashCode(playerShip)] as? RiftState ?: return
        val remaining = RiftShiftTuning.riftLifetimeSeconds(rift.formingDuration) - rift.elapsed
        if (remaining <= 0f) return
        engine.maintainStatusForPlayerShip(
            STATUSKEY3, icon, cloak.displayName,
            I18n[I18n.Categories.MOD, "system.rift_shift.status.1"]
                .replace("%seconds%", "%.1f".format(remaining)),
            true,
        )
    }

    private fun openRift(engine: CombatEngineAPI, ship: ShipAPI) {
        val from = Vector2f(ship.location)
        val dir = RiftShiftTuning.shiftDirection(ship.velocity, ship.facing)
        val maxDist = ship.mutableStats.systemRangeBonus.computeEffective(RiftShiftTuning.SHIFT_DISTANCE)
        val target = resolveShiftTargetPoint(ship)
        val dist = if (target != null) {
            RiftShiftTuning.resolveShiftDistance(maxDist, MathUtils.getDistance(from, target))
        } else {
            maxDist
        }
        if (dist < RiftShiftTuning.ANCHOR_SPACING) {
            log.warn("[ASTD] 裂隙折跃折跃长度不足一个点位间距（${dist}su，maxDist=${maxDist}su），本次不开裂隙")
            return
        }
        val to = Vector2f(from.x + dir.x * dist, from.y + dir.y * dist)
        val formingDuration = RiftShiftTuning.shiftDurationSeconds(maxDist, dist)
        val riftKey = RIFT_KEY_PREFIX + System.identityHashCode(ship)
        (engine.customData[riftKey] as? RiftState)?.let { discardRift(engine, riftKey, it) }
        val rift = RiftState(
            ship = ship,
            from = from,
            to = to,
            formingDuration = formingDuration,
            length = dist,
            points = RiftShiftTuning.anchorPoints(from, to).mapIndexed { index, pos ->
                RiftPoint(pos, RiftShiftTuning.ANCHOR_SPACING * (index + 1))
            },
        )
        engine.customData[riftKey] = rift
        spawnAnchorMines(engine, rift)
        ensureRiftPlugin(engine)
    }

    // AI 决策通道 SYSTEM_TARGET_COORDS 为原版 MineStrikeStats 同款注入通道，优先于玩家鼠标坐标
    private fun resolveShiftTargetPoint(ship: ShipAPI): Vector2f? {
        val flags = ship.aiFlags
        if (flags != null && flags.hasFlag(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS)) {
            val custom = flags.getCustom(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS) as? Vector2f
            if (custom != null) return Vector2f(custom)
        }
        val mouse = ship.mouseTarget ?: return null
        return Vector2f(mouse)
    }

    // spawn 后以 SilentMineAI 替换原版自动挂载的 GuidedProximityFuseAI：原版引信在弹体消退时走 trigger→primed→windup→explode 全链放音
    private fun spawnAnchorMines(engine: CombatEngineAPI, rift: RiftState) {
        for (point in rift.points) {
            val mine = engine.spawnProjectile(
                rift.ship, null, RiftShiftTuning.MINE_WEAPON_ID, point.pos, 0f, null,
            ) as? MissileAPI
            if (mine == null) {
                log.warn("[ASTD] 裂隙折跃虚空锚雷生成失败：spawnProjectile 未产出 MissileAPI: weapon=${RiftShiftTuning.MINE_WEAPON_ID}")
                continue
            }
            mine.source = rift.ship
            mine.damageAmount = RiftShiftTuning.MINE_PANEL_DAMAGE
            mine.velocity.set(0f, 0f)
            mine.armingTime = 0f
            mine.maxFlightTime = RiftShiftTuning.mineFlightTimeSeconds(rift.formingDuration)
            mine.setMineExplosionRange(RiftShiftTuning.CONTACT_RANGE)
            mine.setMinePrimed(true)
            mine.setUntilMineExplosion(1e6f)
            mine.setNoGlowTime(999f)
            mine.isNoFlameoutOnFizzling = true
            mine.interruptContrail()
            mine.spriteAlphaOverride = 0f
            mine.glowRadius = 0f
            mine.missileAI = SilentMineAI
            rift.mines.add(mine)
        }
    }

    private fun ensureRiftPlugin(engine: CombatEngineAPI) {
        if (engine.customData[PLUGIN_KEY] == true) return
        engine.customData[PLUGIN_KEY] = true
        engine.addPlugin(object : BaseEveryFrameCombatPlugin() {
            override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
                if (engine.isPaused || amount <= 0f) return
                val entries = engine.customData.entries
                    .filter { it.key.startsWith(RIFT_KEY_PREFIX) }
                    .toList()
                for ((key, value) in entries) {
                    val rift = value as? RiftState ?: continue
                    val ship = rift.ship
                    if (!engine.isEntityInPlay(ship) || ship.isHulk || !ship.isAlive) {
                        discardRift(engine, key, rift)
                        continue
                    }
                    if (advanceRift(engine, key, rift, amount)) {
                        discardRift(engine, key, rift)
                    }
                }
                // customData 置 null 会残留键导致恒 false，清理必须 remove 摘除
                if (engine.customData.keys.none { it.startsWith(RIFT_KEY_PREFIX) }) {
                    engine.customData.remove(PLUGIN_KEY)
                    engine.removePlugin(this)
                }
            }
        })
    }

    private fun discardRift(engine: CombatEngineAPI, riftKey: String, rift: RiftState) {
        RiftShiftVfx.closeRiftBody(engine, riftKey)
        for (mine in rift.mines) {
            if (engine.isEntityInPlay(mine)) engine.removeEntity(mine)
        }
        rift.mines.clear()
        engine.customData.remove(riftKey)
    }

    // 裂隙时钟与原版 ChargeTracker 同一时钟（amount × timeMult 舰船时间），相位三倍时流下与相位 ACTIVE 窗口天然对齐
    private fun advanceRift(engine: CombatEngineAPI, riftKey: String, rift: RiftState, amount: Float): Boolean {
        val ship = rift.ship
        val shipTime = amount * ship.mutableStats.timeMult.modifiedValue
        rift.elapsed += shipTime
        val formingEnd = rift.formingDuration
        val lingeringEnd = formingEnd + RiftShiftTuning.CLOSURE_DELAY_SECONDS

        val phase: Int
        val tickInterval: Float
        val tickDamage: Float
        val segFrom = Vector2f(rift.from)
        val segTo = Vector2f(rift.to)
        when {
            rift.elapsed < formingEnd -> {
                phase = PHASE_FORMING
                tickInterval = RiftShiftTuning.GRAZE_TICK_SECONDS
                tickDamage = RiftShiftTuning.GRAZE_DAMAGE
                val progress = rift.elapsed / formingEnd
                val tip = RiftShiftTuning.pathPointAt(rift.from, rift.to, progress)
                ship.location.set(tip)
                ship.velocity.set(0f, 0f)
                segTo.set(tip)
                val front = RiftShiftTuning.easedPathFraction(progress) * rift.length
                for (point in rift.points) {
                    if (point.state == POINT_PENDING && point.distAlong <= front) point.state = POINT_ACTIVE
                }
            }

            rift.elapsed < lingeringEnd -> {
                phase = PHASE_LINGERING
                tickInterval = RiftShiftTuning.CONTACT_TICK_SECONDS
                tickDamage = RiftShiftTuning.CONTACT_DAMAGE
            }

            else -> {
                val closureT = (rift.elapsed - lingeringEnd) / rift.formingDuration
                if (closureT >= 1f) return true
                phase = PHASE_CLOSING
                tickInterval = RiftShiftTuning.CONTACT_TICK_SECONDS
                tickDamage = RiftShiftTuning.CONTACT_DAMAGE
                val headFraction = RiftShiftTuning.easedPathFraction(closureT)
                segFrom.set(RiftShiftTuning.pathPointAt(rift.from, rift.to, closureT))
                val sweptPositions = mutableListOf<Vector2f>()
                for (point in rift.points) {
                    if (point.state == POINT_ACTIVE && point.distAlong <= headFraction * rift.length) {
                        point.state = POINT_CLOSED
                        sweptPositions.add(point.pos)
                    }
                }
                if (sweptPositions.isNotEmpty()) {
                    settlePointsTick(engine, ship, sweptPositions, RiftShiftTuning.GRAZE_DAMAGE)
                }
            }
        }

        if (rift.phase != phase) {
            if (phase == PHASE_LINGERING) {
                // 拉开完毕即收尾相位窗口：.system active 为满距名义最大值，短距折跃随之提前结束
                for (point in rift.points) {
                    if (point.state == POINT_PENDING) point.state = POINT_ACTIVE
                }
                endPhaseWindow(ship)
            }
            rift.phase = phase
            rift.damageTimer = 0f
        }
        rift.damageTimer += shipTime
        while (rift.damageTimer >= tickInterval) {
            rift.damageTimer -= tickInterval
            val activePositions = rift.points.filter { it.state == POINT_ACTIVE }.map { it.pos }
            settlePointsTick(engine, ship, activePositions, tickDamage)
        }

        RiftShiftVfx.riftBodyFrame(engine, riftKey, segFrom, segTo, RiftShiftVfx.bodyIntensity(rift.elapsed))
        RiftShiftVfx.riftFrame(engine, segFrom, segTo, amount)
        return false
    }

    private fun endPhaseWindow(ship: ShipAPI) {
        val cloak = ship.phaseCloak ?: ship.system ?: return
        if (cloak.state == ShipSystemAPI.SystemState.ACTIVE) {
            cloak.forceState(ShipSystemAPI.SystemState.OUT, 0f)
        }
    }

    internal fun settlePointsTick(
        engine: CombatEngineAPI,
        source: ShipAPI,
        points: List<Vector2f>,
        damage: Float,
    ) {
        if (points.isEmpty()) return
        for (candidate in engine.ships) {
            val ship = candidate as? ShipAPI ?: continue
            if (ship === source || ship.owner == source.owner) continue
            if (!ship.isAlive && !ship.isHulk) continue
            val point = RiftShiftTuning.nearestAnchorInRange(ship.location, points) ?: continue
            applyRiftDamage(engine, source, ship, point, damage)
        }
        for (missile in engine.missiles) {
            if (missile.owner == source.owner || missile.isExpired || missile.isFading) continue
            val point = RiftShiftTuning.nearestAnchorInRange(missile.location, points) ?: continue
            engine.applyDamage(
                missile, point, damage,
                DamageType.ENERGY, 0f,
                false, false, source, true,
            )
        }
        for (asteroid in engine.asteroids) {
            if (asteroid.owner == source.owner) continue
            val point = RiftShiftTuning.nearestAnchorInRange(asteroid.location, points) ?: continue
            engine.applyDamage(
                asteroid, point, damage,
                DamageType.ENERGY, 0f,
                false, false, source, true,
            )
        }
    }

    private fun applyRiftDamage(
        engine: CombatEngineAPI,
        source: ShipAPI,
        ship: ShipAPI,
        contact: Vector2f,
        damage: Float,
    ) {
        val shield = ship.shield
        val shieldCovers = shield != null && shield.isOn &&
            MathUtils.getDistance(contact, ship.location) <= shield.radius &&
            shield.isWithinArc(contact)
        if (shieldCovers) {
            engine.applyDamage(
                ship, shieldSurfacePoint(ship, contact), damage,
                DamageType.ENERGY, 0f,
                false, false, source, true,
            )
        } else {
            engine.applyDamage(
                ship, clampIntoHull(ship, contact), damage,
                DamageType.ENERGY, 0f,
                true, false, source, true,
            )
        }
    }

    // 脚本 applyDamage 对界外命中点恒结算 0 伤害，接触点须压回碰撞圈内
    private fun clampIntoHull(ship: ShipAPI, contact: Vector2f): Vector2f {
        val dx = contact.x - ship.location.x
        val dy = contact.y - ship.location.y
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        val limit = ship.collisionRadius * 0.9f
        if (dist <= limit || dist <= 1e-3f) return Vector2f(contact)
        val scale = limit / dist
        return Vector2f(ship.location.x + dx * scale, ship.location.y + dy * scale)
    }

    private fun shieldSurfacePoint(ship: ShipAPI, contact: Vector2f): Vector2f {
        val shield = ship.shield ?: return Vector2f(ship.location)
        val radius = shield.radius
        if (radius <= 0f) return Vector2f(ship.location)
        // 不取 Misc.getAngleInDegrees：Misc 静态初始化依赖游戏运行时，无头环境直接 ExceptionInInitializerError
        val angle = Math.toDegrees(
            kotlin.math.atan2(
                (contact.y - ship.location.y).toDouble(),
                (contact.x - ship.location.x).toDouble(),
            ),
        ).toFloat()
        return MathUtils.getPointOnCircumference(ship.location, radius, angle)
    }

    private object SilentMineAI : MissileAIPlugin {
        override fun advance(amount: Float) {
        }
    }

    private class RiftPoint(
        val pos: Vector2f,
        val distAlong: Float,
        var state: Int = POINT_PENDING,
    )

    private class RiftState(
        val ship: ShipAPI,
        val from: Vector2f,
        val to: Vector2f,
        val formingDuration: Float,
        val length: Float,
        val points: List<RiftPoint>,
        var elapsed: Float = 0f,
        var damageTimer: Float = 0f,
        var phase: Int = PHASE_FORMING,
        val mines: MutableList<MissileAPI> = mutableListOf(),
    )

    companion object {
        private const val TRIGGER_KEY_PREFIX = "astd_rift_shift_trigger:"
        private const val RIFT_KEY_PREFIX = "astd_rift_shift_rift:"
        private const val PLUGIN_KEY = "astd_rift_shift_plugin"

        private const val PHASE_FORMING = 0
        private const val PHASE_LINGERING = 1
        private const val PHASE_CLOSING = 2

        private const val POINT_PENDING = 0
        private const val POINT_ACTIVE = 1
        private const val POINT_CLOSED = 2

        private val log = AstdLog.logger
    }
}
