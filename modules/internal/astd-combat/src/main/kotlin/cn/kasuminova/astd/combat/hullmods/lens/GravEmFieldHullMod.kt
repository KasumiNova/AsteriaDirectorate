package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.lens.GravEmFieldHullMod.Companion.isZw002Ship
import cn.kasuminova.astd.combat.shipsystems.GravStormSystemStats
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import java.awt.Color

class GravEmFieldHullMod : BaseHullMod() {

    private class FieldState {
        val affected = HashSet<ShipAPI>()
        var waveTimer = 0f
        var centerFlare: GravEmFieldVfx.CenterFlare? = null
        var departedWatcherInstalled = false

        var phaseBlend = 0f

        var fieldBlend = 0f
    }

    private inner class DepartedCleanupWatcher(
        private val engine: CombatEngineAPI,
        private val ship: ShipAPI,
    ) : BaseEveryFrameCombatPlugin() {
        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (engine.isEntityInPlay(ship)) return
            val state = ship.customData[STATE_KEY] as? FieldState
            if (state != null) {
                for (target in state.affected) unmodifyTarget(target, modIdOf(ship))
                state.affected.clear()
                state.centerFlare?.dispose()
                state.centerFlare = null
                ship.removeCustomData(STATE_KEY)
            }
            GravEmFieldVfx.disposeWavePools(engine)
            log.info("[ASTD] 引力电子干扰力场：舰船离场（ship=${ship.id}），中心光斑/波形池/压制修饰已收口")
            engine.removePlugin(this)
        }
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f) return
        if (!ship.isZw002Ship()) return

        val state = ship.customData[STATE_KEY] as? FieldState

        if (ship.isHulk) {
            if (state != null) {
                for (target in state.affected) unmodifyTarget(target, modIdOf(ship))
                state.centerFlare?.dispose()
                ship.removeCustomData(STATE_KEY)
            }
            return
        }

        val systemState = ship.system?.state
        val fieldActive = systemState != ShipSystemAPI.SystemState.COOLDOWN
        if (!fieldActive) {
            if (state != null) {
                if (state.affected.isNotEmpty()) {
                    for (target in state.affected) unmodifyTarget(target, modIdOf(ship))
                    state.affected.clear()
                }
                advanceBlends(ship, state, false, amount)
                if (state.fieldBlend > 0f) {
                    state.centerFlare = GravEmFieldVfx.maintainCenterFlare(
                        engine, ship, state.centerFlare, state.phaseBlend, state.fieldBlend,
                    )
                } else if (state.centerFlare != null) {
                    state.centerFlare?.dispose()
                    state.centerFlare = null
                }
            }
            return
        }

        // customData 首次写入必须调用 setCustomData；getCustomData 返回的空表不持久。
        val activeState = state ?: FieldState().also { ship.setCustomData(STATE_KEY, it) }
        if (!activeState.departedWatcherInstalled) {
            activeState.departedWatcherInstalled = true
            engine.addPlugin(DepartedCleanupWatcher(engine, ship))
        }
        val values = GravEmFieldTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
        val modId = modIdOf(ship)

        advanceBlends(ship, activeState, true, amount)

        val seenThisFrame = HashSet<ShipAPI>()
        for (target in engine.ships) {
            if (target == null || target === ship) continue
            if (target.owner == ship.owner || target.isFighter || target.isHulk || !target.isAlive) continue
            val dist = Misc.getDistance(ship.location, target.location)
            val scale = GravEmFieldTuning.effectScale(dist, values.range, values.edgeScale)
            if (scale <= 0f) continue
            applyDebuff(target, modId, scale, values)
            seenThisFrame += target
        }

        activeState.affected.removeAll { target ->
            val stale = target !in seenThisFrame
            if (stale) unmodifyTarget(target, modId)
            stale
        }
        activeState.affected += seenThisFrame

        activeState.centerFlare = GravEmFieldVfx.maintainCenterFlare(
            engine, ship, activeState.centerFlare, activeState.phaseBlend, activeState.fieldBlend,
        )
        driveWaveVisuals(engine, ship, activeState, amount)

        if (ship === engine.playerShip) {
            maintainStatusBar(engine, values)
        }
    }

    private fun applyDebuff(target: ShipAPI, modId: String, scale: Float, values: GravEmFieldTuning.Values) {
        val stats = target.mutableStats

        val empStat = stats.empDamageTakenMult
        empStat.unmodifyMult(modId)
        val currentEmp = empStat.modifiedValue
        if (currentEmp > 0f) {
            val shifted = currentEmp + values.empResistReduction * scale
            empStat.modifyMult(modId, shifted / currentEmp)
        }

        val reductionMult = 1f - values.statPenalty * scale
        stats.maxSpeed.modifyMult(modId, reductionMult)
        stats.acceleration.modifyMult(modId, reductionMult)
        stats.deceleration.modifyMult(modId, reductionMult)
        stats.maxTurnRate.modifyMult(modId, reductionMult)
        stats.turnAcceleration.modifyMult(modId, reductionMult)
        stats.ballisticWeaponRangeBonus.modifyMult(modId, reductionMult)
        stats.energyWeaponRangeBonus.modifyMult(modId, reductionMult)
        stats.missileWeaponRangeBonus.modifyMult(modId, reductionMult)

        stats.autofireAimAccuracy.modifyMult(modId, 1f - values.accuracyPenalty * scale)
    }

    private fun unmodifyTarget(target: ShipAPI, modId: String) {
        val stats = target.mutableStats
        stats.empDamageTakenMult.unmodifyMult(modId)
        stats.maxSpeed.unmodifyMult(modId)
        stats.acceleration.unmodifyMult(modId)
        stats.deceleration.unmodifyMult(modId)
        stats.maxTurnRate.unmodifyMult(modId)
        stats.turnAcceleration.unmodifyMult(modId)
        stats.ballisticWeaponRangeBonus.unmodifyMult(modId)
        stats.energyWeaponRangeBonus.unmodifyMult(modId)
        stats.missileWeaponRangeBonus.unmodifyMult(modId)
        stats.autofireAimAccuracy.unmodifyMult(modId)
    }

    private fun driveWaveVisuals(engine: CombatEngineAPI, ship: ShipAPI, state: FieldState, amount: Float) {
        state.waveTimer += amount
        val chargeGather = ship.customData[GravStormSystemStats.CHARGE_PROGRESS_KEY] as? Float ?: 0f
        while (state.waveTimer >= GravEmFieldTuning.WAVE_INTERVAL) {
            state.waveTimer -= GravEmFieldTuning.WAVE_INTERVAL
            GravEmFieldVfx.spawnWave(engine, ship, state.phaseBlend, chargeGather)
        }
    }

    private fun advanceBlends(ship: ShipAPI, state: FieldState, fieldActive: Boolean, amount: Float) {
        state.phaseBlend = stepBlend(state.phaseBlend, ship.isPhased, amount / GravEmFieldVfx.PHASE_BLEND_SECONDS)
        state.fieldBlend = stepBlend(state.fieldBlend, fieldActive, amount / GravEmFieldVfx.FIELD_BLEND_SECONDS)
    }

    private fun stepBlend(current: Float, on: Boolean, step: Float): Float {
        val target = if (on) 1f else 0f
        return if (current < target) (current + step).coerceAtMost(target)
        else (current - step).coerceAtLeast(target)
    }

    private fun maintainStatusBar(engine: CombatEngineAPI, values: GravEmFieldTuning.Values) {
        engine.maintainStatusForPlayerShip(
            STATUS_KEY,
            spec?.spriteName ?: "",
            I18n[I18n.Categories.MOD, "ui.hullmod.grav_em_field.status.title"],
            I18n.t(
                I18n.Categories.MOD, "ui.hullmod.grav_em_field.status.data",
                "range" to values.range.toInt(),
                "empPct" to percent(values.empResistReduction),
                "statPct" to percent(values.statPenalty),
                "accPct" to percent(values.accuracyPenalty),
            ),
            false,
        )
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        val values = GravEmFieldTuning.resolve(DifficultyTuningImpl, ship == null || ship.owner == 0)
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            para("ui.hullmod.grav_em_field.desc", hl("奇点核心", HullmodThemes.LENS.nameColor))
            heading("ui.hullmod.grav_em_field.section.field")
            para(
                "ui.hullmod.grav_em_field.line.field",
                v("range", values.range.toInt()),
                v("edgePct", percent(values.edgeScale)),
                hl("${values.range.toInt()}su", HullmodTone.HIGHLIGHT),
                hl(percent(values.edgeScale), HullmodTone.HIGHLIGHT),
            )
            table {
                row(
                    "ui.hullmod.grav_em_field.attr.emp",
                    "ui.hullmod.grav_em_field.value.emp",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", percent(values.empResistReduction)),
                )
                row(
                    "ui.hullmod.grav_em_field.attr.stat",
                    "ui.hullmod.grav_em_field.value.stat",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", percent(values.statPenalty)),
                )
                row(
                    "ui.hullmod.grav_em_field.attr.accuracy",
                    "ui.hullmod.grav_em_field.value.accuracy",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", percent(values.accuracyPenalty)),
                )
            }
            para("ui.hullmod.grav_em_field.line.final", hl("最终值减益", HullmodTone.HIGHLIGHT))
            para("ui.hullmod.grav_em_field.line.cooldown")
            spacer(6f)
            para("ui.hullmod.export.difficulty_note", hl("难度系数", HullmodTone.DEFAULT))
        }
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isZw002Ship()

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    companion object {
        const val HULLMOD_ID = "astd_grav_em_field"

        private const val HULL_ID = "astd_zw_002"

        private val log = Global.getLogger(GravEmFieldHullMod::class.java)

        private const val STATE_KEY = "astd_grav_em_field_state"

        private const val MOD_ID_PREFIX = "astd_grav_em_field:"

        private const val STATUS_KEY = "astd_grav_em_field_status"

        private val THEME = HullmodThemes.LENS

        private fun percent(value: Float): String = "${(value * 100f).toInt()}%"

        private fun modIdOf(ship: ShipAPI): String = MOD_ID_PREFIX + ship.id

        private fun ShipAPI?.isZw002Ship(): Boolean {
            val s = this ?: return false
            return s.hullSpec?.hullId == HULL_ID || s.hullSpec?.baseHullId == HULL_ID
        }
    }
}
