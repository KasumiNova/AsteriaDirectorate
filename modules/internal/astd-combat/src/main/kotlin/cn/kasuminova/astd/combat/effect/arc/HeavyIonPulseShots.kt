package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.buff.Buff
import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.impl.combat.CombatRandom
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI

/**
 * 泄放概率结算随机的 Weapon 级状态位（纯标记 Buff，规格 02 §2.1）。
 *
 * 动机：`WeaponAPI` 无 customData（jar 已核实），泄放随机的调用序 callIndex 只能挂舰船侧复合键；
 * 每武器实例一个确定性序列（seed 派生 `ship.id × 31 + slot.id`，战斗内稳定），
 * 保证同帧同事件不二次取值、同事件重放结果一致。
 *
 * 生命周期：Weapon 级复合键登记（[BuffLifetime.HOST_BOUND]）；槽位换装/空槽由 BuffTickPlugin
 * 自动回收（weaponMatches 判定），callIndex 无泄漏，本类无需自管理。
 */
class HeavyIonPulseShots(
    /** 确定性序列种子（由 [CombatRandom.seedOf] 派生）。 */
    val seed: Long,
) : Buff {

    /** 泄放结算随机调用序：每次判定取值后自增。 */
    var callIndex: Int = 0

    constructor(source: ShipAPI, weapon: WeaponAPI) : this(CombatRandom.seedOf(source.id, weapon.slot.id))

    override val id: String get() = SHOTS_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND

    /** 武器级回收由 BuffTickPlugin 的换装/空槽判定承担，宿主舰有效性同理，恒 true。 */
    override fun isHostValid(): Boolean = true

    companion object {
        /** Weapon 级 Buff 登记 id（复合键 `astd_buff:weapon:<id>:<slotId>` 的键段）。 */
        const val SHOTS_ID = "astd_heavy_ion_pulse_shots"
    }
}
