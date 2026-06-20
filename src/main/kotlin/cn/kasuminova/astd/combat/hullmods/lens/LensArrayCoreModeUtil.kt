package cn.kasuminova.astd.combat.hullmods.lens

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipVariantAPI

/**
 * 透镜阵列核心双模式状态机：无人/载人通过 PermaMod + next marker 切换，
 * 镜像 ASTDArcFlareHullModUtil 的稳定范式。
 */
internal fun ShipVariantAPI.hasLensAutomatedMode(): Boolean =
    getPermaMods().contains(LensArrayCoreHullModIds.MODE_AUTOMATED) ||
        hasHullMod(LensArrayCoreHullModIds.MODE_AUTOMATED)

internal fun ShipVariantAPI.ensureLensArrayModeState(stats: MutableShipStatsAPI? = null) {
    if (!isGravitationalLensVariant()) return
    // 新船无遗留状态，不需要 migrateLegacyModeState（镜像 ARC 但故意省略）。

    val hasCrewed = getPermaMods().contains(LensArrayCoreHullModIds.MODE_CREWED)
    val hasAutomated = getPermaMods().contains(LensArrayCoreHullModIds.MODE_AUTOMATED)

    if (hasCrewed && hasAutomated) {
        removePermaMod(LensArrayCoreHullModIds.MODE_AUTOMATED)
        removePermaMod("automated")
        setLensNextMarker(LensArrayCoreHullModIds.NEXT_CREWED)
        return
    }

    when {
        // 稳定态：当前模式 + 同向 next marker → 无需切换
        hasCrewed -> setLensNextMarker(LensArrayCoreHullModIds.NEXT_CREWED)
        hasAutomated -> setLensNextMarker(LensArrayCoreHullModIds.NEXT_AUTOMATED)
        // 无模式时：按 next marker 激活
        getPermaMods().contains(LensArrayCoreHullModIds.NEXT_AUTOMATED) ->
            activateLensMode(LensArrayCoreHullModIds.MODE_AUTOMATED, stats)
        getPermaMods().contains(LensArrayCoreHullModIds.NEXT_CREWED) ->
            activateLensMode(LensArrayCoreHullModIds.MODE_CREWED, stats)
        else -> activateLensMode(LensArrayCoreHullModIds.MODE_CREWED, stats)
    }
}

internal fun ShipVariantAPI.activateLensMode(modeId: String, stats: MutableShipStatsAPI? = null) {
    removePermaMod(LensArrayCoreHullModIds.MODE_CREWED)
    removePermaMod(LensArrayCoreHullModIds.MODE_AUTOMATED)
    addPermaMod(modeId)
    // 稳定态：next marker = 与当前模式同向
    val nextMarker = if (modeId == LensArrayCoreHullModIds.MODE_AUTOMATED)
        LensArrayCoreHullModIds.NEXT_AUTOMATED
    else
        LensArrayCoreHullModIds.NEXT_CREWED
    setLensNextMarker(nextMarker)
    // 同步原版 'automated' 船插（全自动舰船）
    if (modeId == LensArrayCoreHullModIds.MODE_AUTOMATED) {
        if (!getPermaMods().contains("automated")) addPermaMod("automated")
    } else {
        removePermaMod("automated")
    }
    // 战役上下文：切换模式时自动卸下不兼容的舰长/AI 核心
    clearIncompatibleCaptain(stats)
}

private fun clearIncompatibleCaptain(stats: MutableShipStatsAPI?) {
    val member = try { stats?.fleetMember } catch (_: Throwable) { null } ?: return
    val captain = try { member.captain } catch (_: Throwable) { null }
    if (captain != null) {
        // AI 核心：先归还到玩家货舱，再移除舰长，避免核心丢失
        val aiCoreId = try { captain.aiCoreId } catch (_: Throwable) { null }
        if (aiCoreId != null) {
            try {
                Global.getSector()?.playerFleet?.cargo?.addCommodity(aiCoreId, 1f)
            } catch (_: Throwable) {}
        }
    }
    try { member.setCaptain(null) } catch (_: Throwable) {}
}

private fun ShipVariantAPI.setLensNextMarker(markerId: String) {
    removePermaMod(LensArrayCoreHullModIds.NEXT_CREWED)
    removePermaMod(LensArrayCoreHullModIds.NEXT_AUTOMATED)
    addPermaMod(markerId)
}
