package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.lens.ASTDGravPhaseDeckHullMod.Companion.SYSTEM_LOCK_COOLDOWN_REMAINING
import cn.kasuminova.astd.combat.hullmods.lens.ASTDGravPhaseDeckHullMod.Companion.isZw103Ship
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

class ASTDGravPhaseDeckHullMod : BaseHullMod() {

    private class LinkState {
        val phasedByThis = HashSet<ShipAPI>()
        val lastFluxByFighter = HashMap<ShipAPI, Float>()
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f) return
        if (!ship.isZw103Ship()) return

        if (ship.isHulk) {
            val state = ship.customData[STATE_KEY] as? LinkState ?: return
            for (fighter in state.phasedByThis) {
                if (fighter.isPhased) fighter.isPhased = false
                fighter.extraAlphaMult = 1f
            }
            ship.removeCustomData(STATE_KEY)
            return
        }

        // customData 首次写入必须调用 setCustomData；getCustomData 返回的空表不持久。
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

                if (mothershipPhased) {
                    if (!fighter.isPhased) {
                        fighter.isPhased = true
                        fighter.extraAlphaMult = LINKED_PHASE_ALPHA
                        state.phasedByThis += fighter
                    }
                    // 原版无禁用战术系统 API，必须用 deactivate 与冷却余量阻止相位期间重新激活。
                    val system = fighter.system
                    if (system != null) {
                        if (system.state != ShipSystemAPI.SystemState.IDLE &&
                            system.state != ShipSystemAPI.SystemState.COOLDOWN
                        ) {
                            system.deactivate()
                        }
                        if (system.cooldownRemaining < SYSTEM_LOCK_COOLDOWN_REMAINING) {
                            system.cooldownRemaining = SYSTEM_LOCK_COOLDOWN_REMAINING
                        }
                    }
                } else if (state.phasedByThis.remove(fighter)) {
                    if (fighter.isPhased) fighter.isPhased = false
                    fighter.extraAlphaMult = 1f
                }

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
        val returnRatio = GravPhaseDeckTuning.resolveReturnRatio(DifficultyTuningImpl, ship == null || ship.owner == 0)
        val ratioText = "${(returnRatio * 100f).toInt()}%"
        val blockText = "${(GravPhaseDeckTuning.MOTHERSHIP_FLUX_LEVEL_DISABLE * 100f).toInt()}%"
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            para("ui.hullmod.grav_phase_deck.desc", hl("维度折叠甲板", HullmodThemes.LENS.nameColor))
            heading("ui.hullmod.grav_phase_deck.section.phase_link")
            para(
                "ui.hullmod.grav_phase_deck.line.phase_link",
                hl("引力相位", HullmodTone.HIGHLIGHT),
            )
            heading("ui.hullmod.grav_phase_deck.section.flux_return")
            para(
                "ui.hullmod.grav_phase_deck.line.flux_return",
                v("ratio", ratioText), v("blockPct", blockText),
                hl(ratioText, HullmodTone.HIGHLIGHT), hl("一视同仁", HullmodTone.HIGHLIGHT), hl(blockText, HullmodTone.HIGHLIGHT)
            )
            spacer(6f)
            para("ui.hullmod.export.difficulty_note", hl("难度系数", HullmodTone.DEFAULT))
        }
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isZw103Ship()

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    companion object {
        private const val LINKED_PHASE_ALPHA = 0.5f

        private const val SYSTEM_LOCK_COOLDOWN_REMAINING = 0.5f

        private const val STATE_KEY = "astd_grav_phase_deck_link_state"

        private const val HULL_ID = "astd_zw_103"

        private val THEME = HullmodThemes.LENS

        private fun ShipAPI?.isZw103Ship(): Boolean {
            val s = this ?: return false
            return s.hullSpec?.hullId == HULL_ID || s.hullSpec?.baseHullId == HULL_ID
        }
    }
}
