package cn.kasuminova.astd.combat.hullmods.base

import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.buildWith
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import java.awt.Color

/**
 * 通用双模式（载人 / 无人）切换器 hullmod。
 *
 * 动机：arc_flare、gravitational_lens 等所有 ASTD 双模式舰共用同一套「在改装界面拆下切换器即轮换模式」
 * 的交互。早期每艘舰各自有一个专属切换器（arc 的 ASTDArcFlareDualModeSwitcherHullMod、lens 的同类，均已废弃删除），
 * 重复且难维护。本类是唯一通用切换器：对任意 ASTD 舰（[isASTDShip]）可装，
 * 其 tooltip 根据当前舰的 [ASTDDualModeConfig] 与 variant permaMods 动态显示「当前模式 / 拆下后切到的目标模式」。
 *
 * 职责边界（重要）：
 * - 切换器是双模式状态机的**统一引导点**：在 [applyEffectsBeforeShipCreation] 经
 *   [ASTDDualModeRegistry.configForVariant] 反查本舰 config 后调用 [ensureASTDDualModeState]，
 *   保证「装了切换器的 ASTD 双模式舰」在 variant 缺模式 permaMod 时收敛到缺省模式（载人）。
 *   动机（防回归）：早期依赖各舰 variant 静态声明模式 permaMod，一旦某舰漏声明（如曾经的
 *   astd_gravitational_lens_Standard），其模式 hullmod 因不在 variant 上而永不触发、整套机制失效。
 *   由切换器统一引导后，任何注册了 config 的双模式舰即便 variant 漏声明也能自举到载人模式，
 *   不再要求每舰手写 permaMod 才能工作（静态声明仍保留作为首选，二者互为冗余兜底）。
 * - 模式**切换**（拆即切：拆下切换器→切到对侧）仍由各舰 mode hullmod 在自己的
 *   applyEffectsBeforeShipCreation 中处理（它们才知道对侧模式 id）；本类只负责**自举到有模式态**
 *   + 渲染动态说明 + 可装/可见判定。反查不到 config（非注册双模式舰）则跳过自举，仅作展示。
 * - 本类的具体接入（.ship builtInMods / variant hullMods）由 arc / lens 各自完成。
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

    /**
     * 统一引导双模式状态：装了切换器的已注册双模式舰，经 registry 反查 config 后确保 variant 模式
     * 自洽（缺模式 permaMod → 收敛到缺省载人模式）。这是防「variant 漏声明模式 permaMod」回归的统一兜底。
     *
     * 反查不到 config（非注册双模式舰，或 modSpec 预览语境下无具体 variant）→ 跳过，不臆造状态。
     * 模式**切换**不在此处（由各舰 mode hullmod 的拆即切处理）；本处只保证「至少有一个模式在 variant 上」。
     */
    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        val config = ASTDDualModeRegistry.configForVariant(variant) ?: return
        variant.ensureASTDDualModeState(config, stats)
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
