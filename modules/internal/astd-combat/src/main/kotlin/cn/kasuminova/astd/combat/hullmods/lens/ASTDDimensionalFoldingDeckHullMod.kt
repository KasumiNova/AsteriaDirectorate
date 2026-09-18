package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
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
 * 2. **联队扩容（难度系数）**：每甲板联队战机数量 +50%/+150%/+250%（轨一三锚点，玩家固定 v2）。
 *    经 `FighterLaunchBayAPI` extraDeployments 体系实现（引擎唯一按甲板生效的扩容通道，
 *    见 [FoldingDeckTuning] 头注）：每帧把每个甲板的 extraDeploymentLimit 锚定到折算上限、
 *    extraDeployments 补足差额、extraDuration 置 1e7（与原生战机一致，不触发强制返航）。
 *    额外编制战机阵亡后由甲板空闲分支自动补员，与原生战机行为一致。
 *
 * 仅对飞蓬级生效（[isApplicableToShip] / advanceInCombat 入口 [isZw102Ship] guard）。
 */
class ASTDDimensionalFoldingDeckHullMod : BaseHullMod() {

    override fun applyEffectsBeforeShipCreation(
        hullSize: ShipAPI.HullSize,
        stats: MutableShipStatsAPI,
        id: String,
    ) {
        stats.dynamic.getMod(FIGHTER_COST_MOD).modifyMult(id, FoldingDeckTuning.OP_COST_MULT)
        stats.dynamic.getMod(BOMBER_COST_MOD).modifyMult(id, FoldingDeckTuning.OP_COST_MULT)
        stats.dynamic.getMod(INTERCEPTOR_COST_MOD).modifyMult(id, FoldingDeckTuning.OP_COST_MULT)
        stats.dynamic.getMod(SUPPORT_COST_MOD).modifyMult(id, FoldingDeckTuning.OP_COST_MULT)
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f || ship.isHulk) return
        if (!ship.isZw102Ship()) return

        val bonusMult = FoldingDeckTuning.resolveWingSizeMult(DifficultyTuningImpl, ship.owner == 0)
        for (bay in ship.launchBaysCopy) {
            val wing = bay.wing ?: continue
            val baseNum = wing.spec?.numFighters ?: continue
            val limit = FoldingDeckTuning.wingSizeLimit(baseNum, bonusMult)
            if (limit <= baseNum) continue

            bay.extraDeploymentLimit = limit
            // 补员差额：额外编制阵亡后甲板空闲分支每次消耗 1 点额度，这里按帧补足，
            // 使联队规模长期锚定在折算上限。
            val extraNeeded = limit - baseNum
            if (bay.extraDeployments < extraNeeded) bay.extraDeployments = extraNeeded
            bay.extraDuration = FoldingDeckTuning.EXTRA_FIGHTER_DURATION
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

        /** 原版 dynamic mod 键：战斗机/轰炸机/截击机/支援机 LPC 装配点乘区。 */
        private const val FIGHTER_COST_MOD = "fighter_cost_mod"
        private const val BOMBER_COST_MOD = "bomber_cost_mod"
        private const val INTERCEPTOR_COST_MOD = "interceptor_cost_mod"
        private const val SUPPORT_COST_MOD = "support_cost_mod"

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
