package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.impl.campaign.ids.Personalities
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs

/**
 * 恒星喷射系统 AI：
 * - 在高辐能（默认 65%+）且前方存在目标时启用系统；
 * - 系统开启后，低辐能不主动关闭；仅在前方目标丢失超过一段时间后才关闭（避免抖动）。
 */
class StellarJetSystemAI : ShipSystemAIScript {

    companion object {
        private const val JET_WEAPON_ID = "astd_stellar_jet_emitter"

        // 系统开启后：前方目标丢失超过该时间才会自动关闭
        private const val TARGET_LOST_CLOSE_DELAY_SEC = 4f

        // “前方有目标”的判定：以舰体朝向为基准的锥形角度（左右各一半）
        private const val FORWARD_CONE_HALF_ANGLE_DEG = 35f

        // 高辐能且前方战机“足够多”时也允许开启系统（不要求前方有大船）
        private const val FIGHTER_SWARM_THRESHOLD = 4

        // 若找不到喷射口武器，则用一个保底范围
        private const val FALLBACK_RANGE = 4200f

        // 扫描间隔：每秒最多做一次全量扫描（减少开销）
        private const val SCAN_INTERVAL_SEC = 1f

        private data class Thresholds(
            val fluxOn: Float,
            val fighterSwarm: Int,
        )

        private fun getThresholdsByPersonality(personalityId: String?): Thresholds {
            return when (personalityId) {
                // 胆小/谨慎/沉着/激进/鲁莽(无畏)
                Personalities.TIMID -> Thresholds(fluxOn = 0.55f, fighterSwarm = 5)
                Personalities.CAUTIOUS -> Thresholds(fluxOn = 0.60f, fighterSwarm = 6)
                Personalities.STEADY -> Thresholds(fluxOn = 0.65f, fighterSwarm = 8)
                Personalities.AGGRESSIVE -> Thresholds(fluxOn = 0.70f, fighterSwarm = 9)
                Personalities.RECKLESS -> Thresholds(fluxOn = 0.75f, fighterSwarm = 10)
                else -> Thresholds(fluxOn = 0.65f, fighterSwarm = 8)
            }
        }

        private fun angleDiffDeg(a: Float, b: Float): Float {
            var d = (b - a) % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }
    }

    private var ship: ShipAPI? = null
    private var system: ShipSystemAPI? = null
    private var engine: CombatEngineAPI? = null

    private var jetRange: Float = FALLBACK_RANGE

    private var targetLostTime = 0f

    private val scanInterval = IntervalUtil(SCAN_INTERVAL_SEC, SCAN_INTERVAL_SEC)
    private var scanInited = false
    private var cachedScan = ForwardScan(hasNonFighter = false, forwardFighterCount = 0)
    private var cachedThresholds = Thresholds(fluxOn = 0.65f, fighterSwarm = 8)

    override fun init(ship: ShipAPI, system: ShipSystemAPI, flags: ShipwideAIFlags, engine: CombatEngineAPI) {
        this.ship = ship
        this.system = system
        this.engine = engine

        // 尽量从喷射口武器读取实际射程（便于未来改表时 AI 自动跟随）。
        jetRange = try {
            val w = ship.allWeapons?.firstOrNull { it.id == JET_WEAPON_ID }
            (w?.range ?: FALLBACK_RANGE).coerceAtLeast(0f)
        } catch (_: Throwable) {
            FALLBACK_RANGE
        }

        // 初始化一次阈值，避免第一帧读不到 captain/personality。
        cachedThresholds = getThresholdsByPersonality(
            try {
                ship.captain?.personalityAPI?.id
            } catch (_: Throwable) {
                null
            }
        )
        scanInterval.forceIntervalElapsed()
        scanInited = false
        cachedScan = ForwardScan(hasNonFighter = false, forwardFighterCount = 0)
        targetLostTime = 0f
    }

    override fun advance(amount: Float, missileDangerDir: Vector2f?, collisionDangerDir: Vector2f?, target: ShipAPI?) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return

        if (engine.isPaused) return
        if (ship.isHulk) return

        // 不在可激活状态就直接放弃（例如冷却/没弹药等）
        // 注意：toggle 系统处于 on 时 canBeActivated() 可能为 false；因此只在“要开机”时检查。

        val ft = ship.fluxTracker ?: return
        val fluxLevel = try {
            ft.fluxLevel
        } catch (_: Throwable) {
            0f
        }

        val overloadedOrVenting = try {
            ft.isOverloadedOrVenting
        } catch (_: Throwable) {
            false
        }
        if (overloadedOrVenting) {
            // 若系统处于开启，则关闭，避免在过载/散热时浪费。
            if (system.isOn) {
                try {
                    ship.useSystem()
                } catch (_: Throwable) {
                }
            }
            targetLostTime = 0f
            return
        }

        // 每秒最多更新一次扫描结果与阈值（降低 engine.ships 遍历频率）
        scanInterval.advance(amount)
        if (!scanInited || scanInterval.intervalElapsed()) {
            cachedThresholds = getThresholdsByPersonality(
                try {
                    ship.captain?.personalityAPI?.id
                } catch (_: Throwable) {
                    null
                }
            )
            cachedScan = scanForwardTargets(engine, ship, target)
            scanInited = true
        }

        val scan = cachedScan
        val thresholds = cachedThresholds
        val hasAnyForwardTarget = scan.hasNonFighter || scan.forwardFighterCount > 0

        if (!system.isOn) {
            // 开启条件：高辐能 + 前方有敌方目标（含非战机），或“战机数量足够多”
            val allowByFighterSwarm = scan.forwardFighterCount >= thresholds.fighterSwarm
            val wantsOn = fluxLevel >= thresholds.fluxOn && (scan.hasNonFighter || allowByFighterSwarm)

            if (wantsOn && system.canBeActivated()) {
                try {
                    ship.useSystem()
                } catch (_: Throwable) {
                }
                targetLostTime = 0f
            }
            return
        }

        // 系统已开启：低辐能不主动关闭；仅当“前方目标丢失超过 4s”才关闭
        if (hasAnyForwardTarget) {
            targetLostTime = 0f
            return
        }

        targetLostTime += amount
        if (targetLostTime >= TARGET_LOST_CLOSE_DELAY_SEC) {
            try {
                ship.useSystem()
            } catch (_: Throwable) {
            }
            targetLostTime = 0f
        }
    }

    private data class ForwardScan(
        val hasNonFighter: Boolean,
        val forwardFighterCount: Int,
    )

    private fun scanForwardTargets(engine: CombatEngineAPI, ship: ShipAPI, hinted: ShipAPI?): ForwardScan {
        var hasNonFighter = false
        var fighterCount = 0

        // 1) 优先检查 AI 传入 target / shipTarget（若满足条件可提前判定）
        val candidates = ArrayList<ShipAPI>(2)
        if (hinted != null) candidates.add(hinted)
        try {
            val st = ship.shipTarget
            if (st != null && st !in candidates) candidates.add(st)
        } catch (_: Throwable) {
        }

        for (t in candidates) {
            if (!isValidForwardTarget(engine, ship, t)) continue
            val isFighter = try {
                t.isFighter
            } catch (_: Throwable) {
                false
            }
            if (isFighter) fighterCount++ else hasNonFighter = true
            // 如果已经找到非战机目标，且不关心战机数量，则可早退
            if (hasNonFighter) return ForwardScan(hasNonFighter = true, forwardFighterCount = fighterCount)
        }

        // 2) 扫描所有敌方单位：统计前方战机数量，并记录是否存在非战机目标
        val ships = try {
            engine.ships
        } catch (_: Throwable) {
            null
        }
        if (ships != null) {
            for (s in ships) {
                val t = s as? ShipAPI ?: continue
                if (!isValidForwardTarget(engine, ship, t)) continue

                val isFighter = try {
                    t.isFighter
                } catch (_: Throwable) {
                    false
                }
                if (isFighter) {
                    fighterCount++
                } else {
                    hasNonFighter = true
                }

                // 小优化：找到非战机目标后即可收工（战机计数主要用于“只有战机时也可开机”）
                if (hasNonFighter) break
            }
        }

        return ForwardScan(hasNonFighter = hasNonFighter, forwardFighterCount = fighterCount)
    }

    private fun isValidForwardTarget(engine: CombatEngineAPI, ship: ShipAPI, target: ShipAPI): Boolean {
        if (target === ship) return false
        if (target.isHulk) return false
        if (target.owner == ship.owner) return false
        if (!engine.isEntityInPlay(target)) return false

        val d = MathUtils.getDistance(ship.location, target.location)
        if (d > jetRange) return false

        val angTo = VectorUtils.getAngle(ship.location, target.location)
        val diff = abs(angleDiffDeg(ship.facing, angTo))
        return diff <= FORWARD_CONE_HALF_ANGLE_DEG
    }
}
