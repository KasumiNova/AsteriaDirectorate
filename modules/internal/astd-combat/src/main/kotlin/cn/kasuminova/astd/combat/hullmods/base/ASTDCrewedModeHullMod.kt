package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

/**
 * 通用双模式·载人模式 hullmod（舰船无关）。
 *
 * 动机：除 xc_001 / zw_001 等显式注册专属双模式配置的舰船外，所有 ASTD 舰共用
 * [ASTDDualModeGenericIds.MODE_CREWED] 这一通用载人模式标记，使「双模式切换器」对全 ASTD 舰船可用。
 *
 * 职责：
 * - 拆即切：玩家在改装界面拆下切换器 → 立即切到无人模式并把切换器加回（切换器常驻）。
 * - 系统互换：仅当本舰配置声明了 [ASTDDualModeConfig.crewedSystemId] 时才 setShipSystemId
 *   （通用配置为 null，不互换）。
 * - 通用载人模式本身不改任何舰船数值；模式差异由各舰专属 mode hullmod 或原版 "automated" 船插承担。
 */
class ASTDCrewedModeHullMod : BaseHullMod() {

    companion object {
        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(168, 190, 230),
            borderColor = Color(120, 150, 200),
            headerBackground = Color(24, 34, 56, 185),
            sectionBackground = Color(20, 28, 46, 120),
            accentColor = Color(143, 182, 255),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        if (!variant.isASTDShipVariant()) return
        val config = ASTDDualModeRegistry.configForVariant(variant) ?: return
        // 本 hullmod 不是该舰配置的载人模式（异常组合）→ 不干预，避免与专属 mode hullmod 重复处理
        if (id != config.crewedModeId) return

        // 拆即切：切换器被玩家移除 → 立即切换到无人模式并恢复切换器（常驻）
        if (!variant.hasHullMod(config.switcherId)) {
            variant.activateDualMode(config, config.automatedModeId, stats)
            variant.addMod(config.switcherId)
            return
        }

        // 系统互换：仅显式声明了载人版系统 id 的舰（通用配置为 null → 不互换）
        config.crewedSystemId?.let { variant.hullSpec?.setShipSystemId(it) }
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isASTDShip()

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun addPostDescriptionSection(tooltip: TooltipMakerAPI, hullSize: ShipAPI.HullSize, ship: ShipAPI?, width: Float, isForModSpec: Boolean) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = listOf(
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.mode_crewed.summary"),
                ASTDHullModTooltipRenderer.heading("ui.hullmod.export.section.mode"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.mode_crewed.hint"),
            ),
        )
    }

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor
}
