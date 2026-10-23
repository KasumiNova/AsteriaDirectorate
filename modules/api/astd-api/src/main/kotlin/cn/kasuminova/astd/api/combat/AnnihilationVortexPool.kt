package cn.kasuminova.astd.api.combat

import cn.kasuminova.astd.api.buff.Buff
import com.fs.starfarer.api.combat.DamageType

/**
 * 累计单个武器在一次开火周期内吸收的弹体伤害。
 *
 * 伤害按类型和软上限折算。宿主失效时移除自身且不触发坍缩；停火结算后由调用方移除。
 * 所有方法仅在战斗主线程调用。
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

    /** 按伤害类型与软上限累计 [baseDamage]，返回实际计入池中的数值。 */
    fun addAbsorbed(type: DamageType, baseDamage: Float): Float
}
