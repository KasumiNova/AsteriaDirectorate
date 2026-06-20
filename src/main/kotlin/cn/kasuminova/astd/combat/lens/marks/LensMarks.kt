package cn.kasuminova.astd.combat.lens.marks

import cn.kasuminova.astd.combat.buffs.ShipBuffApplier
import cn.kasuminova.astd.combat.buffs.StackingBuffSpec
import cn.kasuminova.astd.combat.buffs.StackingBuffState
import cn.kasuminova.astd.combat.buffs.StackingShipBuffs
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 引力透镜级标记闭环的落地：在通用 StackingShipBuffs 框架上定义误差/深水两类标记。
 *
 * 动机（spec §1.1）：
 * - 误差标记：提升目标受到的伤害（收割端在核心·载人模式读取并增伤）。
 * - 深水标记：电战压制——降低目标武器射程/精度/航速、以及对引力透镜造成的伤害。
 * 两类均 10 层、每层 5s、叠加刷新。
 *
 * 注意：MutableShipStatsAPI 无通用 damageTakenMult，
 * 误差标记采用 hullDamageTakenMult + armorDamageTakenMult 覆盖全部船体/装甲伤害，
 * 与项目内 AffixCoherentLinkHullMod / AffixReactorBackfeedHullMod 一致。
 */
object LensMarks {

    /** dynamic stat key：深水标记令目标"对引力透镜造成的伤害"下降。 */
    const val VS_LENS_DAMAGE_MULT_KEY: String = "astd_lens_deep_water_vs_lens_mult"

    // accuracyMult=0（理论极端，精度完全归零）时的 recoil 倍率上限，避免除零。
    private const val MAX_RECOIL_MULT_CAP = 99f

    private val driftSpec = StackingBuffSpec(
        id = LensMarkIds.DRIFT_BUFF_ID,
        baseDuration = LensMarkIds.MARK_DURATION_SEC,
        baseMaxStacks = LensMarkMath.MAX_STACKS,
        baseMagnitudeMult = 1f,
    )

    // 深水各效果量已在 LensMarkMath 直接按层数计算，故不使用 magnitudeMult。
    private val deepWaterSpec = StackingBuffSpec(
        id = LensMarkIds.DEEP_WATER_BUFF_ID,
        baseDuration = LensMarkIds.MARK_DURATION_SEC,
        baseMaxStacks = LensMarkMath.MAX_STACKS,
    )

    private val driftApplier = object : ShipBuffApplier {
        override fun applyTo(ship: ShipAPI, buffId: String, state: StackingBuffState) {
            val mult = LensMarkMath.driftDamageTakenMult(state.stacks, state.magnitudeMult)
            ship.mutableStats.hullDamageTakenMult.modifyMult(buffId, mult)
            ship.mutableStats.armorDamageTakenMult.modifyMult(buffId, mult)
        }

        override fun unapplyFrom(ship: ShipAPI, buffId: String) {
            ship.mutableStats.hullDamageTakenMult.unmodify(buffId)
            ship.mutableStats.armorDamageTakenMult.unmodify(buffId)
        }
    }

    private val deepWaterApplier = object : ShipBuffApplier {
        override fun applyTo(ship: ShipAPI, buffId: String, state: StackingBuffState) {
            val stacks = state.stacks
            val rangeMult = LensMarkMath.deepWaterRangeMult(stacks)
            ship.mutableStats.weaponRangeThreshold.modifyMult(buffId, rangeMult)

            val accuracyMult = LensMarkMath.deepWaterAccuracyMult(stacks)
            val recoilMult = if (accuracyMult > 0f) 1f / accuracyMult else MAX_RECOIL_MULT_CAP
            ship.mutableStats.maxRecoilMult.modifyMult(buffId, recoilMult)
            ship.mutableStats.recoilDecayMult.modifyMult(buffId, recoilMult)

            ship.mutableStats.maxSpeed.modifyMult(buffId, LensMarkMath.deepWaterSpeedMult(stacks))
            ship.mutableStats.dynamic.getMod(VS_LENS_DAMAGE_MULT_KEY).modifyMult(buffId, LensMarkMath.deepWaterVsLensDamageMult(stacks))
        }

        override fun unapplyFrom(ship: ShipAPI, buffId: String) {
            ship.mutableStats.weaponRangeThreshold.unmodify(buffId)
            ship.mutableStats.maxRecoilMult.unmodify(buffId)
            ship.mutableStats.recoilDecayMult.unmodify(buffId)
            ship.mutableStats.maxSpeed.unmodify(buffId)
            ship.mutableStats.dynamic.getMod(VS_LENS_DAMAGE_MULT_KEY).unmodify(buffId)
        }
    }

    /** 叠 1（或多）层误差标记（系统回声定影 / 视差甲板机群调用）。 */
    fun applyDriftMark(engine: CombatEngineAPI, source: CombatEntityAPI?, target: ShipAPI, addStacks: Int = 1) {
        StackingShipBuffs.applyOrRefresh(engine, source, target, driftSpec, driftApplier, addStacks)
    }

    /** 叠 1（或多）层深水标记（渗透潮汐调用）。 */
    fun applyDeepWaterMark(engine: CombatEngineAPI, source: CombatEntityAPI?, target: ShipAPI, addStacks: Int = 1) {
        StackingShipBuffs.applyOrRefresh(engine, source, target, deepWaterSpec, deepWaterApplier, addStacks)
    }

    /** 读取目标当前误差标记层数（收割端/状态栏用）。 */
    fun driftStacks(ship: ShipAPI): Int = StackingShipBuffs.getState(ship, LensMarkIds.DRIFT_BUFF_ID)?.stacks ?: 0

    /** 读取目标当前深水标记层数（收割端/状态栏用）。 */
    fun deepWaterStacks(ship: ShipAPI): Int = StackingShipBuffs.getState(ship, LensMarkIds.DEEP_WATER_BUFF_ID)?.stacks ?: 0

    /**
     * 渗透潮汐「过载退潮」：清空全场所有舰船的误差 + 深水标记（含 stat 效果即时移除）。
     *
     * 动机（spec §阶段二）：退潮瞬间需让标记的 stat 效果立刻消失，
     * 因此走 StackingShipBuffs.clear（先 unapply 再清 customData），而非等插件下一帧自然到期。
     */
    fun clearAllLensMarks(engine: CombatEngineAPI) {
        for (ship in engine.ships) {
            if (ship == null) continue
            StackingShipBuffs.clear(engine, ship, LensMarkIds.DRIFT_BUFF_ID)
            StackingShipBuffs.clear(engine, ship, LensMarkIds.DEEP_WATER_BUFF_ID)
        }
    }
}
