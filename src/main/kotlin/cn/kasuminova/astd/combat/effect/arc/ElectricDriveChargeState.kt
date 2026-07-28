package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.buff.Buff
import cn.kasuminova.astd.api.buff.BuffLifetime
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI

/**
 * 电驱加速炮不稳定装药的 Weapon 级随机序列载体（规格 03 §2.1/§2.3）。
 *
 * 动机：装药附加伤害的结算随机必须走确定性序列（同帧同事件不二次取值、同事件重放结果一致）。
 * `WeaponAPI` 无 customData（jar 已核实），调用序 callIndex 只能挂舰船侧复合键
 * `astd_buff:weapon:<id>:<slotId>`，经 `ShipAPI.getOrCreateBuffByWeapon` 登记；
 * 每武器实例一个确定性序列（[seed] 由 [ElectricDriveAcceleratorDifficulty.seedOf] 派生）。
 *
 * 本类为纯载体：无衰减语义，[advance]/[onRemove] 保持默认空实现；
 * [nextCallIndex] 单调递增不复位——同帧 LINKED 双管两发是两个独立事件，各自取值。
 *
 * 生命周期：[BuffLifetime.HOST_BOUND]；[isHostValid] 语义 = 宿主舰存活且在场
 * 且登记武器的 `spec.weaponId` 仍为本武器（换装后旧 Buff 失效，由 BuffTickPlugin
 * 复合键 weaponMatches 判定与宿主有效性双重路径回收，对齐 00 §6 换装回收验证）。
 */
class ElectricDriveChargeState(
    /** 宿主舰（创建时捕获；宿主有效性判定）。 */
    private val ship: ShipAPI,
    /** 登记武器（创建时捕获；weaponId 匹配判定）。 */
    private val weapon: WeaponAPI,
    /** 确定性序列种子（战斗内稳定）。 */
    val seed: Long,
) : Buff {

    /** 结算随机调用序：单调递增不复位（双管同帧两发取值不同的前置保证）。 */
    var callIndex: Int = 0
        private set

    /** 取下一个调用序并自增。 */
    fun nextCallIndex(): Int = callIndex++

    override val id: String get() = BUFF_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND

    override fun isHostValid(): Boolean =
        ship.isAlive && !ship.isHulk && weapon.spec?.weaponId == WEAPON_ID

    companion object {
        /** Weapon 级 Buff 登记 id（复合键 `astd_buff:weapon:<id>:<slotId>` 的键段）。 */
        const val BUFF_ID = "astd_eda_charge_state"

        /** 本武器 id（换装检测基准）。 */
        const val WEAPON_ID = "astd_electric_drive_accelerator"
    }
}
