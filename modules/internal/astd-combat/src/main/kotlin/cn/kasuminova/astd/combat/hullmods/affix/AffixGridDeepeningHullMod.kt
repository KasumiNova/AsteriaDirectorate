package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import org.lazywizard.lazylib.MathUtils

/**
 * 词缀 R-15：电网深化升级（[AffixRegistry.ID_GRID_DEEPENING]）。
 * - 强制获得六相冰辐能网络与反应式辐能装甲的提升效果（只取增益，不含两者的代价项）；
 * - 按难度系数：提升 25%~50% 强制耗散速率；提升 10%~20% 硬辐能耗散速率（最终乘区）；
 * - AI 行为侧：优化自身与友军舰船的强制排辐决策，使其更智能、激进（[VentAssistListener]）。
 * 与极限辐能线圈扩容互斥（抽取时强制）。
 */
class AffixGridDeepeningHullMod : BaseHullMod() {

    companion object {
        val VENT_RATE = ScalingEntry(v1 = 1.25f, v2 = 1.375f, v5 = 1.50f)

        /** 硬辐能耗散比例增量：10%~20%（hardFluxDissipationFraction 为比例值，flat 叠加）。 */
        val HARD_FLUX_DISSIPATION = ScalingEntry(v1 = 0.10f, v2 = 0.15f, v5 = 0.20f)

        /** 友军排辐决策挂载间隔（秒），避免逐帧扫描。 */
        private const val ALLY_SCAN_INTERVAL = 0.5f

        private const val SCAN_TIMER_KEY = "astd_affix_grid_deepening_scan"
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val tuning = AffixShared.tuning
        // 捆绑：六相冰辐能网络增益项
        val cryo = AffixCryoFluxNetworkHullMod.bonuses(tuning)
        stats.fluxDissipation.modifyMult(id, cryo.dissipationMult)
        stats.ventRateMult.modifyMult(id, cryo.ventRateMult)
        stats.empDamageTakenMult.modifyMult(id, cryo.empDamageTakenMult)
        // 本体增益
        stats.ventRateMult.modifyMult(id, tuning.value(VENT_RATE))
        stats.hardFluxDissipationFraction.modifyFlat(id, tuning.value(HARD_FLUX_DISSIPATION))
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        // 捆绑：反应式辐能装甲增益项（排辐减伤；不含其排辐速率代价）
        if (!ship.hasListenerOfClass(AffixReactiveFluxArmorHullMod.VentGuardListener::class.java)) {
            ship.addListener(
                AffixReactiveFluxArmorHullMod.VentGuardListener(
                    ship,
                    AffixReactiveFluxArmorHullMod.damageTakenMult(AffixShared.tuning),
                ),
            )
        }
        ensureVentAssist(ship)
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || !ship.isAlive || ship.isHulk) return

        var elapsed = (ship.customData[SCAN_TIMER_KEY] as? Float ?: 0f) + amount
        if (elapsed < ALLY_SCAN_INTERVAL) {
            ship.setCustomData(SCAN_TIMER_KEY, elapsed)
            return
        }
        elapsed = 0f
        ship.setCustomData(SCAN_TIMER_KEY, elapsed)

        // AI 行为侧：为全编队（含友军）挂载排辐决策优化。
        for (ally in AffixShared.aliveAllies(ship)) {
            ensureVentAssist(ally)
        }
    }

    private fun ensureVentAssist(ship: ShipAPI) {
        if (!ship.hasListenerOfClass(VentAssistListener::class.java)) {
            ship.addListener(VentAssistListener(ship))
        }
    }

    /**
     * 排辐决策优化监听器：让舰船在更安全或更值得排辐的时机主动排辐。
     *
     * 判定口径（相对原版保守 AI 更激进）：
     * - 安全排辐：辐能水平过半且近距无敌舰 → 立即排辐；
     * - 硬辐能倾泻：硬辐能占比高且贴身无敌舰 → 立即排辐；
     * - 每次判定有最短间隔，避免指令抖动。
     */
    class VentAssistListener(
        private val ship: ShipAPI,
    ) : AdvanceableListener {

        private var sinceLastAttempt = ATTEMPT_INTERVAL

        override fun advance(amount: Float) {
            if (!ship.isAlive || ship.isHulk) {
                ship.removeListener(this)
                return
            }
            val engine = Global.getCombatEngine() ?: return
            if (engine.isPaused) return
            sinceLastAttempt += amount
            if (sinceLastAttempt < ATTEMPT_INTERVAL) return

            val tracker = ship.fluxTracker
            if (tracker.isOverloadedOrVenting || tracker.currFlux <= 0f) return
            val maxFlux = tracker.maxFlux.coerceAtLeast(1f)
            val fluxLevel = tracker.currFlux / maxFlux
            val hardFluxLevel = tracker.hardFlux / maxFlux

            val nearestEnemyDist = nearestEnemyDistance(engine)
            val safeToVent = fluxLevel >= SAFE_VENT_FLUX_LEVEL && nearestEnemyDist > SAFE_VENT_ENEMY_DIST
            val dumpHardFlux = hardFluxLevel >= HARD_FLUX_DUMP_LEVEL && nearestEnemyDist > HARD_FLUX_DUMP_ENEMY_DIST
            if (safeToVent || dumpHardFlux) {
                ship.giveCommand(ShipCommand.VENT_FLUX, null, 0)
                sinceLastAttempt = 0f
            }
        }

        private fun nearestEnemyDistance(engine: com.fs.starfarer.api.combat.CombatEngineAPI): Float {
            var nearest = Float.MAX_VALUE
            for (other in engine.ships) {
                if (other.owner == ship.owner || !other.isAlive || other.isHulk) continue
                if (other.isFighter || other.isDrone) continue
                if (other.isPhased) continue
                nearest = minOf(nearest, MathUtils.getDistance(ship, other))
            }
            return nearest
        }

        companion object {
            /** 两次排辐判定之间的最短间隔（秒）。 */
            const val ATTEMPT_INTERVAL = 1.0f

            /** 安全排辐触发线：辐能水平 ≥ 55% 且最近敌舰在 1200su 之外。 */
            const val SAFE_VENT_FLUX_LEVEL = 0.55f
            const val SAFE_VENT_ENEMY_DIST = 1200f

            /** 硬辐能倾泻触发线：硬辐能占比 ≥ 35% 且最近敌舰在 600su 之外。 */
            const val HARD_FLUX_DUMP_LEVEL = 0.35f
            const val HARD_FLUX_DUMP_ENEMY_DIST = 600f
        }
    }
}
