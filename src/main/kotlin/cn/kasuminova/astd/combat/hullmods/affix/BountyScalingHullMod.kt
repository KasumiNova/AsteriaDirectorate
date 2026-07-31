package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 赏金舰队基础数值缩放（轨一：固有缩放系数）。
 *
 * 三锚点登记：v1=迟暮（克制下限，无加成）/ v2=砺刃（设计基准，即旧版 k=1 时的加成值）/ v5=破晓（放开上限）。
 * 只作用于敌方赏金舰队——该船插只会被赏金生成器装上敌舰，因此无需玩家侧判定。
 */
class BountyScalingHullMod : BaseHullMod() {

    /** 五项缩放倍率的计算结果。 */
    data class Bonuses(
        val hull: Float,
        val armor: Float,
        val fluxCapacity: Float,
        val fluxDissipation: Float,
        val maxSpeed: Float,
    )

    companion object {
        private val HULL_BONUS = ScalingEntry(v1 = 1.00f, v2 = 1.05f, v5 = 1.20f)
        private val ARMOR_BONUS = ScalingEntry(v1 = 1.00f, v2 = 1.05f, v5 = 1.20f)
        private val FLUX_CAPACITY = ScalingEntry(v1 = 1.00f, v2 = 1.04f, v5 = 1.16f)
        private val FLUX_DISSIPATION = ScalingEntry(v1 = 1.00f, v2 = 1.06f, v5 = 1.24f)
        private val MAX_SPEED = ScalingEntry(v1 = 1.00f, v2 = 1.02f, v5 = 1.08f)

        /** 按给定难度读取面计算五项缩放倍率（完整逻辑，实现与测试共用）。 */
        fun bonuses(tuning: DifficultyTuning): Bonuses = Bonuses(
            hull = tuning.value(HULL_BONUS),
            armor = tuning.value(ARMOR_BONUS),
            fluxCapacity = tuning.value(FLUX_CAPACITY),
            fluxDissipation = tuning.value(FLUX_DISSIPATION),
            maxSpeed = tuning.value(MAX_SPEED),
        )
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val b = bonuses(DifficultyTuningImpl)
        ship.mutableStats.hullBonus.modifyMult(id, b.hull)
        ship.mutableStats.armorBonus.modifyMult(id, b.armor)
        ship.mutableStats.fluxCapacity.modifyMult(id, b.fluxCapacity)
        ship.mutableStats.fluxDissipation.modifyMult(id, b.fluxDissipation)
        ship.mutableStats.maxSpeed.modifyMult(id, b.maxSpeed)
    }
}
