package cn.kasuminova.astd.combat.hullmods.arc

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI

internal object ASTDArcFlareHullModIds {
    const val HULL_ID: String = "astd_arc_flare"

    const val SWITCHER: String = "astd_arc_flare_mode_switcher"
    const val MODE_CREWED: String = "astd_arc_flare_mode_crewed"
    const val MODE_AUTOMATED: String = "astd_arc_flare_mode_automated"
    const val NEXT_CREWED: String = "astd_arc_flare_mode_next_crewed"
    const val NEXT_AUTOMATED: String = "astd_arc_flare_mode_next_automated"
}

/**
 * 判断 variant 所属舰船是否为模组全局舰船（hull id 以 "astd_" 开头）。
 */
internal fun ShipVariantAPI?.isASTDShipVariant(): Boolean {
    val variant = this ?: return false
    val hullId = try { variant.hullSpec?.hullId } catch (_: Throwable) { null }
    val baseHullId = try { variant.hullSpec?.baseHullId } catch (_: Throwable) { null }
    return (hullId ?: "").startsWith("astd_") || (baseHullId ?: "").startsWith("astd_")
}

/**
 * 判断 ship 是否为模组全局舰船（hull id 以 "astd_" 开头）。
 */
internal fun ShipAPI?.isASTDShip(): Boolean {
    val ship = this ?: return false
    val hullId = try { ship.hullSpec?.hullId } catch (_: Throwable) { null }
    val baseHullId = try { ship.hullSpec?.baseHullId } catch (_: Throwable) { null }
    return (hullId ?: "").startsWith("astd_") || (baseHullId ?: "").startsWith("astd_")
}

internal fun ShipVariantAPI?.isASTDArcFlareVariant(): Boolean {
    val variant = this ?: return false
    val hullId = try {
        variant.hullSpec?.hullId
    } catch (_: Throwable) {
        null
    }
    val baseHullId = try {
        variant.hullSpec?.baseHullId
    } catch (_: Throwable) {
        null
    }
    return hullId == ASTDArcFlareHullModIds.HULL_ID || baseHullId == ASTDArcFlareHullModIds.HULL_ID
}

internal fun ShipAPI?.isASTDArcFlareShip(): Boolean {
    val ship = this ?: return false
    val hullId = try {
        ship.hullSpec?.hullId
    } catch (_: Throwable) {
        null
    }
    val baseHullId = try {
        ship.hullSpec?.baseHullId
    } catch (_: Throwable) {
        null
    }
    return hullId == ASTDArcFlareHullModIds.HULL_ID || baseHullId == ASTDArcFlareHullModIds.HULL_ID
}

internal fun ShipVariantAPI.ensureASTDArcFlareModeState(stats: MutableShipStatsAPI? = null) {
    if (!isASTDShipVariant()) return

    migrateLegacyModeState()

    val hasCrewed = getPermaMods().contains(ASTDArcFlareHullModIds.MODE_CREWED)
    val hasAutomated = getPermaMods().contains(ASTDArcFlareHullModIds.MODE_AUTOMATED)

    if (hasCrewed && hasAutomated) {
        removePermaMod(ASTDArcFlareHullModIds.MODE_AUTOMATED)
        removePermaMod("automated")
        setNextMarker(ASTDArcFlareHullModIds.NEXT_CREWED)
        return
    }

    when {
        // 稳定态：当前模式 + 同向 next marker → 无需切换
        hasCrewed -> setNextMarker(ASTDArcFlareHullModIds.NEXT_CREWED)
        hasAutomated -> setNextMarker(ASTDArcFlareHullModIds.NEXT_AUTOMATED)
        // 无模式时：按 next marker 激活
        getPermaMods().contains(ASTDArcFlareHullModIds.NEXT_AUTOMATED) -> activateMode(ASTDArcFlareHullModIds.MODE_AUTOMATED, stats)
        getPermaMods().contains(ASTDArcFlareHullModIds.NEXT_CREWED) -> activateMode(ASTDArcFlareHullModIds.MODE_CREWED, stats)
        else -> activateMode(ASTDArcFlareHullModIds.MODE_CREWED, stats)
    }
}

internal fun ShipVariantAPI.hasASTDArcFlareAutomatedMode(): Boolean =
    getPermaMods().contains(ASTDArcFlareHullModIds.MODE_AUTOMATED) || hasHullMod(ASTDArcFlareHullModIds.MODE_AUTOMATED)

private fun ShipVariantAPI.migrateLegacyModeState() {
    val stateIds = listOf(
        ASTDArcFlareHullModIds.MODE_CREWED,
        ASTDArcFlareHullModIds.MODE_AUTOMATED,
        ASTDArcFlareHullModIds.NEXT_CREWED,
        ASTDArcFlareHullModIds.NEXT_AUTOMATED,
    )
    for (id in stateIds) {
        if (hasHullMod(id) && !getPermaMods().contains(id)) {
            removeMod(id)
            addPermaMod(id)
        }
    }
}

internal fun ShipVariantAPI.activateMode(modeId: String, stats: MutableShipStatsAPI? = null) {
    removePermaMod(ASTDArcFlareHullModIds.MODE_CREWED)
    removePermaMod(ASTDArcFlareHullModIds.MODE_AUTOMATED)
    addPermaMod(modeId)
    // 稳定态：next marker = 与当前模式同向
    val nextMarker = if (modeId == ASTDArcFlareHullModIds.MODE_AUTOMATED)
        ASTDArcFlareHullModIds.NEXT_AUTOMATED
    else
        ASTDArcFlareHullModIds.NEXT_CREWED
    setNextMarker(nextMarker)
    // 同步原版 'automated' 船插（全自动舰船）
    if (modeId == ASTDArcFlareHullModIds.MODE_AUTOMATED) {
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

private fun ShipVariantAPI.setNextMarker(markerId: String) {
    removePermaMod(ASTDArcFlareHullModIds.NEXT_CREWED)
    removePermaMod(ASTDArcFlareHullModIds.NEXT_AUTOMATED)
    addPermaMod(markerId)
}