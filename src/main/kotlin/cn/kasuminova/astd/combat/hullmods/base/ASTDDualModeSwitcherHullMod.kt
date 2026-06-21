package cn.kasuminova.astd.combat.hullmods.base

import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.buildWith
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import java.awt.Color

/**
 * 通用双模式（载人 / 无人）切换器 hullmod。
 *
 * 动机：arc_flare、gravitational_lens 等所有 ASTD 双模式舰共用同一套「在改装界面拆下切换器即轮换模式」
 * 的交互。早期每艘舰各自有一个专属切换器（如 [cn.kasuminova.astd.combat.hullmods.arc.ASTDArcFlareDualModeSwitcherHullMod]），
 * 重复且难维护。本类是唯一通用切换器：对任意 ASTD 舰（[isASTDShip]）可装，
 * 其 tooltip 根据当前舰的 [ASTDDualModeConfig] 与 variant permaMods 动态显示「当前模式 / 拆下后切到的目标模式」。
 *
 * 职责边界（重要）：
 * - 切换器本身**不知道**具体舰的模式 id 集合，因此**不**在 [applyEffectsBeforeShipCreation] 调用
 *   [ensureASTDDualModeState]。模式状态的自洽由各舰的 mode hullmod（Task 4/5）在自己的
 *   applyEffectsBeforeShipCreation 中保证。本类只负责渲染动态说明 + 提供可装/可见判定。
 * - 本类**不**注册到任何 .ship / variant；arc / lens 的接入是 Task 4/5。
 *
 * tooltip 动态文案实现：渲染器 [ASTDHullModTooltipRenderer] 仅支持静态 i18n key（无参数替换），
 * 无法表达「当前模式名」这类运行期才知道的内容。故本类不走 renderBlocks，而是直接用 [buildWith] DSL 的
 * `para(category, key, color, pad, vararg vars)` ——它支持 `%mode%` / `%target%` 命名占位替换，
 * 并通过 `<param:#RRGGBB:key>` 标记高亮替换后的模式名（见 strings.json）。
 */
class ASTDDualModeSwitcherHullMod : BaseHullMod() {

    companion object {
        /**
         * 通用中性主题：不偏 arc（橙）也不偏特定舰，使用中性蓝灰。
         * accentColor 与 strings.json 中 `<param:#8FB6FF:...>` 的高亮色一致，视觉统一。
         */
        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(168, 190, 230),
            borderColor = Color(120, 150, 200),
            headerBackground = Color(24, 34, 56, 185),
            sectionBackground = Color(20, 28, 46, 120),
            accentColor = Color(143, 182, 255),
        )
    }

    /** 可装判定：仅 ASTD 舰可装本通用切换器。 */
    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isASTDShip()

    /** refit 改装界面的船插选择器中，仅对 ASTD 舰显示本切换器。 */
    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = ship.isASTDShip()

    /**
     * 动态说明区：根据当前舰的双模式配置与 variant 当前模式，渲染「当前模式 + 拆下后切到的目标模式」。
     *
     * 降级：若反查不到本舰 config（[ASTDDualModeRegistry.configForShip] 返回 null），
     * 仅显示通用 summary（说明这是双模式切换器），不臆造模式名。
     */
    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        val config = ASTDDualModeRegistry.configForShip(ship)
        val title = spec?.displayName ?: ""

        tooltip.buildWith {
            spacer(6f)
            withLatticePulseBackground(accentColor = THEME.accentColor, width = width) {
                heading(title, THEME.nameColor, THEME.headerBackground, 6f)
                spacer(2f)
                para(
                    I18n.Categories.MOD,
                    "ui.hullmod.dual_mode_switcher.summary",
                    Misc.getTextColor(),
                    4f,
                )

                // 降级：无 config（非已注册的双模式舰，或在 modSpec 预览语境下拿不到具体舰）→ 仅通用说明。
                if (config == null) return@withLatticePulseBackground

                val automated = isAutomatedMode(ship, config)
                val currentModeName = modeName(automated)
                val targetModeName = modeName(!automated)

                spacer(4f)
                para(
                    I18n.Categories.MOD,
                    "ui.hullmod.dual_mode_switcher.current",
                    Misc.getTextColor(),
                    2f,
                    "mode" to currentModeName,
                )
                para(
                    I18n.Categories.MOD,
                    "ui.hullmod.dual_mode_switcher.hint",
                    Misc.getTextColor(),
                    2f,
                    "target" to targetModeName,
                )
            }
        }
    }

    /**
     * 判断当前舰是否处于无人模式：检查 variant 的 permaMods 是否含本舰无人模式 id。
     * 找不到 variant / permaMods 不可达时按载人模式（false）处理——这是纯展示判定，
     * 退化到默认模式名比崩溃合理（核心防崩例外，有意静默）。
     */
    private fun isAutomatedMode(ship: ShipAPI?, config: ASTDDualModeConfig): Boolean {
        val variant = try { ship?.variant } catch (_: Throwable) { null } ?: return false
        val permaMods = try { variant.permaMods } catch (_: Throwable) { null } ?: return false
        if (permaMods.contains(config.automatedModeId)) return true
        if (permaMods.contains(config.crewedModeId)) return false
        // 既无无人也无载人模式 permaMod：尚未结算的临时态，按载人默认展示（与状态机缺省一致）。
        return false
    }

    /** 模式名 i18n：true=无人、false=载人。 */
    private fun modeName(automated: Boolean): String =
        if (automated) I18n[I18n.Categories.MOD, "ui.dual_mode.automated"]
        else I18n[I18n.Categories.MOD, "ui.dual_mode.crewed"]

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor
}
