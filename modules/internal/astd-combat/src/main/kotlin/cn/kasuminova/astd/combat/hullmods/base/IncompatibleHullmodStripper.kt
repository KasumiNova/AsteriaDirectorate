package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipVariantAPI

/**
 * 不兼容船插延迟清理器。
 *
 * 动机：原版 `HullVariantSpec.updateStatsForOpCosts` 在**实时迭代** `variant.getHullMods()`
 * （LinkedHashSet 活性集合）期间回调 `applyEffectsBeforeShipCreation`（仅 `affectsOPCosts=true`
 * 的船插）；此时结构性 `removeMod` 必抛 `ConcurrentModificationException`（自动装配实机踩坑，
 * 堆栈 CoreAutofitPlugin.addExtraVentsAndCaps → computeOPCost → updateStatsForOpCosts）。
 * 而 `ShipFactory`/`FleetMember` 等路径迭代的是 `getAllMods()` 快照，无此问题。
 *
 * 约定：`applyEffectsBeforeShipCreation` 只允许检测并入队（[requestStrip]），真正的五路清理
 * （普通/permaMod/S-mod/S-modded built-in/suppressed）延后到安全点执行：
 * - `applyEffectsAfterShipCreation`（ShipFactory 快照迭代，战斗生成前即刻清理）；
 * - `advanceInCombat`（战斗内兜底）；
 * - 战役侧每帧 drainer（transient script，装配界面改动下一帧生效）。
 */
object IncompatibleHullmodStripper {

    /** 战役 drainer 注册标记（`$` 前缀键不随存档持久化，读档后自动重新注册）。 */
    private const val DRAINER_KEY = "\$astd_incompatible_hullmod_stripper"

    private data class StripRequest(
        val variant: ShipVariantAPI,
        val forbiddenIds: Set<String>,
        val sourceId: String,
    )

    private val pending = mutableListOf<StripRequest>()

    private val log = Global.getLogger(IncompatibleHullmodStripper::class.java)

    /**
     * 检测 [variant] 是否装有 [forbiddenIds] 中的船插；有则入队等待清理。
     * 本方法不做任何结构性修改，在任意回调上下文（含 OP 结算实时迭代）中均安全。
     */
    fun requestStrip(variant: ShipVariantAPI?, forbiddenIds: Set<String>, sourceId: String) {
        variant ?: return
        val hit = forbiddenIds.any { forbiddenId ->
            variant.hasHullMod(forbiddenId) ||
                    forbiddenId in variant.sMods ||
                    forbiddenId in variant.sModdedBuiltIns ||
                    forbiddenId in variant.suppressedMods
        }
        if (!hit) return
        if (pending.none { it.variant === variant && it.sourceId == sourceId }) {
            pending += StripRequest(variant, forbiddenIds, sourceId)
        }
        ensureCampaignDrainer()
    }

    /**
     * 执行全部待清理请求并清空队列。只能在快照迭代或迭代外的安全点调用
     * （AfterCreation / advanceInCombat / 战役每帧 drainer）。
     */
    fun drainPending() {
        if (pending.isEmpty()) return
        val requests = pending.toList()
        pending.clear()
        requests.forEach { request ->
            request.forbiddenIds.forEach { forbiddenId ->
                val installed = request.variant.hasHullMod(forbiddenId) ||
                        forbiddenId in request.variant.sMods ||
                        forbiddenId in request.variant.sModdedBuiltIns ||
                        forbiddenId in request.variant.suppressedMods
                if (installed) {
                    log.info("${request.sourceId}: 移除不兼容船插 $forbiddenId（variant=${request.variant.hullVariantId}）")
                }
                request.variant.removeMod(forbiddenId)
                request.variant.removePermaMod(forbiddenId)
                request.variant.sMods.remove(forbiddenId)
                request.variant.sModdedBuiltIns.remove(forbiddenId)
                request.variant.removeSuppressedMod(forbiddenId)
            }
        }
    }

    /** 战役侧 drainer 只需注册一次（transient，不进存档；读档后经 `$` 键自动重注册）。 */
    private fun ensureCampaignDrainer() {
        val sector = Global.getSector() ?: return
        if (sector.memoryWithoutUpdate.getBoolean(DRAINER_KEY)) return
        sector.memoryWithoutUpdate[DRAINER_KEY] = true
        sector.addTransientScript(CampaignDrainScript())
    }

    private class CampaignDrainScript : EveryFrameScript {
        override fun isDone(): Boolean = false

        override fun runWhilePaused(): Boolean = true

        override fun advance(amount: Float) = drainPending()
    }
}
