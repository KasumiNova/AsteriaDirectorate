package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipVariantAPI

// 原版 OP 结算会实时迭代 hullMods 并回调船插；requestStrip 只能入队，结构性移除必须延后到快照迭代外。
object IncompatibleHullmodStripper {

    private const val DRAINER_KEY = "\$astd_incompatible_hullmod_stripper"

    private data class StripRequest(
        val variant: ShipVariantAPI,
        val forbiddenIds: Set<String>,
        val sourceId: String,
    )

    private val pending = mutableListOf<StripRequest>()

    private val log = Global.getLogger(IncompatibleHullmodStripper::class.java)

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
