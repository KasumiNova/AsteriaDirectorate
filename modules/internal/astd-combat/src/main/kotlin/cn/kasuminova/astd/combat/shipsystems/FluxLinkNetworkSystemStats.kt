package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfx
import cn.kasuminova.astd.combat.shipsystems.FluxLinkNetworkTuning.AUTO_DEACTIVATE_FLUX_LEVEL
import cn.kasuminova.astd.combat.shipsystems.FluxLinkNetworkTuning.LINK_VISIBLE_INTENSITY
import cn.kasuminova.astd.combat.shipsystems.FluxLinkNetworkTuning.PENALTY_MULT
import cn.kasuminova.astd.combat.shipsystems.FluxLinkNetworkTuning.TOTAL_RATE_CAP
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.effect.system.GravTimeflowLinkVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.FluxTrackerAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import org.lazywizard.lazylib.MathUtils
import kotlin.math.roundToInt

/**
 * 「辐能链接网络」系统效果：激活期间链接范围内的所有友舰（不含战机/无人机/空间站模块），
 * 以自身辐能耗散的一定比例无条件持续将友舰辐能抽取到自身，效率随距离衰减；
 * 全链路总传输速率封顶为自身最终辐能耗散的 [TOTAL_RATE_CAP] 倍，超额时优先辐能水平最高的友舰；
 * 自身获得辐能耗散增益，代价是武器射速与机动性减半。
 *
 * 链路光束复用「引力时流干涉器」的 [GravTimeflowLinkVfx]（蓝色主题），
 * 仅在存在实际传输时渲染，亮度/粗细/摆动幅度随平滑后的传输速率缩放。
 */
class FluxLinkNetworkSystemStats : BaseShipSystemScript() {

    /** 单条链路的渲染状态：光束实例与平滑后的传输速率（su/s）。 */
    private class LinkState {
        var link: GravTimeflowLinkVfx? = null
        var smoothedRate: Float = 0f
    }

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk || ship.hitpoints <= 0f) {
            teardown(engine, ship)
            return
        }

        val level = effectLevel.coerceIn(0f, 1f)
        val tracker = ship.fluxTracker
        if (tracker != null && tracker.fluxLevel > AUTO_DEACTIVATE_FLUX_LEVEL) {
            ship.system?.deactivate()
        }

        val values = FluxLinkNetworkTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
        applySelfStats(stats, id, values, level)
        if (level <= 0.001f) {
            teardown(engine, ship)
            return
        }

        val amount = engine.elapsedInLastFrame.coerceIn(0.001f, 0.25f)
        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, values.range)
        val links = links(engine, ship)
        val activeIds = HashSet<String>()

        // 含自身增益后的耗散，即状态栏展示给玩家的「自身辐能耗散」
        val dissipation = ship.mutableStats.fluxDissipation.modifiedValue
        val maxRate = (dissipation * values.transferRate * level).coerceAtLeast(1f)
        var activeLinkCount = 0
        var transferredThisFrame = 0f

        // 收集范围内候选链路，按友舰辐能水平降序：全局预算不足时优先高辐能舰船
        val candidates = ArrayList<Pair<ShipAPI, Float>>()
        for (target in engine.ships) {
            if (!isValidTarget(ship, target)) continue
            val distance = MathUtils.getDistance(ship.location, target.location)
            if (distance > range) continue
            if (target.fluxTracker == null) continue
            activeIds += target.id
            candidates += target to FluxLinkNetworkTuning.efficiencyOf(distance / range)
        }
        candidates.sortByDescending { it.first.fluxTracker.fluxLevel }

        // 全局预算：总传输速率不超过自身最终辐能耗散的 TOTAL_RATE_CAP 倍
        var remainingBudget = dissipation * TOTAL_RATE_CAP * level * amount

        for ((target, efficiency) in candidates) {
            val targetTracker = target.fluxTracker ?: continue
            val linkRate = dissipation * values.transferRate * efficiency * level
            val transferred = transferFlux(
                tracker, targetTracker,
                softBudget = linkRate * amount,
                hardBudget = linkRate * values.hardTransferMult * amount,
                totalCap = remainingBudget,
            )
            remainingBudget = (remainingBudget - transferred).coerceAtLeast(0f)
            transferredThisFrame += transferred

            val linkState = links.getOrPut(target.id) { LinkState() }
            val actualRate = transferred / amount
            linkState.smoothedRate += (actualRate - linkState.smoothedRate) * (amount * RATE_SMOOTHING).coerceIn(0f, 1f)
            val intensity = (linkState.smoothedRate / maxRate).coerceIn(0f, 1f)
            if (intensity > LINK_VISIBLE_INTENSITY) {
                if (linkState.link == null) {
                    // 辐能流向为 友舰 → 本舰，attach 的 source/target 按流动方向传参
                    linkState.link = GravTimeflowLinkVfx.attach(
                        engine, target, ship, GravTimeflowLinkVfx.FLUX_LINK, FluxLinkNetworkTuning.SYSTEM_ID,
                        fadeIn = LINK_FADE_IN, fadeOut = LINK_FADE_OUT,
                    )
                }
                linkState.link?.update(amount, level, intensity)
                activeLinkCount++
                maintainTargetStatus(engine, ship, target, linkState.smoothedRate)
            } else {
                linkState.link?.dispose()
                linkState.link = null
            }
        }

        // 离群/失效目标的链路收尾
        val iterator = links.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in activeIds && entry.value.smoothedRate > 0f) continue
            entry.value.link?.dispose()
            iterator.remove()
        }

        ASTDArcProductionVfx.incrementCounter(engine, ASTDArcProductionVfx.TELEMETRY_FLUX_LINK_TRANSFERRED_FLUX, transferredThisFrame.roundToInt())
        if (transferredThisFrame > 0f) ASTDArcProductionVfx.incrementCounter(engine, ASTDArcProductionVfx.TELEMETRY_FLUX_LINK_TRANSFER_FRAMES)
        ASTDArcProductionVfx.setCounter(engine, ASTDArcProductionVfx.TELEMETRY_FLUX_LINK_ACTIVE_LINKS, activeLinkCount)
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        stats.ballisticRoFMult.unmodifyMult(id)
        stats.energyRoFMult.unmodifyMult(id)
        stats.missileRoFMult.unmodifyMult(id)
        stats.maxSpeed.unmodifyMult(id)
        stats.acceleration.unmodifyMult(id)
        stats.deceleration.unmodifyMult(id)
        stats.maxTurnRate.unmodifyMult(id)
        stats.turnAcceleration.unmodifyMult(id)
        stats.fluxDissipation.unmodifyMult(id)
        stats.hardFluxDissipationFraction.unmodifyFlat(id)

        val ship = stats.entity as? ShipAPI ?: return
        val engine = Global.getCombatEngine() ?: return
        teardown(engine, ship)
    }

    private fun applySelfStats(stats: MutableShipStatsAPI, id: String, values: FluxLinkNetworkTuning.Values, level: Float) {
        val penalty = 1f - (1f - PENALTY_MULT) * level
        stats.ballisticRoFMult.modifyMult(id, penalty)
        stats.energyRoFMult.modifyMult(id, penalty)
        stats.missileRoFMult.modifyMult(id, penalty)
        stats.maxSpeed.modifyMult(id, penalty)
        stats.acceleration.modifyMult(id, penalty)
        stats.deceleration.modifyMult(id, penalty)
        stats.maxTurnRate.modifyMult(id, penalty)
        stats.turnAcceleration.modifyMult(id, penalty)
        stats.fluxDissipation.modifyMult(id, 1f + values.dissipationBonus * level)
        stats.hardFluxDissipationFraction.modifyFlat(id, values.hardDissipationBonus * level)
    }

    /**
     * 执行一帧辐能传输：无条件抽取友舰辐能；软辐能预算与硬辐能预算独立，
     * 当全局剩余预算不足时按比例收缩软/硬传输量，保证总量不超过 [totalCap]。返回本帧实际传输量。
     */
    private fun transferFlux(
        sourceTracker: FluxTrackerAPI?,
        targetTracker: FluxTrackerAPI,
        softBudget: Float,
        hardBudget: Float,
        totalCap: Float,
    ): Float {
        if (sourceTracker == null || sourceTracker.isOverloadedOrVenting) return 0f
        if (totalCap <= 0f) return 0f

        val softDesired = minOf((targetTracker.currFlux - targetTracker.hardFlux).coerceAtLeast(0f), softBudget)
        val hardDesired = minOf(targetTracker.hardFlux, hardBudget)
        val desired = softDesired + hardDesired
        if (desired <= 0f) return 0f

        val scale = if (desired > totalCap) totalCap / desired else 1f
        val soft = softDesired * scale
        val hard = hardDesired * scale
        val total = soft + hard
        if (total <= 0f) return 0f

        if (soft > 0f) targetTracker.decreaseFlux(soft)
        if (hard > 0f) {
            val newHard = (targetTracker.hardFlux - hard).coerceAtLeast(0f)
            targetTracker.hardFlux = newHard
            targetTracker.currFlux = (targetTracker.currFlux - hard).coerceAtLeast(newHard)
        }
        if (soft > 0f) sourceTracker.increaseFlux(soft, false)
        if (hard > 0f) sourceTracker.increaseFlux(hard, true)
        return total
    }

    private fun isValidTarget(source: ShipAPI, target: ShipAPI): Boolean =
        target !== source && target.owner == source.owner && target.isAlive && !target.isHulk &&
                !target.isFighter && !target.isDrone && !target.isStationModule

    private fun teardown(engine: CombatEngineAPI, ship: ShipAPI) {
        val links = engine.customData.remove(linksKey(ship)) as? Map<*, *> ?: return
        for (entry in links.values) {
            (entry as? LinkState)?.link?.dispose()
        }
    }

    private fun maintainTargetStatus(engine: CombatEngineAPI, source: ShipAPI, target: ShipAPI, rate: Float) {
        if (target !== engine.playerShip) return
        engine.maintainStatusForPlayerShip(
            "astd_flux_link_target:${source.id}",
            null,
            I18n[I18n.Categories.MOD, "system.arc_shared_flux_network.target_status.line1"],
            I18n.t(
                I18n.Categories.MOD, "system.arc_shared_flux_network.target_status.line2",
                "name" to (source.name ?: source.hullSpec.hullName),
                "rate" to rate.roundToInt().toString(),
            ),
            false,
        )
    }

    override fun isUsable(system: ShipSystemAPI, ship: ShipAPI): Boolean {
        val tracker = ship.fluxTracker ?: return false
        return tracker.fluxLevel <= AUTO_DEACTIVATE_FLUX_LEVEL
    }

    override fun getStatusData(index: Int, state: ShipSystemStatsScript.State, effectLevel: Float): ShipSystemStatsScript.StatusData? {
        val suffix = when (state) {
            ShipSystemStatsScript.State.IN -> "in"
            ShipSystemStatsScript.State.ACTIVE -> "active"
            ShipSystemStatsScript.State.OUT -> "out"
            else -> return null
        }
        val line = when (index) {
            0 -> "line1"
            1 -> "line2"
            else -> return null
        }
        val level = effectLevel.coerceIn(0f, 1f)
        val values = FluxLinkNetworkTuning.resolve(DifficultyTuningImpl, true)
        return ShipSystemStatsScript.StatusData(
            I18n.t(
                I18n.Categories.MOD,
                "system.arc_shared_flux_network.status.default.$suffix.$line",
                "selfPenalty" to formatPercent((1f - PENALTY_MULT) * level),
                "selfDissipation" to formatPercent(values.dissipationBonus * level),
                "hardDissipation" to formatPercent(values.hardDissipationBonus * level),
                "transferRate" to formatPercent(values.transferRate * level),
            ),
            false,
        )
    }

    private fun formatPercent(value: Float): String =
        "${(value.coerceAtLeast(0f) * 100f).roundToInt()}%"

    companion object {
        private const val RATE_SMOOTHING = 3f
        private const val LINK_FADE_IN = 0.5f
        private const val LINK_FADE_OUT = 1.0f
        private const val LINKS_KEY = "astd_flux_link_network_links:"

        private fun linksKey(ship: ShipAPI): String = "$LINKS_KEY${ship.id}"

        @Suppress("UNCHECKED_CAST")
        private fun links(engine: CombatEngineAPI, ship: ShipAPI): MutableMap<String, LinkState> =
            engine.customData.getOrPut(linksKey(ship)) { HashMap<String, LinkState>() } as MutableMap<String, LinkState>
    }
}
