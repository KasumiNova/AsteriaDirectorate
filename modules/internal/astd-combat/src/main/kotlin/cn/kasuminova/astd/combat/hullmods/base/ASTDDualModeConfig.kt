package cn.kasuminova.astd.combat.hullmods.base

import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeRegistry.configFor
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeRegistry.configForShip
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeRegistry.configForVariant
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeRegistry.genericConfigFor
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeRegistry.register
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeSwitcherIds.SWITCHER_ID
import cn.kasuminova.astd.impl.combat.DualModeSettingsImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.impl.campaign.ids.Tags

data class ASTDDualModeConfig(
    val switcherId: String,
    val crewedModeId: String,
    val automatedModeId: String,
    val nextCrewedMarker: String,
    val nextAutomatedMarker: String,
    val crewedSystemId: String?,
    val automatedSystemId: String?,
)

object ASTDDualModeSwitcherIds {
    /** 必须与 hull_mods.csv 中注册通用切换器的行 id 完全一致。 */
    const val SWITCHER_ID: String = "astd_dual_mode_switcher"
}

object ASTDDualModeGenericIds {
    const val MODE_CREWED: String = "astd_mode_crewed"
    const val MODE_AUTOMATED: String = "astd_mode_automated"
    const val NEXT_CREWED: String = "astd_mode_next_crewed"
    const val NEXT_AUTOMATED: String = "astd_mode_next_automated"
}

val GENERIC_DUAL_MODE_CONFIG = ASTDDualModeConfig(
    switcherId = SWITCHER_ID,
    crewedModeId = ASTDDualModeGenericIds.MODE_CREWED,
    automatedModeId = ASTDDualModeGenericIds.MODE_AUTOMATED,
    nextCrewedMarker = ASTDDualModeGenericIds.NEXT_CREWED,
    nextAutomatedMarker = ASTDDualModeGenericIds.NEXT_AUTOMATED,
    crewedSystemId = null,
    automatedSystemId = null,
)

object ASTDDualModeRegistry {
    private val byHullId = LinkedHashMap<String, ASTDDualModeConfig>()

    fun register(hullId: String, config: ASTDDualModeConfig) {
        byHullId[hullId] = config
    }

    fun configFor(hullId: String?): ASTDDualModeConfig? = hullId?.let { byHullId[it] }

    fun genericConfigFor(hullId: String?): ASTDDualModeConfig? =
        if (hullId != null && hullId.startsWith("astd_")) GENERIC_DUAL_MODE_CONFIG else null

    fun configForVariant(variant: ShipVariantAPI?): ASTDDualModeConfig? {
        val v = variant ?: return null
        val hullId = try {
            v.hullSpec?.hullId
        } catch (_: Throwable) {
            null
        }
        val baseHullId = try {
            v.hullSpec?.baseHullId
        } catch (_: Throwable) {
            null
        }
        return configFor(hullId) ?: configFor(baseHullId)
        ?: genericConfigFor(hullId) ?: genericConfigFor(baseHullId)
    }

    fun configForShip(ship: ShipAPI?): ASTDDualModeConfig? {
        val s = ship ?: return null
        val hullId = try {
            s.hullSpec?.hullId
        } catch (_: Throwable) {
            null
        }
        val baseHullId = try {
            s.hullSpec?.baseHullId
        } catch (_: Throwable) {
            null
        }
        return configFor(hullId) ?: configFor(baseHullId)
        ?: genericConfigFor(hullId) ?: genericConfigFor(baseHullId)
    }
}

internal fun ShipVariantAPI?.isASTDShipVariant(): Boolean {
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
    return (hullId ?: "").startsWith("astd_") || (baseHullId ?: "").startsWith("astd_")
}

internal fun ShipAPI?.isASTDShip(): Boolean {
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
    return (hullId ?: "").startsWith("astd_") || (baseHullId ?: "").startsWith("astd_")
}

fun ShipVariantAPI.hasASTDDualModeState(config: ASTDDualModeConfig): Boolean =
    permaMods.contains(config.crewedModeId) ||
        permaMods.contains(config.automatedModeId) ||
        permaMods.contains(config.nextCrewedMarker) ||
        permaMods.contains(config.nextAutomatedMarker)

fun ShipVariantAPI.ensureDualModeSwitcherPresent(config: ASTDDualModeConfig): Boolean {
    if (hasHullMod(config.switcherId)) return false
    // 有模式状态但缺切换器说明拆即切尚未完成，此处补装会抢在翻转判定之前，导致拆即切永不触发
    if (hasASTDDualModeState(config)) return false
    addMod(config.switcherId)
    // 补装初始化必须保持 stats=null，避免预览 variant 的翻转被镜像写入真实成员
    ensureASTDDualModeState(config)
    return true
}

fun ShipVariantAPI.ensureASTDDualModeState(config: ASTDDualModeConfig, stats: MutableShipStatsAPI? = null) {
    if (!isASTDShipVariant()) return

    migrateLegacyDualModeState(config)
    syncDualModeAutoPenaltyTag(config)

    val hasCrewed = permaMods.contains(config.crewedModeId)
    val hasAutomated = permaMods.contains(config.automatedModeId)

    if (hasCrewed && hasAutomated) {
        removePermaMod(config.automatedModeId)
        removePermaMod("automated")
        setDualModeNextMarker(config, config.nextCrewedMarker)
        syncDualModeAutoPenaltyTag(config)
        return
    }

    when {
        hasCrewed -> setDualModeNextMarker(config, config.nextCrewedMarker)
        hasAutomated -> setDualModeNextMarker(config, config.nextAutomatedMarker)
        permaMods.contains(config.nextAutomatedMarker) -> activateDualMode(config, config.automatedModeId, stats)
        permaMods.contains(config.nextCrewedMarker) -> activateDualMode(config, config.crewedModeId, stats)
        else -> activateDualMode(config, config.crewedModeId, stats)
    }
}

fun ShipVariantAPI.activateDualMode(config: ASTDDualModeConfig, modeId: String, stats: MutableShipStatsAPI? = null) {
    removePermaMod(config.crewedModeId)
    removePermaMod(config.automatedModeId)
    addPermaMod(modeId)
    val nextMarker = if (modeId == config.automatedModeId) config.nextAutomatedMarker else config.nextCrewedMarker
    setDualModeNextMarker(config, nextMarker)
    if (modeId == config.automatedModeId) {
        if (!permaMods.contains("automated")) addPermaMod("automated")
    } else {
        removePermaMod("automated")
    }
    syncDualModeAutoPenaltyTag(config)
    // 仅当前生效 variant 才允许清理舰长并登记镜像；克隆 variant（装配预览）禁止触碰真实成员
    val member = stats?.fleetMember
    if (member != null && member.variant === this) {
        clearIncompatibleDualModeCaptain(member, config)
        ASTDDualModeMirror.record(member, modeId)
    }
}

fun ShipVariantAPI.hasASTDDualModeAutomated(config: ASTDDualModeConfig): Boolean =
    permaMods.contains(config.automatedModeId) || hasHullMod(config.automatedModeId)

const val NO_AUTO_PENALTY_TAG: String = Tags.TAG_AUTOMATED_NO_PENALTY

fun ShipVariantAPI.syncDualModeAutoPenaltyTag(config: ASTDDualModeConfig) {
    val exempt = hasASTDDualModeAutomated(config) && DualModeSettingsImpl.automatedModeExemptFromAutoPoints
    if (exempt == hasTag(NO_AUTO_PENALTY_TAG)) return
    if (exempt) addTag(NO_AUTO_PENALTY_TAG) else removeTag(NO_AUTO_PENALTY_TAG)
}

fun installDualModeAutoPointsHook() {
    DualModeSettingsImpl.exemptChangedHook = { syncPlayerFleetAutoPenaltyTags() }
}

private fun syncPlayerFleetAutoPenaltyTags() {
    val fleet = Global.getSector()?.playerFleet ?: return
    fleet.fleetData.membersListCopy.forEach { member ->
        val variant = member.variant ?: return@forEach
        if (!variant.isASTDShipVariant()) return@forEach
        val config = ASTDDualModeRegistry.configForVariant(variant) ?: return@forEach
        variant.syncDualModeAutoPenaltyTag(config)
        // 标签影响 Automated 船插的 CR 惩罚（stats 层），显式重建成员 stats 令其即时生效
        member.updateStats()
    }
}

private fun ShipVariantAPI.migrateLegacyDualModeState(config: ASTDDualModeConfig) {
    val stateIds = listOf(
        config.crewedModeId,
        config.automatedModeId,
        config.nextCrewedMarker,
        config.nextAutomatedMarker,
    )
    for (id in stateIds) {
        if (hasHullMod(id) && !permaMods.contains(id)) {
            removeMod(id)
            addPermaMod(id)
        }
    }
}

private fun ShipVariantAPI.setDualModeNextMarker(config: ASTDDualModeConfig, markerId: String) {
    removePermaMod(config.nextCrewedMarker)
    removePermaMod(config.nextAutomatedMarker)
    addPermaMod(markerId)
}

internal fun clearIncompatibleDualModeCaptain(member: FleetMemberAPI?, config: ASTDDualModeConfig) {
    if (member == null) return
    val captain = try {
        member.captain
    } catch (_: Throwable) {
        null
    } ?: return
    if (captain.isDefault) return
    val variant = try {
        member.variant
    } catch (_: Throwable) {
        null
    } ?: return
    val automated = variant.hasASTDDualModeAutomated(config)
    val aiCoreId = try {
        captain.aiCoreId
    } catch (_: Throwable) {
        null
    }
    val incompatible = if (automated) aiCoreId == null else aiCoreId != null
    if (!incompatible) return
    if (aiCoreId != null) {
        try {
            Global.getSector()?.playerFleet?.cargo?.addCommodity(aiCoreId, 1f)
        } catch (_: Throwable) {
            // refit 外上下文可能无玩家舰队，归还失败接受核心丢失，不得因此崩溃
        }
    }
    try {
        member.captain = null
    } catch (_: Throwable) {
        // refit 外上下文卸载可能失败，不得因此崩溃
    }
}
