package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

/**
 * 「辐能链接网络」系统 AI：友舰辐能压力高且自身辐能充裕时开启；自身辐能逼近上限、
 * 过载/排气或撤退时关闭。
 */
class FluxLinkNetworkSystemAI : ShipSystemAIScript {

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
        val fluxLevel = tracker.fluxLevel
        val overloadedOrVenting = tracker.isOverloadedOrVenting
        val retreating = isRetreating()

        if (system.isOn || system.state == ShipSystemAPI.SystemState.IN || system.state == ShipSystemAPI.SystemState.ACTIVE) {
            if (fluxLevel > SOURCE_ABORT_FLUX || overloadedOrVenting || retreating) {
                ship.useSystem()
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

        val wantsNetwork = cachedScan.eligibleCount >= 2 && cachedScan.pressuredValue >= 1f ||
                cachedScan.pressuredValue >= 1.5f && !cachedScan.enemyPressure
        if (wantsNetwork) {
            ship.useSystem()
        }
    }

    private fun scanFriendlies(engine: CombatEngineAPI, source: ShipAPI): ScanResult {
        val range = FluxLinkNetworkTuning.resolve(DifficultyTuningImpl, source.owner == 0).range
        var eligible = 0
        var value = 0f
        var nearbyEnemy = false

        for (candidate in engine.ships) {
            if (candidate === source || candidate.isHulk) continue
            val distance = MathUtils.getDistance(source.location, candidate.location)
            if (candidate.owner == source.owner) {
                if (candidate.isFighter || candidate.isDrone || candidate.isStationModule) continue
                if (distance > range) continue
                eligible++
                val flux = candidate.fluxTracker?.fluxLevel ?: 0f
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
        return flags.hasFlag(ShipwideAIFlags.AIFlags.RUN_QUICKLY) ||
                flags.hasFlag(ShipwideAIFlags.AIFlags.BACK_OFF) ||
                flags.hasFlag(ShipwideAIFlags.AIFlags.BACKING_OFF)
    }

    private data class ScanResult(
        val eligibleCount: Int,
        val pressuredValue: Float,
        val enemyPressure: Boolean,
    )
}
