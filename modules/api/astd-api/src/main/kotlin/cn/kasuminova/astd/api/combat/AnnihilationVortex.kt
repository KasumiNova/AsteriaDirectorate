package cn.kasuminova.astd.api.combat

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import org.lwjgl.util.vector.Vector2f

/**
 * 一次吸收的结算快照（规格 04 §2.1）。
 *
 * @property type 被吸收弹体的伤害类型（决定类型转换比）
 * @property baseDamage 被吸收弹体的面板伤害（`getBaseDamageAmount` 口径，设计：「其面板伤害」）
 * @property location 吸收发生位置（吸收 flare 起点与浮字锚点）
 */
data class AbsorbedShot(
    val type: DamageType,
    val baseDamage: Float,
    val location: Vector2f,
)

/**
 * 湮灭涡旋一帧牵引/吸收的结算输出（规格 04 §2.1）。
 *
 * @property absorbed 本帧被吸收弹体清单（类型 + 面板伤害 + 位置）；池记账与浮字由调用方逐条消费
 * @property pulledCount 本帧被牵引（未达吸收条件、速度被改写）的弹体数；遥测用
 */
data class AbsorbOutcome(
    val absorbed: List<AbsorbedShot>,
    val pulledCount: Int,
)

/**
 * 湮灭涡旋每帧牵引/吸收结算（规格 04 §2.1）。
 *
 * 动机：把「涡旋半径内敌方射弹/导弹的空间筛选、指向中心的牵引加速、吸收半径内的移除」
 * 收敛为纯结算面——不含 VFX、不含池记账（吸收 flare 经 [advance] 的回调抛出、池记账由调用方逐条做），
 * 使单元测试可直接驱动结算逻辑，BeamEffect 只负责接线。
 *
 * 仅作用于敌方弹体（`owner != sourceOwner`；定稿裁定）；`MissileAPI` 是
 * `DamagingProjectileAPI` 子接口，导弹天然覆盖，无需分支。
 */
interface AnnihilationVortexAbsorb {

    /**
     * 推进一帧牵引/吸收。
     *
     * @param engine 战斗引擎（吸收移除弹体用）
     * @param center 涡旋中心（光束终点 `beam.to`）
     * @param radius 涡旋半径（难度折算后；<= 0 属配置错误，实现 clamp 到最小值并记 WARN）
     * @param absorbRadius 吸收半径（`max(30, radius × 0.25)`，由调用方算好传入）
     * @param sourceOwner 涡旋归属方（仅牵引/吸收 `owner != sourceOwner` 的弹体）
     * @param amount 帧长（秒）
     * @param onAbsorbedFx 吸收事件回调（吸收 flare 起点抛给特效侧；位置即弹体被移除处）
     * @return 本帧被吸收清单与被牵引计数
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
