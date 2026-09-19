package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils

/**
 * 落叶飞花系统 AI（2026-09 重写）：按充能余量分两档决策。
 *
 * - **充能 > 1：为弹匣武器充能**——只在交战时（敌舰进入本舰最长非导弹武器射程 ×1.2），
 *   任一可装填的非导弹武器剩余弹药低于 50% 即激活（导弹弹药不在系统的备弹恢复通道内，
 *   不参与判定）。
 * - **充能 > 最大充能数 - 1（满充）：调整战术位置**——只在赶路（舰速超过 50% 最大航速）
 *   或交战时激活，利用冲刺动量快速位移。
 *
 * 扫描间隔 0.25s；过载/散辐中不激活。
 */
class ASTDBurstFlowSystemAI : ShipSystemAIScript {

    companion object {
        private const val SCAN_INTERVAL_SEC = 0.25f

        /** 交战判定距离系数（本舰最长非导弹武器射程 × 本值）。 */
        private const val ENGAGE_RANGE_MULT = 1.2f

        /** 弹匣武器充能决策的剩余弹药阈值（占最大备弹比例）。 */
        private const val AMMO_LOW_RATIO = 0.5f

        /** 赶路判定速度阈值（占最大航速比例）。 */
        private const val TRAVEL_SPEED_RATIO = 0.5f
    }

    private var ship: ShipAPI? = null
    private var system: ShipSystemAPI? = null
    private var engine: CombatEngineAPI? = null
    private val scanInterval = IntervalUtil(SCAN_INTERVAL_SEC, SCAN_INTERVAL_SEC)

    override fun init(ship: ShipAPI, system: ShipSystemAPI, flags: ShipwideAIFlags, engine: CombatEngineAPI) {
        this.ship = ship
        this.system = system
        this.engine = engine
        scanInterval.forceIntervalElapsed()
    }

    override fun advance(
        amount: Float,
        missileDangerDir: org.lwjgl.util.vector.Vector2f?,
        collisionDangerDir: org.lwjgl.util.vector.Vector2f?,
        target: ShipAPI?,
    ) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return
        if (engine.isPaused || ship.isHulk) return
        if (system.state != ShipSystemAPI.SystemState.IDLE || !system.canBeActivated()) return
        if (ship.fluxTracker?.isOverloadedOrVenting == true) return

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        val engaged = isEngaged(ship, engine)

        // 决策一：为弹匣武器充能（充能 > 1，仅交战时）。
        if (engaged && system.ammo > 1 && hasLowAmmoWeapon(ship)) {
            ship.useSystem()
            return
        }

        // 决策二：调整战术位置（满充才允许动用，仅赶路或交战时）。
        if (system.ammo > system.maxAmmo - 1 && (engaged || isTraveling(ship))) {
            ship.useSystem()
        }
    }

    /** 任一非装饰、非系统槽、非导弹的可装填武器剩余弹药 < [AMMO_LOW_RATIO]（与系统备弹恢复通道对齐，排除导弹）。 */
    private fun hasLowAmmoWeapon(ship: ShipAPI): Boolean =
        ship.allWeapons.any { weapon ->
            !weapon.isDecorative &&
                weapon.slot?.isSystemSlot == false &&
                weapon.type != WeaponAPI.WeaponType.MISSILE &&
                weapon.usesAmmo() &&
                weapon.maxAmmo > 0 &&
                weapon.ammo.toFloat() / weapon.maxAmmo < AMMO_LOW_RATIO
        }

    /** 任一有效敌舰进入本舰最长非导弹武器射程 × [ENGAGE_RANGE_MULT]（owner 100 中立残骸不算交战对象）。 */
    private fun isEngaged(ship: ShipAPI, engine: CombatEngineAPI): Boolean {
        val range = longestNonMissileRange(ship) * ENGAGE_RANGE_MULT
        return engine.ships.any { other ->
            other !== ship &&
                !other.isHulk &&
                other.owner != ship.owner &&
                other.owner != 100 &&
                MathUtils.getDistance(ship.location, other.location) <= range
        }
    }

    /** 赶路：舰速超过 50% 最大航速。 */
    private fun isTraveling(ship: ShipAPI): Boolean =
        ship.velocity.length() > ship.mutableStats.maxSpeed.modifiedValue * TRAVEL_SPEED_RATIO

    private fun longestNonMissileRange(ship: ShipAPI): Float =
        ship.allWeapons
            .asSequence()
            .filter { !it.isDecorative && it.type != WeaponAPI.WeaponType.MISSILE }
            .map { it.range }
            .filter { it > 0f }
            .maxOrNull() ?: 600f
}
