package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipwideAIFlags
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.AIUtils

/**
 * R-15 电网深化升级（affixes.md v3.0）：
 * - 强制获得 S-02 六相冰辐能网络与 M-10 反应式辐能装甲的提升效果（复用其实现；
 *   M-10 的 -35% 排辐速率惩罚不属于提升效果，不捆绑）；
 * - 按难度系数提升 25%~50% 强制耗散速率、10%~20% 硬辐能耗散速率（最终乘区）；
 * - 优化自动驾驶（含友军舰船）AI 的强制排辐决策，使其更智能、激进
 *   （决策结构参考 Polaris_Prime 的排辐优化 AI：威胁评估 + AI 旗标引导 + 主动排辐指令）；
 * - 与 S-03 极限辐能线圈扩容互斥（互斥表见 AffixRegistry）。
 */
class AffixGridDeepeningHullMod : BaseHullMod() {

    companion object {
        const val HULLMOD_ID = "astd_affix_grid_deepening"

        /** 强制耗散（排辐）速率提升（最终乘区）。 */
        val VENT_RATE_BONUS = ScalingEntry(v1 = 0.25f, v2 = 0.375f, v5 = 0.50f)

        /** 硬辐能耗散速率提升（最终乘区）。 */
        val HARD_FLUX_DISSIPATION_BONUS = ScalingEntry(v1 = 0.10f, v2 = 0.15f, v5 = 0.20f)

        // ─── 排辐 AI 决策参数 ───
        /** 决策节流间隔（秒）。 */
        const val VENT_AI_INTERVAL = 0.25f

        /** 高于该辐能水平时允许在威胁较近时激进排辐。 */
        const val AGGRESSIVE_VENT_FLUX_LEVEL = 0.75f

        /** 高于该辐能水平且脱离接触时引导排辐。 */
        const val SAFE_VENT_FLUX_LEVEL = 0.50f

        /** 激进排辐的"危险距离"：敌舰近于此距离时即使高辐能也不主动排辐。 */
        const val DANGER_CLOSE_RANGE = 700f

        /** "脱离接触"判定距离：最近敌舰远于此距离时主动排辐并置 SAFE_VENT 旗标。 */
        const val DISENGAGED_RANGE = 1500f

        private const val VENT_AI_TIMER_KEY = "astd_affix_grid_deepening_vent_ai"

        /**
         * 排辐决策（纯逻辑，测试直调）：
         * - 辐能 ≥ 75%：敌舰不在危险距离内即可激进排辐；
         * - 辐能 ≥ 50%：仅在脱离接触（最近敌舰距离 > [DISENGAGED_RANGE] 或无敌舰）时排辐；
         * - 其余不主动排辐。
         */
        fun shouldVent(fluxLevel: Float, nearestEnemyDistance: Float): Boolean = when {
            fluxLevel >= AGGRESSIVE_VENT_FLUX_LEVEL -> nearestEnemyDistance > DANGER_CLOSE_RANGE
            fluxLevel >= SAFE_VENT_FLUX_LEVEL -> nearestEnemyDistance > DISENGAGED_RANGE
            else -> false
        }

        fun applyStatic(stats: MutableShipStatsAPI, id: String, tuning: DifficultyTuning) {
            // 捆绑 S-02 六相冰辐能网络全部效果。
            AffixCryoFluxNetworkHullMod.apply(stats, id, tuning)
            // 捆绑 M-10 反应式辐能装甲的提升效果（排辐期伤害减免在逐帧路径）；
            // 设计原文为"提升效果"，M-10 的 -35% 排辐速率惩罚不属于提升，不捆绑。
            // 自身数值。
            stats.ventRateMult.modifyMult(id, 1f + tuning.value(VENT_RATE_BONUS))
            stats.hardFluxDissipationFraction.modifyMult(id, 1f + tuning.value(HARD_FLUX_DISSIPATION_BONUS))
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        applyStatic(stats, id, DifficultyTuningImpl)
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        // 捆绑 M-10 排辐期防御。
        AffixReactiveFluxArmorHullMod.applyVentingDefense(ship, HULLMOD_ID, DifficultyTuningImpl)
        advanceVentAI(ship, amount)
    }

    /**
     * 排辐 AI 行为侧：仅作用于 AI 驾驶的舰船（玩家手操不干预）。
     * 参考 Polaris_Prime 排辐优化：威胁评估决定是否激进排辐，同时用 AI 旗标
     * （移除 DO_NOT_VENT / 置 SAFE_VENT）引导原版排辐决策。
     */
    private fun advanceVentAI(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || !ship.isAlive || ship.isHulk) return
        if (ship.shipAI == null) return

        val flux = ship.fluxTracker ?: return
        if (flux.isOverloadedOrVenting) return

        val timer = (ship.customData[VENT_AI_TIMER_KEY] as? Float ?: 0f) - amount
        if (timer > 0f) {
            ship.setCustomData(VENT_AI_TIMER_KEY, timer)
            return
        }
        ship.setCustomData(VENT_AI_TIMER_KEY, VENT_AI_INTERVAL)

        val fluxLevel = flux.fluxLevel
        if (fluxLevel < SAFE_VENT_FLUX_LEVEL) return

        val nearestEnemy = AIUtils.getNearestEnemy(ship)
        val nearestDistance = if (nearestEnemy != null) MathUtils.getDistance(ship, nearestEnemy) else Float.MAX_VALUE

        // 更激进：高辐能时摘掉原版"禁止排辐"约束。
        ship.aiFlags.removeFlag(ShipwideAIFlags.AIFlags.DO_NOT_VENT)
        if (nearestDistance > DISENGAGED_RANGE) {
            // 脱离接触：置 SAFE_VENT 让原版 AI 更倾向排辐。
            ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.SAFE_VENT, VENT_AI_INTERVAL * 2f)
        }
        if (shouldVent(fluxLevel, nearestDistance)) {
            ship.giveCommand(ShipCommand.VENT_FLUX, null, 0)
        }
    }
}
