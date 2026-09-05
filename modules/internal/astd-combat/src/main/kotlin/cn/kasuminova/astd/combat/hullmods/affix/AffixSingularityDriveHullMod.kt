package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lazywizard.lazylib.MathUtils
import org.magiclib.subsystems.MagicSubsystem
import org.magiclib.subsystems.MagicSubsystemsManager

/**
 * R-17 奇点驱动（affixes.md v3.0）：
 * - 按难度系数获得 300%~900% 峰值时间；降低 20%~40% 武器产生的辐能；
 *   降低 25%~75% 过载时间（最终乘区）；
 * - 免疫原版环境导致的峰值时间降低效果（逐帧仅抵偿已知环境修饰 id 的 mult/percent/flat 域）；
 * - 附带子系统「奇点爆发」（[AffixSingularityBurstSubsystem]，MagicSubsystem）：
 *   激活时逐渐提升 100%~200% 时间流速，持续 10s，冷却 20s，不可充能；
 *   AI 逻辑沿用原版「时流之壳」的启用口径（有目标、处于交战距离、未过载/排辐）。
 */
class AffixSingularityDriveHullMod : BaseHullMod() {

    companion object {
        const val HULLMOD_ID = "astd_affix_singularity_drive"

        val PEAK_CR_BONUS = ScalingEntry(v1 = 3.0f, v2 = 6.0f, v5 = 9.0f)
        val WEAPON_FLUX_REDUCTION = ScalingEntry(v1 = 0.20f, v2 = 0.30f, v5 = 0.40f)
        val OVERLOAD_TIME_REDUCTION = ScalingEntry(v1 = 0.25f, v2 = 0.50f, v5 = 0.75f)

        /** 奇点爆发时间流速提升上限（激活期间从 0 渐增至该值）。 */
        val SINGULARITY_TIME_FLOW_BONUS = ScalingEntry(v1 = 1.0f, v2 = 1.5f, v5 = 2.0f)

        /** 峰值免疫补偿修饰 id（与 hullmod 自身 id 区分，避免误抵偿自身加成）。 */
        const val PEAK_IMMUNITY_MOD_ID_SUFFIX = "_peak_immunity"

        /** 原版战场环境写入峰值时间的修饰 id；其它 id 属于技能、船插或战斗效果，不得抵消。 */
        val ENVIRONMENT_MOD_IDS: Set<String> = setOf(
            "corona",
            "event_horizon",
            "eventHorizon",
            "nebula",
            "star_corona",
            "starCorona",
        )

        private fun isEnvironmentModifier(modId: String, environmentIds: Set<String>): Boolean {
            val normalized = modId.replace("_", "").lowercase()
            return environmentIds.any { it.replace("_", "").lowercase() == normalized }
        }

        /**
         * 峰值时间环境免疫（纯逻辑，测试直调）：统计外部负向修饰并给出反向补偿。
         *
         * @param multBonuses 当前 mult 修饰表（id → 乘值）
         * @param percentBonuses 当前 percent 修饰表（id → 百分值）
         * @param ownIds 自身修饰 id（hullmod id 与免疫补偿 id），不参与统计
         * @return (需要抵偿的乘区, 需要抵偿的百分比)；mult < 1 或 percent < 0 时表示存在环境削减
         */
        fun immunityCompensation(
            multBonuses: Map<String, Float>,
            percentBonuses: Map<String, Float>,
            ownIds: Set<String>,
            environmentIds: Set<String> = ENVIRONMENT_MOD_IDS,
        ): Pair<Float, Float> {
            var penaltyMult = 1f
            for ((modId, value) in multBonuses) {
                if (modId in ownIds || !isEnvironmentModifier(modId, environmentIds)) continue
                if (value < 1f) penaltyMult *= value
            }
            var penaltyPercent = 0f
            for ((modId, value) in percentBonuses) {
                if (modId in ownIds || !isEnvironmentModifier(modId, environmentIds)) continue
                if (value < 0f) penaltyPercent += value
            }
            return penaltyMult to penaltyPercent
        }

        /** 环境写入的 flat 负修正全部抵偿；其它 flat 来源保持不动。 */
        fun environmentFlatCompensation(
            flatBonuses: Map<String, Float>,
            environmentIds: Set<String> = ENVIRONMENT_MOD_IDS,
        ): Float = flatBonuses
            .filter { (modId, value) -> isEnvironmentModifier(modId, environmentIds) && value < 0f }
            .values
            .sumOf { -it.toDouble() }
            .toFloat()
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val tuning = DifficultyTuningImpl
        stats.peakCRDuration.modifyMult(id, 1f + tuning.value(PEAK_CR_BONUS))
        val weaponFluxMult = 1f - tuning.value(WEAPON_FLUX_REDUCTION)
        stats.ballisticWeaponFluxCostMod.modifyMult(id, weaponFluxMult)
        stats.energyWeaponFluxCostMod.modifyMult(id, weaponFluxMult)
        stats.missileWeaponFluxCostMod.modifyMult(id, weaponFluxMult)
        stats.overloadTimeMod.modifyMult(id, 1f - tuning.value(OVERLOAD_TIME_REDUCTION))
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        MagicSubsystemsManager.addSubsystemToShip(ship, AffixSingularityBurstSubsystem(ship))
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val stat = ship.mutableStats.peakCRDuration
        val immunityId = HULLMOD_ID + PEAK_IMMUNITY_MOD_ID_SUFFIX
        stat.unmodify(immunityId)
        if (!ship.isAlive || ship.isHulk) return

        val multBonuses = stat.multBonuses.mapValues { it.value.value }
        val percentBonuses = stat.percentBonuses.mapValues { it.value.value }
        val flatBonuses = stat.flatBonuses.mapValues { it.value.value }
        val (penaltyMult, penaltyPercent) = immunityCompensation(
            multBonuses, percentBonuses, setOf(HULLMOD_ID, immunityId),
        )
        if (penaltyMult < 0.9999f) {
            stat.modifyMult(immunityId, 1f / penaltyMult)
        }
        if (penaltyPercent < -0.01f) {
            stat.modifyPercent(immunityId, -penaltyPercent)
        }
        val flatCompensation = environmentFlatCompensation(flatBonuses)
        if (flatCompensation != 0f) {
            stat.modifyFlat(immunityId, flatCompensation)
        }
    }
}

/**
 * 奇点爆发子系统（R-17 附带）：激活后 10s 内时间流速由 1 渐增至 1 + [难度系数 100%~200%]，
 * 冷却 20s，不可充能；AI 沿用原版「时流之壳」的启用口径。
 */
internal class AffixSingularityBurstSubsystem(ship: ShipAPI) : MagicSubsystem(ship) {

    companion object {
        private const val I18N_CATEGORY = "asteria_directorate_bounty"
        private const val STAT_ID = "astd_affix_singularity_burst"

        /** 「时流之壳」式启用口径的交战距离上限。 */
        private const val ENGAGE_RANGE = 1800f
    }

    override fun getBaseInDuration(): Float = 0.5f
    override fun getBaseActiveDuration(): Float = 10f
    override fun getBaseOutDuration(): Float = 0.5f
    override fun getBaseCooldownDuration(): Float = 20f
    override fun getMaxCharges(): Int = 0

    override fun shouldActivateAI(amount: Float): Boolean {
        // 沿用原版「时流之壳」AI 的启用口径：有舰船目标、处于交战距离、未过载/排辐。
        val target = ship.shipTarget ?: return false
        if (!target.isAlive || target.isHulk) return false
        if (ship.fluxTracker?.isOverloadedOrVenting == true) return false
        return MathUtils.getDistance(ship, target) <= ENGAGE_RANGE
    }

    override fun getDisplayText(): String = I18n[I18N_CATEGORY, "affix.singularity_drive.subsystem.name"]

    override fun getStateText(): String = I18n[I18N_CATEGORY, "affix.singularity_drive.subsystem.state"]

    override fun advance(amount: Float, isPaused: Boolean) {
        if (isPaused) return
        val stats = ship.mutableStats
        stats.timeMult.unmodify(STAT_ID)
        Global.getCombatEngine()?.timeMult?.unmodify(STAT_ID)
        if (!isOn) return

        // 逐渐提升：IN 起幅、ACTIVE 期间随进度线性增至满额、OUT 线性回落。
        val ramp = when {
            isIn -> 0f
            isActive -> stateCompleteRatio
            isOut -> 1f - stateCompleteRatio
            else -> 0f
        }
        val bonus = DifficultyTuningImpl.value(AffixSingularityDriveHullMod.SINGULARITY_TIME_FLOW_BONUS)
        val mult = 1f + bonus * ramp
        if (mult <= 1.0001f) return
        stats.timeMult.modifyMult(STAT_ID, mult)
        // 与原版时流之壳一致：玩家舰加速时反比压缩全局时间，保持主观流速不变。
        val engine = Global.getCombatEngine() ?: return
        if (engine.playerShip === ship) {
            engine.timeMult.modifyMult(STAT_ID, 1f / mult)
        }
    }
}
