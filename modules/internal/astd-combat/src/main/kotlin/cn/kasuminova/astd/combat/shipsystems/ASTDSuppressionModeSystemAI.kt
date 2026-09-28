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
 * 压制模式系统 AI：权衡射程/射速收益与机动惩罚+硬辐能自伤，只在"站桩输出窗口"开启。
 *
 * 开启条件（全部满足）：
 * - 自身辐能水平低于 [MAX_FLUX_LEVEL_TO_ACTIVATE]（系统持续产硬辐能，高辐能开启等于自压）；
 * - 无来袭导弹/碰撞威胁（机动惩罚期躲不开威胁）；
 * - 无近距敌舰逼近/缠斗（最近敌舰距离低于 [CLOSE_THREAT_RANGE_FRACTION] 倍最长射程且以本舰
 *   为目标时视为被追击/近战，机动惩罚致命）；
 * - 存在有效目标且进入交战带：距离 ≤ 最长射程 × [ENGAGE_RANGE_MULT]（压制模式射程加成后
 *   能实际覆盖）；目标过载/排气/高辐能（输出窗口）时放宽到 [PUNISH_RANGE_MULT]。
 */
class ASTDSuppressionModeSystemAI : ShipSystemAIScript {

    companion object {
        private const val SCAN_INTERVAL_SEC = 0.25f
        private const val MAX_FLUX_LEVEL_TO_ACTIVATE = 0.5f
        private const val CLOSE_THREAT_RANGE_FRACTION = 0.45f
        private const val ENGAGE_RANGE_MULT = 1.05f
        private const val PUNISH_RANGE_MULT = 1.35f
        private const val VULNERABLE_FLUX_LEVEL = 0.6f
        private const val MIN_WEAPON_RANGE_FALLBACK = 700f
        private const val REACTIVATE_DELAY_SEC = 1f
    }

    private var ship: ShipAPI? = null
    private var system: ShipSystemAPI? = null
    private var engine: CombatEngineAPI? = null
    private val scanInterval = IntervalUtil(SCAN_INTERVAL_SEC, SCAN_INTERVAL_SEC)
    private var lastUseAt = -999f

    override fun init(ship: ShipAPI, system: ShipSystemAPI, flags: ShipwideAIFlags, engine: CombatEngineAPI) {
        this.ship = ship
        this.system = system
        this.engine = engine
        scanInterval.forceIntervalElapsed()
        lastUseAt = -999f
    }

    override fun advance(amount: Float, missileDangerDir: Vector2f?, collisionDangerDir: Vector2f?, target: ShipAPI?) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return
        if (engine.isPaused || ship.isHulk || !ship.isAlive) return
        if (system.isOn || system.state != ShipSystemAPI.SystemState.IDLE) return
        if (!system.canBeActivated()) return

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        val tracker = ship.fluxTracker ?: return
        if (tracker.isOverloadedOrVenting) return
        if (tracker.fluxLevel >= MAX_FLUX_LEVEL_TO_ACTIVATE) return

        // 机动惩罚期内无法规避威胁：来袭导弹/碰撞告警直接不开
        if (missileDangerDir != null && missileDangerDir.lengthSquared() > 0.01f) return
        if (collisionDangerDir != null && collisionDangerDir.lengthSquared() > 0.01f) return

        val now = engine.getTotalElapsedTime(false)
        if (now - lastUseAt < REACTIVATE_DELAY_SEC) return

        val range = longestNonMissileWeaponRange(ship).coerceAtLeast(MIN_WEAPON_RANGE_FALLBACK)

        // 近距敌舰以本舰为目标（被追击/近战缠斗）时不开：机动惩罚会被直接惩罚
        val nearest = nearestEnemy(ship, engine) ?: return
        val nearestDist = MathUtils.getDistance(ship.location, nearest.location)
        if (nearestDist <= range * CLOSE_THREAT_RANGE_FRACTION && nearest.shipTarget === ship) return

        val actualTarget = validTarget(ship, engine, target)
            ?: validTarget(ship, engine, ship.shipTarget)
            ?: nearest
        val distance = MathUtils.getDistance(ship.location, actualTarget.location)
        val targetTracker = actualTarget.fluxTracker
        val targetVulnerable = targetTracker != null &&
                (targetTracker.isOverloadedOrVenting || targetTracker.fluxLevel >= VULNERABLE_FLUX_LEVEL)

        val engageLimit = range * if (targetVulnerable) PUNISH_RANGE_MULT else ENGAGE_RANGE_MULT
        if (distance > engageLimit) return

        ship.useSystem()
        lastUseAt = now
    }

    private fun nearestEnemy(ship: ShipAPI, engine: CombatEngineAPI): ShipAPI? =
        engine.ships
            .asSequence()
            .filter { validTarget(ship, engine, it) != null }
            .minByOrNull { MathUtils.getDistance(ship.location, it.location) }

    private fun validTarget(ship: ShipAPI, engine: CombatEngineAPI, target: ShipAPI?): ShipAPI? {
        if (target == null || target === ship || target.isHulk || target.owner == ship.owner) return null
        if (!engine.isEntityInPlay(target)) return null
        return target
    }

    private fun longestNonMissileWeaponRange(ship: ShipAPI): Float =
        ship.allWeapons
            .asSequence()
            .filter { !it.isDecorative }
            .filter { it.type != WeaponAPI.WeaponType.MISSILE }
            .map { it.range }
            .filter { it > 0f }
            .maxOrNull() ?: 0f
}
