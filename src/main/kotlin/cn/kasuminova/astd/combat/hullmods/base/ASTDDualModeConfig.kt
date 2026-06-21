package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI

/**
 * ASTD 通用双模式（载人/无人）配置：一艘双模式舰船的全部 hullmod / 系统 id 集合。
 *
 * 动机：arc_flare 与 gravitational_lens 共用同一套「拆切换器即轮换模式」交互，
 * 早期实现让每艘舰各自硬编码一套状态机（[cn.kasuminova.astd.combat.hullmods.arc.ASTDArcFlareHullModUtil]），
 * 重复且易漂移。此配置把「双模式舰需要的全部 id」收敛为单一数据对象，配合本文件的通用状态机
 * 扩展函数（[ensureASTDDualModeState] / [activateDualMode]）让一套逻辑驱动任意双模式舰。
 *
 * 每个字段都对应原 arc 状态机里的一个硬编码常量，泛化后由各舰在自己的 ids util 处构造一份本配置并注册到
 * [ASTDDualModeRegistry]。
 */
data class ASTDDualModeConfig(
    /**
     * 通用切换器 hullmod id（所有双模式舰共用同一个，见后续 ASTDDualModeSwitcherHullMod）。
     * 作用：mode hullmod 在 refit 检测「切换器是否被玩家拆下」时比对此 id，从而触发拆即切。
     */
    val switcherId: String,
    /** 载人模式 hullmod id；存在于 permaMods 表示当前为载人模式。 */
    val crewedModeId: String,
    /** 无人模式 hullmod id；存在于 permaMods 表示当前为无人模式（同时挂原版 "automated"）。 */
    val automatedModeId: String,
    /** 「下次激活载人」标记 hullmod id；无模式 permaMod 时按此标记激活载人模式。 */
    val nextCrewedMarker: String,
    /** 「下次激活无人」标记 hullmod id；无模式 permaMod 时按此标记激活无人模式。 */
    val nextAutomatedMarker: String,
    /** 载人版舰船系统 id（载人 mode hullmod 激活时 setShipSystemId）。 */
    val crewedSystemId: String,
    /** 无人版舰船系统 id（无人 mode hullmod 激活时 setShipSystemId）。 */
    val automatedSystemId: String,
)

/**
 * 双模式配置注册表：hull id → [ASTDDualModeConfig]。
 *
 * 动机：通用切换器 hullmod（后续任务）需根据当前舰船反查其模式配置以渲染动态 tooltip，
 * 而切换器本身不知道具体舰的 ids。arc / lens 各自在静态初始化处 [register] 自己的 config，
 * 切换器用 [configFor] 反查。
 */
object ASTDDualModeRegistry {
    /**
     * hull id → config。用 LinkedHashMap 保证注册顺序稳定（便于调试遍历）。
     * key 同时容纳「具体 variant hull id」与「baseHullId」两种可能：
     * 注册方应以舰船的基底 hull id（即 .ship 文件 id）注册，[configFor] 查询时会同时尝试传入 id 与其 baseHullId。
     */
    private val byHullId = LinkedHashMap<String, ASTDDualModeConfig>()

    /**
     * 注册一艘双模式舰的配置。
     * @param hullId 该舰的基底 hull id（.ship 文件 id，例如 "astd_arc_flare"）。
     * @param config 该舰的双模式配置。
     */
    fun register(hullId: String, config: ASTDDualModeConfig) {
        byHullId[hullId] = config
    }

    /**
     * 按 hull id 反查配置；找不到返回 null。
     * 调用方通常传 variant.hullSpec.hullId，但变体可能带后缀，故由 [configForVariant] / [configForShip]
     * 同时尝试 hullId 与 baseHullId。
     */
    fun configFor(hullId: String?): ASTDDualModeConfig? = hullId?.let { byHullId[it] }

    /**
     * 从 variant 反查配置：先试 hullId，再试 baseHullId（变体的基底）。
     * 动机：注册以基底 id 进行，而切换器拿到的是具体 variant，其 hullId 可能等于或派生自基底。
     */
    fun configForVariant(variant: ShipVariantAPI?): ASTDDualModeConfig? {
        val v = variant ?: return null
        val hullId = try { v.hullSpec?.hullId } catch (_: Throwable) { null }
        val baseHullId = try { v.hullSpec?.baseHullId } catch (_: Throwable) { null }
        return configFor(hullId) ?: configFor(baseHullId)
    }

    /**
     * 从 ship 反查配置：先试 hullId，再试 baseHullId。
     * 与 [configForVariant] 同理，供切换器 tooltip 直接拿到 ShipAPI 时使用。
     */
    fun configForShip(ship: ShipAPI?): ASTDDualModeConfig? {
        val s = ship ?: return null
        val hullId = try { s.hullSpec?.hullId } catch (_: Throwable) { null }
        val baseHullId = try { s.hullSpec?.baseHullId } catch (_: Throwable) { null }
        return configFor(hullId) ?: configFor(baseHullId)
    }
}

/**
 * 判断 variant 所属舰船是否为模组全局舰船（hull id 以 "astd_" 开头）。
 *
 * 动机：双模式状态机仅应作用于本模组舰船，避免误改其它 mod / 原版舰的 permaMods。
 * 说明：arc util 当前各自持有一份同名 internal 扩展（按包区分，互不冲突）；base 自带一份供 base 包内逻辑使用，
 * 后续统一任务再合并去重。
 */
internal fun ShipVariantAPI?.isASTDShipVariant(): Boolean {
    val variant = this ?: return false
    val hullId = try { variant.hullSpec?.hullId } catch (_: Throwable) { null }
    val baseHullId = try { variant.hullSpec?.baseHullId } catch (_: Throwable) { null }
    return (hullId ?: "").startsWith("astd_") || (baseHullId ?: "").startsWith("astd_")
}

/**
 * 判断 ship 是否为模组全局舰船（hull id 以 "astd_" 开头）。
 * 动机同 [isASTDShipVariant]，供 base 包内以 ShipAPI 判定时使用。
 */
internal fun ShipAPI?.isASTDShip(): Boolean {
    val ship = this ?: return false
    val hullId = try { ship.hullSpec?.hullId } catch (_: Throwable) { null }
    val baseHullId = try { ship.hullSpec?.baseHullId } catch (_: Throwable) { null }
    return (hullId ?: "").startsWith("astd_") || (baseHullId ?: "").startsWith("astd_")
}

/**
 * 通用：确保 variant 的双模式状态自洽（泛化自 arc 的 ensureASTDArcFlareModeState，参数化全部 id）。
 *
 * 状态机不变量（与 arc 原实现一致）：
 * - 至多存在一个模式 permaMod（crewed 或 automated），且存在与当前模式同向的 next marker。
 * - 异常态「同时存在两模式」→ 收敛到 crewed（移除 automated + 原版 automated，标记下次载人）。
 * - 无模式 permaMod → 按已有 next marker 激活对应模式，缺省载人。
 *
 * @param config 本舰的双模式配置。
 * @param stats 战役上下文舰船 stats（用于切模式时清理不兼容舰长/AI 核心）；纯 refit 校验可传 null。
 */
fun ShipVariantAPI.ensureASTDDualModeState(config: ASTDDualModeConfig, stats: MutableShipStatsAPI? = null) {
    if (!isASTDShipVariant()) return

    migrateLegacyDualModeState(config)

    val hasCrewed = getPermaMods().contains(config.crewedModeId)
    val hasAutomated = getPermaMods().contains(config.automatedModeId)

    if (hasCrewed && hasAutomated) {
        removePermaMod(config.automatedModeId)
        removePermaMod("automated")
        setDualModeNextMarker(config, config.nextCrewedMarker)
        return
    }

    when {
        // 稳定态：当前模式 + 同向 next marker → 无需切换
        hasCrewed -> setDualModeNextMarker(config, config.nextCrewedMarker)
        hasAutomated -> setDualModeNextMarker(config, config.nextAutomatedMarker)
        // 无模式时：按 next marker 激活
        getPermaMods().contains(config.nextAutomatedMarker) -> activateDualMode(config, config.automatedModeId, stats)
        getPermaMods().contains(config.nextCrewedMarker) -> activateDualMode(config, config.crewedModeId, stats)
        else -> activateDualMode(config, config.crewedModeId, stats)
    }
}

/**
 * 通用：激活某模式（泛化自 arc activateMode，参数化全部 id）。
 *
 * 行为：清掉两个模式 permaMod → 挂上目标模式 → 设同向 next marker → 同步原版 "automated" 船插 →
 * 战役上下文下清理不兼容舰长。
 *
 * @param config 本舰的双模式配置。
 * @param modeId 目标模式 hullmod id（应为 config.crewedModeId 或 config.automatedModeId）。
 * @param stats 战役上下文 stats（用于清理不兼容舰长）。
 */
fun ShipVariantAPI.activateDualMode(config: ASTDDualModeConfig, modeId: String, stats: MutableShipStatsAPI? = null) {
    removePermaMod(config.crewedModeId)
    removePermaMod(config.automatedModeId)
    addPermaMod(modeId)
    // 稳定态：next marker = 与当前模式同向
    val nextMarker = if (modeId == config.automatedModeId) config.nextAutomatedMarker else config.nextCrewedMarker
    setDualModeNextMarker(config, nextMarker)
    // 同步原版 'automated' 船插（全自动舰船）
    if (modeId == config.automatedModeId) {
        if (!getPermaMods().contains("automated")) addPermaMod("automated")
    } else {
        removePermaMod("automated")
    }
    // 战役上下文：切换模式时自动卸下不兼容的舰长/AI 核心
    clearIncompatibleDualModeCaptain(stats)
}

/**
 * 通用：判断 variant 当前是否处于无人模式（泛化自 arc hasASTDArcFlareAutomatedMode）。
 * 同时检查 permaMods 与已挂 hullMods，覆盖 refit 临时态与稳定态。
 */
fun ShipVariantAPI.hasASTDDualModeAutomated(config: ASTDDualModeConfig): Boolean =
    getPermaMods().contains(config.automatedModeId) || hasHullMod(config.automatedModeId)

/**
 * 私有：把历史存档里以普通 hullMod 形式存在的模式/标记迁移为 permaMod（泛化自 arc migrateLegacyModeState）。
 * 动机：早期版本可能用 addMod 而非 addPermaMod 写入状态，迁移以保证状态机读 permaMods 时一致。
 */
private fun ShipVariantAPI.migrateLegacyDualModeState(config: ASTDDualModeConfig) {
    val stateIds = listOf(
        config.crewedModeId,
        config.automatedModeId,
        config.nextCrewedMarker,
        config.nextAutomatedMarker,
    )
    for (id in stateIds) {
        if (hasHullMod(id) && !getPermaMods().contains(id)) {
            removeMod(id)
            addPermaMod(id)
        }
    }
}

/**
 * 私有：设置 next marker 为指定标记，并清掉另一向标记（泛化自 arc setNextMarker）。
 */
private fun ShipVariantAPI.setDualModeNextMarker(config: ASTDDualModeConfig, markerId: String) {
    removePermaMod(config.nextCrewedMarker)
    removePermaMod(config.nextAutomatedMarker)
    addPermaMod(markerId)
}

/**
 * 私有：切模式时清理与新模式不兼容的舰长/AI 核心（泛化自 arc clearIncompatibleCaptain）。
 *
 * 这是战役上下文核心防崩例外（全局规范允许）：try/catch 的 catch 体带有意义的 fallback——
 * 先把 AI 核心归还玩家货舱避免丢失，再卸下舰长。fleetMember / captain / sector 在 refit 之外的上下文
 * 可能不可用，此处吞错而非崩溃，但每个 catch 都不是空操作（要么有归还逻辑，要么是无害的 best-effort 卸载）。
 */
private fun clearIncompatibleDualModeCaptain(stats: MutableShipStatsAPI?) {
    val member = try { stats?.fleetMember } catch (_: Throwable) { null } ?: return
    val captain = try { member.captain } catch (_: Throwable) { null }
    if (captain != null) {
        // AI 核心：先归还到玩家货舱，再移除舰长，避免核心丢失
        val aiCoreId = try { captain.aiCoreId } catch (_: Throwable) { null }
        if (aiCoreId != null) {
            try {
                Global.getSector()?.playerFleet?.cargo?.addCommodity(aiCoreId, 1f)
            } catch (_: Throwable) {
                // 归还货舱失败则接受 AI 核心丢失：refit 外/无玩家舰队上下文不可达，
                // 不能因此崩溃影响整个战役层（核心逻辑防崩例外，有意静默）。
            }
        }
    }
    try {
        member.setCaptain(null)
    } catch (_: Throwable) {
    }
}
