package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.util.IntervalUtil

/**
 * 引力空间复制器系统 AI（舜华级 ZW-101）。
 *
 * 决策口径（每 [SCAN_INTERVAL_SEC] 评估一次，全部满足才施放）：
 * 1. 系统空闲（state == IDLE 且 canBeActivated；冷却/激活中不评估）；
 * 2. 本舰未过载/散辐；
 * 3. **开火窗口**：至少一件可复制的能量武器正在开火或充能（[hasFiringEnergyWeapon]）——
 *    复制器窗口与实弹输出窗口对齐。旧口径「交战距离 + 有可用能量武器」会在 AI 尚未开火时
 *    提前激活，复制窗口在接敌前空转（空放技能不开火）。
 *    开火判定带 [FIRE_MEMORY_SEC] 记忆：低频瞬发武器的开火瞬间可能落在两次评估之间，
 *    无记忆会整轮错过复制窗口。
 */
class GravReplicatorSystemAI : ShipSystemAIScript {

    companion object {
        /** 评估间隔（s）。 */
        private const val SCAN_INTERVAL_SEC = 0.25f

        /** 开火窗口记忆（s）：采到开火/充能后保持本时长，覆盖评估间隔漏采的开火瞬间。 */
        private const val FIRE_MEMORY_SEC = 0.5f
    }

    private var ship: ShipAPI? = null
    private var system: ShipSystemAPI? = null
    private var engine: CombatEngineAPI? = null
    private val scanInterval = IntervalUtil(SCAN_INTERVAL_SEC, SCAN_INTERVAL_SEC)

    /** 开火窗口记忆剩余时长（s）：采到开火/充能时重置为 [FIRE_MEMORY_SEC]。 */
    private var fireMemoryRemaining = 0f

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
        fireMemoryRemaining = (fireMemoryRemaining - amount).coerceAtLeast(0f)
        if (system.state != ShipSystemAPI.SystemState.IDLE || !system.canBeActivated()) return
        if (ship.fluxTracker?.isOverloadedOrVenting == true) return

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        if (hasFiringEnergyWeapon(ship)) {
            fireMemoryRemaining = FIRE_MEMORY_SEC
        }
        if (fireMemoryRemaining <= 0f) return
        ship.useSystem()
    }

    /**
     * 开火窗口检测：任一可复制能量武器（非装饰、非系统槽、能量类型、非光束——系统只复制
     * 能量实弹、未禁用、备弹武器有余弹）正在开火或充能时成立。
     * 充能口径取 chargeLevel > 0（原版 ChargeFireTracker：IDLE 恒为 0，充能/激活/降充阶段 > 0），
     * AI 进入充能阶段即激活系统，复制窗口完整覆盖充能-发射全程。
     */
    private fun hasFiringEnergyWeapon(ship: ShipAPI): Boolean =
        ship.allWeapons.any { weapon ->
            !weapon.isDecorative &&
                    weapon.slot?.isSystemSlot == false &&
                    weapon.type == WeaponAPI.WeaponType.ENERGY &&
                    !weapon.isBeam &&
                    !weapon.isDisabled &&
                    (!weapon.usesAmmo() || weapon.ammo > 0) &&
                    (weapon.isFiring || weapon.chargeLevel > 0f)
        }
}
