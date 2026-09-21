package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.FighterLaunchBayAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

/**
 * 维度折叠甲板（Dimensional Folding Deck）——飞蓬级（ZW-102）内置船插
 * （purple/20-production.md §1，2026-09 重构）。
 *
 * 两效果（数值声明与折算纯函数见 [FoldingDeckTuning]）：
 *
 * 1. **装配代价（固定）**：战机 LPC 装配点 +50%。经 dynamic mod 打到四类机种 cost 键
 *    （fighter/bomber/interceptor/support_cost_mod）；原版 `all_fighter_cost_mod` 在
 *    `FighterWingSpec.getOpCost` 中取值结果被丢弃（原版 bug），故逐机种键写入。
 *    注意必须覆写 [affectsOPCosts] 返回 true：装配 OP 账目走
 *    `HullVariantSpec.statsForOpCosts`，该 stats 只应用 affectsOPCosts()=true 的船插
 *    （原版 `updateStatsForOpCosts`），否则装配界面 OP 总量仍按基础价扣除。
 *    （已知的原版显示边界：LPC 详情面板 `FighterWingStatsDisplay` 恒以 `getOpCost(null)`
 *    取基础价，任何船插都无法改变该面板读数；选择器列表与实际扣点为本船插生效口径。）
 * 2. **联队扩容（难度系数）**：每甲板联队战机数量 +50%/+150%/+250%（轨一三锚点，玩家固定 v2）。
 *    经 `FighterLaunchBayAPI` extraDeployments 体系实现（引擎唯一按甲板生效的扩容通道，
 *    见 [FoldingDeckTuning] 头注）：每帧把每个甲板的 extraDeploymentLimit 锚定到折算上限
 *    （上限变更时同步把 extraDuration 置 1e7，与原生战机一致，不触发强制返航）、
 *    extraDeployments 按帧补足差额。
 *    额外编制战机阵亡后由甲板空闲分支自动补员，与原生战机行为一致；过载/散辐/CR 归零
 *    期间暂停补足（与原版 canRequestReplacement 口径对齐），上限锚定不受此影响。
 *
 * 仅对飞蓬级生效（[isApplicableToShip] / advanceInCombat 入口 [isZw102Ship] guard）。
 */
class ASTDDimensionalFoldingDeckHullMod : BaseHullMod() {

    override fun applyEffectsBeforeShipCreation(
        hullSize: ShipAPI.HullSize,
        stats: MutableShipStatsAPI,
        id: String,
    ) {
        stats.dynamic.getMod(Stats.FIGHTER_COST_MOD).modifyMult(id, FoldingDeckTuning.OP_COST_MULT)
        stats.dynamic.getMod(Stats.BOMBER_COST_MOD).modifyMult(id, FoldingDeckTuning.OP_COST_MULT)
        stats.dynamic.getMod(Stats.INTERCEPTOR_COST_MOD).modifyMult(id, FoldingDeckTuning.OP_COST_MULT)
        stats.dynamic.getMod(Stats.SUPPORT_COST_MOD).modifyMult(id, FoldingDeckTuning.OP_COST_MULT)
    }

    // 装配 OP 账目（HullVariantSpec.statsForOpCosts）只应用 affectsOPCosts()=true 的船插；
    // 不覆写则装配界面 OP 总量按 LPC 基础价扣除（原版 BaseHullMod 默认 false）。
    override fun affectsOPCosts(): Boolean = true

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f || ship.isHulk) return
        if (!ship.isZw102Ship()) return

        val bonusMult = FoldingDeckTuning.resolveWingSizeMult(DifficultyTuningImpl, ship.owner == 0)
        // 补员闸门：与原版常规补员口径对齐（canRequestReplacement 仅判过载/散辐/CR>0，
        // 不判 isPullBackFighters——回收战机是玩家指令语义，且原版 FighterLaunchBay 的
        // 空闲分支也不判此项，故此处同样放行）。闸门只拦截「额外编制补足」，
        // 不拦截上限锚定：锚定是扩容存在的根本，若受闸门影响，过载/散辐/CR 归零的
        // 任意一帧都会导致联队规模上限丢失、联机编制永久回落到基础值。
        val canRefill = !ship.fluxTracker.isOverloadedOrVenting && ship.currentCR > 0f

        for (bay in ship.launchBaysCopy) {
            // 空甲板（未装配联队）属正常配置，直接跳过。
            val wing = bay.wing ?: continue
            // 已装联队的 spec 原版语义下恒非空；若为 null 说明引擎行为偏离预期，记日志后跳过。
            val spec = wing.spec
            if (spec == null) {
                log.warn("dimensional_folding_deck: bay wing spec is null on ${ship.hullSpec?.hullId}, skipped")
                continue
            }
            val baseNum = spec.numFighters
            val limit = FoldingDeckTuning.wingSizeLimit(baseNum, bonusMult)

            if (bay.extraDeploymentLimit != limit) {
                bay.extraDeploymentLimit = limit
                // 额外编制战机的整备倒计时（与原生战机一致，不触发强制返航），仅随上限变更写一次。
                bay.extraDuration = FoldingDeckTuning.EXTRA_FIGHTER_DURATION
            }
            // 补员差额：额外编制阵亡后甲板空闲分支每次消耗 1 点额度，这里按帧补足，
            // 使联队规模长期锚定在折算上限；过载/散辐/CR 归零期间暂停补足。
            if (canRefill) {
                val extraNeeded = limit - baseNum
                if (bay.extraDeployments < extraNeeded) bay.extraDeployments = extraNeeded
                // 快速出库（仅开场首轮补满）：extraDeployments 空闲分支每个整备间隔仅发射
                // 1 架（原版默认间隔为秒级整备时长），首轮爬编期按帧把待发间隔压到
                // 0.3~0.6s（原版 makeCurrentIntervalFast 语义），使额外编制开场「一次性
                // 补满」——引擎无批量发射 API，此为最快合法路径。
                // 必须闩死在首轮：intervalTracker 全甲板共享，不闩则原生编制的战中补员
                // 同样被永久压到 0.3~0.6s/架（超出设计的隐性增益）。
                val fillKey = initialFillKey(ship, bay)
                if (engine.customData[fillKey] != true) {
                    if (wing.wingMembers.count { !it.isHulk } >= limit) {
                        engine.customData[fillKey] = true
                    } else {
                        bay.makeCurrentIntervalFast()
                    }
                }
            }
        }
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = listOf(
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.dimensional_folding_deck.summary"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.dimensional_folding_deck.line.1"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.dimensional_folding_deck.line.2"),
            ),
        )
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isZw102Ship()

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    companion object {
        /** 飞蓬级舰体 id（guard 与适用性判定共用）。 */
        const val HULL_ID: String = "astd_zw_102"

        private val log = Global.getLogger(ASTDDimensionalFoldingDeckHullMod::class.java)

        /**
         * 开场首轮补满闩的 customData key（每船每甲板一条，整场只闩一次）。
         * 键用 ship.id + 甲板槽位 id（战斗内唯一且稳定），不用 identityHashCode。
         */
        private fun initialFillKey(ship: ShipAPI, bay: FighterLaunchBayAPI): String =
            "astd_dfd_initial_fill:${ship.id}:${bay.weaponSlot?.id}"

        /** 紫主题（与透镜阵列核心一致，透镜协议视觉统一）。 */
        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(200, 160, 255),
            borderColor = Color(160, 110, 255),
            headerBackground = Color(40, 18, 70, 185),
            sectionBackground = Color(28, 12, 52, 120),
            accentColor = Color(150, 90, 230),
        )
    }
}

/** 飞蓬级（ZW-102）舰体判定（hullId 或 baseHullId 命中）。 */
internal fun ShipAPI?.isZw102Ship(): Boolean {
    val s = this ?: return false
    val hullId = s.hullSpec?.hullId
    val baseHullId = s.hullSpec?.baseHullId
    return hullId == ASTDDimensionalFoldingDeckHullMod.HULL_ID ||
            baseHullId == ASTDDimensionalFoldingDeckHullMod.HULL_ID
}
