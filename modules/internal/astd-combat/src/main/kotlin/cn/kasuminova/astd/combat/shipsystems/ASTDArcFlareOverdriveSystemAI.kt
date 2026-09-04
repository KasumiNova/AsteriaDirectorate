package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

/**
 * 战术超频系统 AI。
 *
 * 自动模式：
 * - 无附近敌人（巡航/转战）→ 直接开启（利用时流加速过图）
 * - 有敌在射程内 → 开启（战斗加成 + 时流优势）
 * - 敌在附近但射程外，且辐能正常 → 不开（节省冷却）
 * - 辐能告急（≥ 65%）→ 无论距离都开（靠 50% DR 扛过危机）
 *
 * 载人模式：
 * - 无射程内敌人 → 不开
 * - 有射程内敌人 → 开启（射速/弹匣增益）
 */
open class ASTDArcFlareOverdriveSystemAI : ShipSystemAIScript {

    /** 子类覆盖以指定系统所属模式。 */
    protected open val isAutomatedSystem: Boolean = false

    companion object {
        private const val FALLBACK_RANGE = 800f
        private const val SCAN_INTERVAL_SEC = 0.75f
        private const val HIGH_FLUX_THRESHOLD = 0.65f
        // "附近" = 武器射程的 2 倍（用于判断有没有敌人在战场上）
        private const val NEARBY_RANGE_MULT = 2.0f
    }

    private var ship: ShipAPI? = null
    private var system: ShipSystemAPI? = null
    private var engine: CombatEngineAPI? = null

    private var weaponRange = FALLBACK_RANGE
    private var isAutomated = false

    private val scanInterval = IntervalUtil(SCAN_INTERVAL_SEC, SCAN_INTERVAL_SEC)
    private var scanInited = false
    private var cachedResult = ScanResult(hasEnemyNearby = false, hasEnemyInRange = false)

    override fun init(ship: ShipAPI, system: ShipSystemAPI, flags: ShipwideAIFlags, engine: CombatEngineAPI) {
        this.ship = ship
        this.system = system
        this.engine = engine

        // 从非导弹武器读取最大射程
        weaponRange = try {
            ship.allWeapons
                ?.filter { w ->
                    try { w.type != WeaponAPI.WeaponType.MISSILE } catch (_: Throwable) { true }
                }
                ?.mapNotNull { w -> try { w.range } catch (_: Throwable) { null } }
                ?.maxOrNull()
                ?: FALLBACK_RANGE
        } catch (_: Throwable) {
            FALLBACK_RANGE
        }

        isAutomated = isAutomatedSystem

        scanInterval.forceIntervalElapsed()
        scanInited = false
        cachedResult = ScanResult(hasEnemyNearby = false, hasEnemyInRange = false)
    }

    override fun advance(amount: Float, missileDangerDir: Vector2f?, collisionDangerDir: Vector2f?, target: ShipAPI?) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return

        if (engine.isPaused) return
        if (ship.isHulk) return

        scanInterval.advance(amount)
        if (!scanInited || scanInterval.intervalElapsed()) {
            cachedResult = scanNearbyEnemies(engine, ship, target)
            scanInited = true
        }

        val ft = ship.fluxTracker ?: return
        val fluxLevel = try { ft.fluxLevel } catch (_: Throwable) { 0f }
        val overloadedOrVenting = try { ft.isOverloadedOrVenting } catch (_: Throwable) { false }

        if (isAutomated) {
            advanceAutoMode(ship, system, cachedResult.hasEnemyNearby, cachedResult.hasEnemyInRange, fluxLevel, overloadedOrVenting)
        } else {
            advanceCrewedMode(ship, system, cachedResult.hasEnemyInRange, overloadedOrVenting)
        }
    }

    private fun advanceAutoMode(
        ship: ShipAPI,
        system: ShipSystemAPI,
        hasEnemyNearby: Boolean,
        hasEnemyInRange: Boolean,
        fluxLevel: Float,
        overloadedOrVenting: Boolean,
    ) {
        // 过载/散热时不干预（系统会自动结束或由系统自身处理）
        if (overloadedOrVenting) return
        // 系统已在激活中（含 chargeUp/active/chargeDown 阶段），不重复触发
        if (system.isOn || system.state == ShipSystemAPI.SystemState.IN || system.state == ShipSystemAPI.SystemState.ACTIVE) return
        if (!system.canBeActivated()) return

        val isTransiting = !hasEnemyNearby
        val badFlux = fluxLevel >= HIGH_FLUX_THRESHOLD

        val shouldUse = when {
            isTransiting -> true          // 战场无敌：巡航/转战全速开
            hasEnemyInRange -> true       // 敌在射程内：开启战斗加成
            badFlux -> true               // 辐能告急：靠 DR 扛压
            else -> false                 // 敌在附近但射程外，辐能正常：省冷却
        }

        if (shouldUse) {
            try { ship.useSystem() } catch (_: Throwable) {}
        }
    }

    private fun advanceCrewedMode(
        ship: ShipAPI,
        system: ShipSystemAPI,
        hasEnemyInRange: Boolean,
        overloadedOrVenting: Boolean,
    ) {
        if (overloadedOrVenting) return
        if (system.isOn || system.state == ShipSystemAPI.SystemState.IN || system.state == ShipSystemAPI.SystemState.ACTIVE) return
        if (!system.canBeActivated()) return

        // 载人模式：只在射程内有敌时开启
        if (hasEnemyInRange) {
            try { ship.useSystem() } catch (_: Throwable) {}
        }
    }

    private data class ScanResult(val hasEnemyNearby: Boolean, val hasEnemyInRange: Boolean)

    private fun scanNearbyEnemies(engine: CombatEngineAPI, ship: ShipAPI, hinted: ShipAPI?): ScanResult {
        val nearbyRange = weaponRange * NEARBY_RANGE_MULT
        var hasEnemyNearby = false
        var hasEnemyInRange = false

        // 优先检查 AI 传入 target 和当前 shipTarget
        val candidates = ArrayList<ShipAPI>(2)
        if (hinted != null) candidates.add(hinted)
        try {
            val st = ship.shipTarget
            if (st != null && st !in candidates) candidates.add(st)
        } catch (_: Throwable) {}

        for (t in candidates) {
            if (!isValidTarget(engine, ship, t, nearbyRange)) continue
            hasEnemyNearby = true
            if (MathUtils.getDistance(ship.location, t.location) <= weaponRange) hasEnemyInRange = true
            if (hasEnemyNearby && hasEnemyInRange) return ScanResult(true, true)
        }

        // 全量扫描（已节流，约每 0.75s 一次）
        val ships = try { engine.ships } catch (_: Throwable) { null }
        if (ships != null) {
            for (s in ships) {
                val t = s as? ShipAPI ?: continue
                if (!isValidTarget(engine, ship, t, nearbyRange)) continue
                hasEnemyNearby = true
                if (MathUtils.getDistance(ship.location, t.location) <= weaponRange) hasEnemyInRange = true
                if (hasEnemyNearby && hasEnemyInRange) break
            }
        }

        return ScanResult(hasEnemyNearby = hasEnemyNearby, hasEnemyInRange = hasEnemyInRange)
    }

    private fun isValidTarget(engine: CombatEngineAPI, ship: ShipAPI, target: ShipAPI, range: Float): Boolean {
        if (target === ship) return false
        if (target.isHulk) return false
        if (target.owner == ship.owner) return false
        if (!engine.isEntityInPlay(target)) return false
        return MathUtils.getDistance(ship.location, target.location) <= range
    }
}
