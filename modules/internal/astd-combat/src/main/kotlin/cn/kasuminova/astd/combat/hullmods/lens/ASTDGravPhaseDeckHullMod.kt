package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.combat.hullmods.lens.ASTDGravPhaseDeckHullMod.Companion.isZw103Ship
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

/**
 * 引力相位甲板（Gravity Phase Deck，purple/20-production.md §2，2026-09 D27 重做）——茑萝级内置插件。
 *
 * 仅对茑萝级生效（[isApplicableToShip] / advanceInCombat 入口 [isZw103Ship] guard）。
 * advanceInCombat 每帧驱动两效果：
 *
 * 1. **引力联结（相位联动）**：母舰进入相位状态（[ShipAPI.isPhased]）时，全部在外战机
 *    同步进入相位状态（[ShipAPI.setPhased] + 半透明），母舰退出相位后严格配对恢复。
 *    相位所有权由 [LinkState.phasedByThis] 标记——只对「本插件相位过」的战机执行 setPhased(false)，
 *    绝不误清其他来源的相位状态（与视差甲板同一配对纪律）。
 *    「战机不会产生相位维持辐能」：战机本身无相位线圈，联动相位不产生任何辐能开销，
 *    无需额外抵消逻辑。
 * 2. **辐能返还**：逐帧统计每架战机的辐能净增量（开火产出的软/硬辐能），按难度系数
 *    比例（[GravPhaseDeckTuning.FLUX_RETURN_RATIO]）从战机扣除并以软辐能形式加到母舰；
 *    母舰辐能水平高于 [GravPhaseDeckTuning.MOTHERSHIP_FLUX_LEVEL_DISABLE] 时失效。
 *    基线表 [LinkState.lastFluxByFighter] 按战机引用维护，战机消失时清理。
 *
 * 状态隔离：HullModEffect 实例按 hullmod 规格全局共享（同场多艘茑萝共用一个实例），
 * 全部逐舰状态挂 [ShipAPI.getCustomData]（战斗内随实体生命周期），按舰天然隔离、
 * 战斗结束随实体回收，不泄漏（2026-09 审查裁定，对照透潮/共享战术网络同款口径）。
 * 注意原版实体级 customData 的写入契约：字段惰性为 null，此时 getCustomData() 返回
 * 一次性空表，直接 put 会写入虚空（逐帧丢失）；首写必须走 [ShipAPI.setCustomData]
 * （2026-09-20 断言C排查实证：茑萝在自动化场景内无任何 setCustomData 调用方，
 * getOrPut 写入的 LinkState 每帧重建，配对恢复永远不执行）。
 *
 * 母舰被击毁：茑萝变残骸瞬间即对 [LinkState.phasedByThis] 中仍在场的战机执行配对释放
 * （setPhased(false) + 透明度复原），不留「母舰已毁、战机永久相位」的悬挂态。
 */
class ASTDGravPhaseDeckHullMod : BaseHullMod() {

    /** 单舰联动状态：由本插件相位过的战机集合 + 战机辐能基线表（逐帧净增量口径）。 */
    private class LinkState {
        val phasedByThis = HashSet<ShipAPI>()
        val lastFluxByFighter = HashMap<ShipAPI, Float>()
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f) return
        if (!ship.isZw103Ship()) return

        // 母舰残骸化：配对释放在外联动战机后移除本舰状态（hulk 的 allWings 不再可信，按引用直接释放）
        if (ship.isHulk) {
            val state = ship.customData[STATE_KEY] as? LinkState ?: return
            for (fighter in state.phasedByThis) {
                if (fighter.isPhased) fighter.isPhased = false
                fighter.extraAlphaMult = 1f
            }
            ship.removeCustomData(STATE_KEY)
            return
        }

        // 首写走 setCustomData（见类注释 customData 写入契约）
        val state = (ship.customData[STATE_KEY] as? LinkState)
            ?: LinkState().also { ship.setCustomData(STATE_KEY, it) }
        val returnRatio = GravPhaseDeckTuning.resolveReturnRatio(DifficultyTuningImpl, ship.owner == 0)
        val mothershipBlocked = ship.fluxTracker.fluxLevel >= GravPhaseDeckTuning.MOTHERSHIP_FLUX_LEVEL_DISABLE
        val mothershipPhased = ship.isPhased

        val seenThisFrame = HashSet<ShipAPI>()
        val wings = ship.allWings ?: return
        for (wing in wings) {
            if (wing == null) continue
            val members = wing.wingMembers ?: continue
            for (fighter in members) {
                if (fighter == null || fighter.isHulk) continue
                seenThisFrame += fighter

                // ---- 引力联结：母舰相位 ↔ 战机相位 ----
                if (mothershipPhased) {
                    if (!fighter.isPhased) {
                        fighter.isPhased = true
                        fighter.extraAlphaMult = LINKED_PHASE_ALPHA
                        state.phasedByThis += fighter
                    }
                } else if (state.phasedByThis.remove(fighter)) {
                    if (fighter.isPhased) fighter.isPhased = false
                    fighter.extraAlphaMult = 1f
                }

                // ---- 辐能返还：战机净产出 × 比例 → 母舰软辐能 ----
                val currFlux = fighter.fluxTracker.currFlux
                val lastFlux = state.lastFluxByFighter.put(fighter, currFlux) ?: currFlux
                if (!mothershipBlocked) {
                    val give = GravPhaseDeckTuning.returnAmount(currFlux - lastFlux, returnRatio)
                    if (give > 0f) {
                        fighter.fluxTracker.decreaseFlux(give)
                        ship.fluxTracker.increaseFlux(give, false)
                    }
                }
            }
        }

        // 回收已消失（本帧未见）的战机条目；战机实体回收后其相位状态随实体一并消失，无需显式恢复。
        state.phasedByThis.retainAll(seenThisFrame)
        state.lastFluxByFighter.keys.retainAll(seenThisFrame)
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
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.grav_phase_deck.summary"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.grav_phase_deck.line.1"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.grav_phase_deck.line.2"),
            ),
        )
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isZw103Ship()

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    companion object {
        /** 联动相位时战机的额外透明度（对齐原版相位态 25% 不透明度观感的折中取值）。 */
        private const val LINKED_PHASE_ALPHA = 0.5f

        /** 逐舰联动状态挂载键（ShipAPI.customData，战斗内随实体生命周期）。 */
        private const val STATE_KEY = "astd_grav_phase_deck_link_state"

        private const val HULL_ID = "astd_zw_103"

        /** 紫主题（与 [ASTDLensParallaxDecksHullMod] 一致，透镜协议视觉统一）。 */
        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(200, 160, 255),
            borderColor = Color(160, 110, 255),
            headerBackground = Color(40, 18, 70, 185),
            sectionBackground = Color(28, 12, 52, 120),
            accentColor = Color(150, 90, 230),
        )

        private fun ShipAPI?.isZw103Ship(): Boolean {
            val s = this ?: return false
            return s.hullSpec?.hullId == HULL_ID || s.hullSpec?.baseHullId == HULL_ID
        }
    }
}
