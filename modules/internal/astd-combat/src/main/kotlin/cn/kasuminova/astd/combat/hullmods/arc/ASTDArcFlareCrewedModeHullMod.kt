package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.combat.hullmods.base.activateDualMode
import cn.kasuminova.astd.combat.hullmods.base.isASTDShip
import cn.kasuminova.astd.combat.hullmods.base.isASTDShipVariant
import cn.kasuminova.astd.renderer.effect.system.ArcFlareOverdriveVisualState
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

class ASTDArcFlareCrewedModeHullMod : BaseHullMod() {

    companion object {
        private const val PEAK_CR_BONUS_FLAT = 160f
        private const val SHIELD_UPKEEP_MULT = 0.86f
        private const val SHIELD_DAMAGE_TAKEN_MULT = 0.93f
        private const val SHIELD_UNFOLD_MULT = 1.25f
        private const val SHIELD_TURN_MULT = 1.15f
        private const val ENERGY_FLUX_MULT = 0.96f

        private const val ARC_TIMER_KEY = "astd_arc_flare_crewed_mode_arc_timer"
        private const val ARC_BUDGET_KEY = "astd_arc_flare_crewed_mode_arc_budget"
        private const val ARC_INTERVAL = 0.28f

        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(188, 226, 255),
            borderColor = Color(120, 170, 255),
            headerBackground = Color(18, 34, 84, 180),
            sectionBackground = Color(12, 24, 58, 120),
            accentColor = Color(70, 130, 220),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        if (!variant.isASTDShipVariant()) return

        // 切换器被玩家移除 → 立即切换到无人模式并恢复切换器（常驻）
        if (!variant.hasHullMod(ARC_FLARE_DUAL_MODE_CONFIG.switcherId)) {
            variant.activateDualMode(ARC_FLARE_DUAL_MODE_CONFIG, ARC_FLARE_DUAL_MODE_CONFIG.automatedModeId, stats)
            variant.addMod(ARC_FLARE_DUAL_MODE_CONFIG.switcherId)
            return
        }

        // 载人模式：使用载人版系统
        variant.hullSpec?.setShipSystemId(ARC_FLARE_DUAL_MODE_CONFIG.crewedSystemId)

        stats.peakCRDuration.modifyFlat(id, PEAK_CR_BONUS_FLAT)
        stats.shieldUpkeepMult.modifyMult(id, SHIELD_UPKEEP_MULT)
        stats.shieldDamageTakenMult.modifyMult(id, SHIELD_DAMAGE_TAKEN_MULT)
        stats.shieldUnfoldRateMult.modifyMult(id, SHIELD_UNFOLD_MULT)
        stats.shieldTurnRateMult.modifyMult(id, SHIELD_TURN_MULT)
        stats.energyWeaponFluxCostMod.modifyMult(id, ENERGY_FLUX_MULT)
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isASTDShip()

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f || ship.isHulk || ship.hitpoints <= 0f || !ship.isASTDArcFlareShip()) return

        val systemLevel = try {
            ship.system?.effectLevel ?: 0f
        } catch (_: Throwable) { 0f }.coerceIn(0f, 1f)
        val state = try { ship.system?.state } catch (_: Throwable) { null }
        val visualLevel = ArcFlareOverdriveVisualState.getLevel(ship, engine).coerceAtLeast(systemLevel)

        val timerKey = "$ARC_TIMER_KEY:${System.identityHashCode(ship)}"
        val budgetKey = "$ARC_BUDGET_KEY:${System.identityHashCode(ship)}"
        if (visualLevel <= 0.05f) {
            engine.customData[timerKey] = 0f
            engine.customData.remove(budgetKey)
            return
        }

        var timer = (engine.customData[timerKey] as? Float) ?: 0f
        var budget = (engine.customData[budgetKey] as? Float) ?: 0f
        timer += amount
        while (timer >= ARC_INTERVAL) {
            timer -= ARC_INTERVAL
            // 载人模式电弧速率约为自动模式的 40%
            budget += when (state) {
                ShipSystemAPI.SystemState.IN -> 1.2f
                ShipSystemAPI.SystemState.ACTIVE -> 0.30f
                ShipSystemAPI.SystemState.OUT -> (0.30f * visualLevel).coerceIn(0f, 0.30f)
                else -> 0f
            }
            while (budget >= 1f) {
                budget -= 1f
                spawnCrewedArcAccent(ship, engine, visualLevel)
            }
        }
        engine.customData[timerKey] = timer
        engine.customData[budgetKey] = budget
    }

    private fun spawnCrewedArcAccent(ship: ShipAPI, engine: CombatEngineAPI, systemLevel: Float) {
        val arcFringe = ArcFlareOverdriveVisualState.lerpColor(
            ArcFlareOverdriveVisualState.coldFringe, ArcFlareOverdriveVisualState.hotFringe, systemLevel, 155,
        )
        val arcCore = ArcFlareOverdriveVisualState.lerpColor(
            ArcFlareOverdriveVisualState.coldCore, ArcFlareOverdriveVisualState.hotCore, systemLevel, 132,
        )
        val from = hullBoundaryPoint(ship)
        val outAngle = Misc.getAngleInDegrees(ship.location, from) + MathUtils.getRandomNumberInRange(-30f, 30f)
        val outDist = ship.collisionRadius * MathUtils.getRandomNumberInRange(0.22f, 0.40f)
        val rad = Math.toRadians(outAngle.toDouble())
        val to = Vector2f(
            from.x + (cos(rad) * outDist).toFloat(),
            from.y + (sin(rad) * outDist).toFloat(),
        )
        val params = EmpArcEntityAPI.EmpArcParams().apply {
            segmentLengthMult = 5f
            zigZagReductionFactor = 0.12f
            fadeOutDist = 30f
            minFadeOutMult = 6f
            flickerRateMult = 0.45f
        }
        val thickness = 5f + systemLevel * 3f
        try {
            val arc = engine.spawnEmpArcVisual(from, ship, to, ship, thickness, arcFringe, arcCore, params)
            arc.setCoreWidthOverride(thickness * 0.45f)
            arc.setSingleFlickerMode(true)
            arc.setRenderGlowAtStart(false)
        } catch (_: Throwable) {}
    }

    private fun hullBoundaryPoint(ship: ShipAPI): Vector2f {
        val bounds = try { ship.exactBounds } catch (_: Throwable) { null }
        if (bounds != null) {
            try {
                bounds.update(ship.location, ship.facing)
                val segments = bounds.segments
                if (segments.isNotEmpty()) {
                    val seg = segments[MathUtils.getRandomNumberInRange(0, segments.size - 1)]
                    val t = MathUtils.getRandomNumberInRange(0f, 1f)
                    return Vector2f(
                        seg.p1.x + (seg.p2.x - seg.p1.x) * t,
                        seg.p1.y + (seg.p2.y - seg.p1.y) * t,
                    )
                }
            } catch (_: Throwable) {}
        }
        val angle = MathUtils.getRandomNumberInRange(0f, 360f)
        val radius = ship.collisionRadius * 0.90f
        val rad = Math.toRadians(angle.toDouble())
        return Vector2f(
            ship.location.x + (cos(rad) * radius).toFloat(),
            ship.location.y + (sin(rad) * radius).toFloat(),
        )
    }

    override fun addPostDescriptionSection(tooltip: TooltipMakerAPI, hullSize: ShipAPI.HullSize, ship: ShipAPI?, width: Float, isForModSpec: Boolean) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = listOf(
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.crewed.summary"),
                ASTDHullModTooltipRenderer.heading("ui.hullmod.export.section.mode"),
                ASTDHullModTooltipRenderer.table(
                    rows = arrayOf(
                        ASTDHullModTooltipRenderer.row("ui.hullmod.crewed.attr.peak", "ui.hullmod.crewed.value.peak"),
                        ASTDHullModTooltipRenderer.row("ui.hullmod.crewed.attr.shield_speed", "ui.hullmod.crewed.value.shield_speed"),
                        ASTDHullModTooltipRenderer.row("ui.hullmod.crewed.attr.shield_damage", "ui.hullmod.crewed.value.shield_damage"),
                        ASTDHullModTooltipRenderer.row("ui.hullmod.crewed.attr.weapon_range", "ui.hullmod.crewed.value.weapon_range"),
                        ASTDHullModTooltipRenderer.row("ui.hullmod.crewed.attr.flux", "ui.hullmod.crewed.value.flux"),
                    ),
                ),
            ),
        )
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor
}
