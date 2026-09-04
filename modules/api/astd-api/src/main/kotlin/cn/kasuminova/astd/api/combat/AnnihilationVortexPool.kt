package cn.kasuminova.astd.api.combat

import cn.kasuminova.astd.api.buff.Buff
import com.fs.starfarer.api.combat.DamageType

/**
 * 湮灭涡旋吞噬池：单武器一次开火周期的吞噬累计载体（规格 04 §2.1）。
 *
 * 动机：湮灭涡旋光束持续期间吸收的敌方射弹/导弹，按类型转换比折算后累计入池，
 * 停火时一次性消费（坍缩爆炸伤害基数）；池生命周期不随宿主（[cn.kasuminova.astd.api.buff.BuffLifetime.SELF_MANAGED]），
 * 宿主失效时自回收且不触发坍缩（宿主死亡涡旋哑火是机制明确行为）。
 *
 * 挂载：经 `ShipAPI.getOrCreateBuffByWeapon("astd_annihilation_vortex_pool", weapon, ...)` 挂 BuffHost
 * 武器级复合键；每个开火周期由 BeamEffect 在停火坍缩后移除，下周期重建。
 */
interface AnnihilationVortexPool : Buff {

    /**
     * 折算后池值（类型转换比 + 软上限折算后的累计量）；坍缩伤害基数读取处。
     */
    val convertedTotal: Float

    /**
     * 本周期已吸收弹体数（含 0 伤害弹体）；遥测与目检证据用。
     */
    val absorbedCount: Int

    /**
     * 难度折算后的吸收阈值（软上限拐点）：池值在阈值内全额入池，超出部分按超额折算比计入。
     */
    val threshold: Float

    /**
     * 记录一次吸收：[type] 伤害类型决定转换比，[baseDamage] 为弹体面板伤害（getBaseDamageAmount 口径）。
     * 内部完成类型转换 + 软上限分段折算后累计入池。
     *
     * @return 实际入池量（折算后；供玩家可见浮字与遥测）
     */
    fun addAbsorbed(type: DamageType, baseDamage: Float): Float
}
