package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcAuraUtil
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

class ASTDArcSharedFluxNetworkSystemAI : ShipSystemAIScript {

    companion object {
        private const val SCAN_INTERVAL_SEC = 0.60f
        private const val SOURCE_ACTIVATE_MAX_FLUX = 0.80f
        private const val SOURCE_ABORT_FLUX = 0.85f
        private const val TARGET_PRESSURE_FLUX = 0.60f
    }

    private var ship: ShipAPI? = null
    private var system: ShipSystemAPI? = null
    private var flags: ShipwideAIFlags? = null
    private var engine: CombatEngineAPI? = null

    private val scanInterval = IntervalUtil(SCAN_INTERVAL_SEC, SCAN_INTERVAL_SEC)
    private var scanInited = false
    private var cachedScan = ScanResult(eligibleCount = 0, pressuredValue = 0f, enemyPressure = false)

    override fun init(ship: ShipAPI, system: ShipSystemAPI, flags: ShipwideAIFlags, engine: CombatEngineAPI) {
        this.ship = ship
        this.system = system
        this.flags = flags
        this.engine = engine
        scanInterval.forceIntervalElapsed()
        scanInited = false
        cachedScan = ScanResult(eligibleCount = 0, pressuredValue = 0f, enemyPressure = false)
    }

    override fun advance(amount: Float, missileDangerDir: Vector2f?, collisionDangerDir: Vector2f?, target: ShipAPI?) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return
        if (engine.isPaused || ship.isHulk) return

        val tracker = ship.fluxTracker ?: return
        val fluxLevel = try { tracker.fluxLevel } catch (_: Throwable) { 0f }
        val overloadedOrVenting = try { tracker.isOverloadedOrVenting } catch (_: Throwable) { false }
        val retreating = isRetreating()

        if (system.isOn || system.state == ShipSystemAPI.SystemState.IN || system.state == ShipSystemAPI.SystemState.ACTIVE) {
            if (fluxLevel > SOURCE_ABORT_FLUX || overloadedOrVenting || retreating) {
                try { ship.useSystem() } catch (_: Throwable) {}
            }
            return
        }

        if (!system.canBeActivated()) return
        if (overloadedOrVenting || retreating || fluxLevel > SOURCE_ACTIVATE_MAX_FLUX) return

        scanInterval.advance(amount)
        if (!scanInited || scanInterval.intervalElapsed()) {
            cachedScan = scanFriendlies(engine, ship)
            scanInited = true
        }

        val wantsNetwork = cachedScan.eligibleCount >= 2 ||
            (cachedScan.pressuredValue >= 1.5f && !cachedScan.enemyPressure)
        if (wantsNetwork) {
            try { ship.useSystem() } catch (_: Throwable) {}
        }
    }

    private fun scanFriendlies(engine: CombatEngineAPI, source: ShipAPI): ScanResult {
        val ships = try { engine.ships } catch (_: Throwable) { null } ?: return ScanResult(0, 0f, false)
        var eligible = 0
        var value = 0f
        var nearbyEnemy = false

        for (candidate in ships) {
            if (candidate === source || candidate.isHulk) continue
            val distance = MathUtils.getDistance(source.location, candidate.location)
            if (candidate.owner == source.owner) {
                if (candidate.isFighter || candidate.isDrone) continue
                if (distance > ASTDArcAuraUtil.ARC_JET_SYSTEM_MAX_RANGE) continue
                eligible++
                val flux = try { candidate.fluxTracker?.fluxLevel ?: 0f } catch (_: Throwable) { 0f }
                val sizeValue = when (candidate.hullSize) {
                    ShipAPI.HullSize.CAPITAL_SHIP -> 2.2f
                    ShipAPI.HullSize.CRUISER -> 1.6f
                    ShipAPI.HullSize.DESTROYER -> 1.1f
                    ShipAPI.HullSize.FRIGATE -> 0.8f
                    else -> 0f
                }
                if (flux >= TARGET_PRESSURE_FLUX) value += sizeValue * (0.75f + flux)
            } else if (distance <= source.collisionRadius + candidate.collisionRadius + 650f) {
                nearbyEnemy = true
            }
        }

        return ScanResult(eligible, value, nearbyEnemy)
    }

    private fun isRetreating(): Boolean {
        val flags = this.flags ?: return false
        return try {
            flags.hasFlag(ShipwideAIFlags.AIFlags.RUN_QUICKLY) ||
                flags.hasFlag(ShipwideAIFlags.AIFlags.BACK_OFF) ||
                flags.hasFlag(ShipwideAIFlags.AIFlags.BACKING_OFF)
        } catch (_: Throwable) {
            false
        }
    }

    private data class ScanResult(
        val eligibleCount: Int,
        val pressuredValue: Float,
        val enemyPressure: Boolean,
    )
}
