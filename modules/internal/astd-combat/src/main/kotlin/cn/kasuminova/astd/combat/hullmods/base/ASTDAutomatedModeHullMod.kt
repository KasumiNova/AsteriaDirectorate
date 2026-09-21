package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

/**
 * 通用双模式·无人模式 hullmod（舰船无关）。
 *
 * 动机：除 xc_001 / zw_001 等显式注册专属双模式配置的舰船外，所有 ASTD 舰共用
 * [ASTDDualModeGenericIds.MODE_AUTOMATED] 这一通用无人模式标记，使「双模式切换器」对全 ASTD 舰船可用。
 *
 * 职责：
 * - 拆即切：玩家在改装界面拆下切换器 → 立即切到载人模式并把切换器加回（切换器常驻）。
 * - 系统互换：仅当本舰配置声明了 [ASTDDualModeConfig.automatedSystemId] 时才 setShipSystemId
 *   （通用配置为 null，不互换）。
 * - 无人化行为（无人点数、AI 核心需求等）由通用状态机同步的原版 "automated" 船插承担
 *   （见 [activateDualMode]），本类不重复实现。
 */
class ASTDAutomatedModeHullMod : BaseHullMod() {

    companion object {
        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(255, 196, 150),
            borderColor = Color(220, 150, 90),
            headerBackground = Color(66, 40, 18, 185),
            sectionBackground = Color(48, 28, 14, 120),
            accentColor = Color(230, 170, 100),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        if (!variant.isASTDShipVariant()) return
        val config = ASTDDualModeRegistry.configForVariant(variant) ?: return
        // 本 hullmod 不是该舰配置的无人模式（异常组合）→ 不干预，避免与专属 mode hullmod 重复处理
        if (id != config.automatedModeId) return

        // 拆即切：切换器被玩家移除 → 立即切换到载人模式并恢复切换器（常驻）
        if (!variant.hasHullMod(config.switcherId)) {
            variant.activateDualMode(config, config.crewedModeId, stats)
            variant.addMod(config.switcherId)
            return
        }

        // 系统互换：仅显式声明了无人版系统 id 的舰（通用配置为 null → 不互换）
        config.automatedSystemId?.let { variant.hullSpec?.shipSystemId = it }
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isASTDShip()

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean
    ) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = listOf(
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.mode_automated.summary"),
                ASTDHullModTooltipRenderer.heading("ui.hullmod.export.section.mode"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.mode_automated.hint"),
            ),
        )
    }

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor
}
