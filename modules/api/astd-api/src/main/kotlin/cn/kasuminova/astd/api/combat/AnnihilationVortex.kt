package cn.kasuminova.astd.api.combat

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import org.lwjgl.util.vector.Vector2f

/**
 * 一次弹体吸收的结算数据。
 *
 * @property type 被吸收弹体的伤害类型
 * @property baseDamage 弹体的基础伤害
 * @property location 弹体被移除时的位置
 */
data class AbsorbedShot(
    val type: DamageType,
    val baseDamage: Float,
    val location: Vector2f,
)

/**
 * 一帧牵引与吸收的结算结果。
 *
 * @property absorbed 本帧移除的弹体
 * @property pulledCount 本帧被牵引但未被移除的弹体数
 */
data class AbsorbOutcome(
    val absorbed: List<AbsorbedShot>,
    val pulledCount: Int,
)

/**
 * 对湮灭涡旋半径内的敌方弹体进行牵引与吸收。
 *
 * 每帧仅在战斗主线程调用。[advance] 会修改弹体速度并移除进入吸收半径的弹体，
 * 对每次移除调用特效回调。池值累计由调用方根据返回结果完成。
 */
interface AnnihilationVortexAbsorb {

    /**
     * 推进一帧牵引与吸收，返回本帧结算结果。
     *
     * @param engine 当前战斗引擎
     * @param center 涡旋中心
     * @param radius 涡旋半径；非正数由实现限制到最小值并记录 WARN
     * @param absorbRadius 吸收半径
     * @param sourceOwner 涡旋归属方，仅处理其他归属方的弹体
     * @param amount 帧长，单位为秒
     * @param onAbsorbedFx 每次吸收时以移除位置调用
     */
    fun advance(
        engine: CombatEngineAPI,
        center: Vector2f,
        radius: Float,
        absorbRadius: Float,
        sourceOwner: Int,
        amount: Float,
        onAbsorbedFx: (Vector2f) -> Unit = {},
    ): AbsorbOutcome
}
