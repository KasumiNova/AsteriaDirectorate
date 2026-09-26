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
 * 引力空间复制器系统 AI（舜华级 ZW-101）。
 *
 * 决策口径（每 [SCAN_INTERVAL_SEC] 评估一次，全部满足才施放）：
 * 1. 系统空闲（state == IDLE 且 canBeActivated；冷却/激活中不评估）；
 * 2. 本舰未过载/散辐；
 * 3. **交战状态**：当前目标或任一有效敌舰进入本舰最长能量实弹武器射程 × [ENGAGE_RANGE_MULT]
 *    （owner 100 中立残骸不算交战对象）；
 * 4. **能量武器可输出窗口**：至少一件非装饰、非系统槽、非光束的能量武器可用
 *    （未禁用，备弹武器有余弹）——系统只复制能量武器实弹，无可复制武器时激活是空转。
 */
class GravReplicatorSystemAI : ShipSystemAIScript {

    companion object {
        /** 评估间隔（s）。 */
        private const val SCAN_INTERVAL_SEC = 0.25f

        /** 交战判定距离系数（本舰最长能量实弹武器射程 × 本值）。 */
        private const val ENGAGE_RANGE_MULT = 1.2f
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

        if (!hasReadyEnergyWeapon(ship)) return
        val engageRange = longestEnergyProjectileRange(ship) * ENGAGE_RANGE_MULT
        if (!isEngaged(engine, ship, target, engageRange)) return

        ship.useSystem()
    }

    /** 能量武器可输出窗口：任一非装饰、非系统槽、非光束的能量武器未禁用且有余弹（备弹武器）。 */
    private fun hasReadyEnergyWeapon(ship: ShipAPI): Boolean =
        ship.allWeapons.any { weapon ->
            !weapon.isDecorative &&
                    weapon.slot?.isSystemSlot == false &&
                    weapon.type == WeaponAPI.WeaponType.ENERGY &&
                    !weapon.isBeam &&
                    !weapon.isDisabled &&
                    (!weapon.usesAmmo() || weapon.ammo > 0)
        }

    /** 本舰最长能量实弹武器射程（与复制口径对齐：排除光束；无可用武器时兜底 600su）。 */
    private fun longestEnergyProjectileRange(ship: ShipAPI): Float =
        ship.allWeapons
            .asSequence()
            .filter {
                !it.isDecorative && it.type == WeaponAPI.WeaponType.ENERGY && !it.isBeam
            }
            .map { it.range }
            .filter { it > 0f }
            .maxOrNull() ?: 600f

    /** 交战判定：当前目标有效且在交战距离内，或任一有效敌舰进入交战距离。 */
    private fun isEngaged(engine: CombatEngineAPI, ship: ShipAPI, target: ShipAPI?, engageRange: Float): Boolean {
        if (target != null && isValidEnemy(ship, target) &&
            MathUtils.getDistance(ship.location, target.location) <= engageRange
        ) {
            return true
        }
        return engine.ships.any { other ->
            isValidEnemy(ship, other) &&
                    MathUtils.getDistance(ship.location, other.location) <= engageRange
        }
    }

    private fun isValidEnemy(ship: ShipAPI, other: ShipAPI): Boolean =
        other !== ship && !other.isHulk && other.owner != ship.owner && other.owner != 100 && other.isAlive
}
