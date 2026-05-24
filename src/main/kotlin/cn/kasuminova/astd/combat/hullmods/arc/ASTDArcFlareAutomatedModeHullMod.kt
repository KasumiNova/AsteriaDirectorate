package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.renderer.effect.system.ArcFlareOverdriveVisualState
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import org.magiclib.util.MagicLensFlare
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

class ASTDArcFlareAutomatedModeHullMod : BaseHullMod() {

    companion object {
        private const val MAX_SPEED_BONUS = 8f
        private const val ACCEL_MULT = 1.15f
        private const val TURN_MULT = 1.12f
        private const val FLUX_DISSIPATION_BONUS = 150f
        private const val ENERGY_FLUX_MULT = 0.95f
        private const val PEAK_CR_MULT = 0.84f
        private const val SHIELD_UPKEEP_MULT = 1.10f
        private const val SHIELD_DAMAGE_TAKEN_MULT = 1.08f

        private const val ARC_TIMER_KEY = "astd_arc_flare_automated_mode_arc_timer"
        private const val ARC_BUDGET_KEY = "astd_arc_flare_automated_mode_arc_budget"
        private const val ARC_RANGE = 430f
        private const val ARC_INTERVAL = 0.28f

        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(255, 170, 136),
            borderColor = Color(255, 96, 74),
            headerBackground = Color(84, 22, 22, 185),
            sectionBackground = Color(58, 16, 16, 120),
            accentColor = Color(200, 80, 60),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        if (!variant.isASTDShipVariant()) return

        // 切换器被玩家移除 → 立即切换到载人模式并恢复切换器（常驻）
        if (!variant.hasHullMod(ASTDArcFlareHullModIds.SWITCHER)) {
            variant.activateMode(ASTDArcFlareHullModIds.MODE_CREWED, stats)
            variant.addMod(ASTDArcFlareHullModIds.SWITCHER)
            return
        }

        // 无人模式：使用无人版系统
        variant.hullSpec?.setShipSystemId("astd_arc_flare_overdrive_automated")

        stats.maxSpeed.modifyFlat(id, MAX_SPEED_BONUS)
        stats.acceleration.modifyMult(id, ACCEL_MULT)
        stats.deceleration.modifyMult(id, ACCEL_MULT)
        stats.maxTurnRate.modifyMult(id, TURN_MULT)
        stats.turnAcceleration.modifyMult(id, TURN_MULT)
        stats.fluxDissipation.modifyFlat(id, FLUX_DISSIPATION_BONUS)
        stats.energyWeaponFluxCostMod.modifyMult(id, ENERGY_FLUX_MULT)
        stats.peakCRDuration.modifyMult(id, PEAK_CR_MULT)
        stats.shieldUpkeepMult.modifyMult(id, SHIELD_UPKEEP_MULT)
        stats.shieldDamageTakenMult.modifyMult(id, SHIELD_DAMAGE_TAKEN_MULT)
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f || ship.isHulk || ship.hitpoints <= 0f || !ship.isASTDArcFlareShip()) return

        val systemLevel = try {
            ship.system?.effectLevel ?: 0f
        } catch (_: Throwable) {
            0f
        }.coerceIn(0f, 1f)
        val state = try {
            ship.system?.state
        } catch (_: Throwable) {
            null
        }
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
            budget += when (state) {
                ShipSystemAPI.SystemState.IN -> 3f
                ShipSystemAPI.SystemState.ACTIVE -> 0.75f
                ShipSystemAPI.SystemState.OUT -> (0.75f * visualLevel).coerceIn(0f, 0.75f)
                else -> 0f
            }
            while (budget >= 1f) {
                budget -= 1f
                spawnArcDischarge(ship, engine, visualLevel)
            }
        }
        engine.customData[timerKey] = timer
        engine.customData[budgetKey] = budget
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isASTDShip()

    override fun addPostDescriptionSection(tooltip: TooltipMakerAPI, hullSize: ShipAPI.HullSize, ship: ShipAPI?, width: Float, isForModSpec: Boolean) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = listOf(
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.automated.summary"),
                ASTDHullModTooltipRenderer.heading("ui.hullmod.export.section.mode"),
                ASTDHullModTooltipRenderer.table(
                    rows = arrayOf(
                        ASTDHullModTooltipRenderer.row("ui.hullmod.automated.attr.dp", "ui.hullmod.automated.value.dp"),
                        ASTDHullModTooltipRenderer.row("ui.hullmod.automated.attr.hull_armor", "ui.hullmod.automated.value.hull_armor"),
                        ASTDHullModTooltipRenderer.row("ui.hullmod.automated.attr.flux", "ui.hullmod.automated.value.flux"),
                        ASTDHullModTooltipRenderer.row("ui.hullmod.automated.attr.speed", "ui.hullmod.automated.value.speed"),
                        ASTDHullModTooltipRenderer.row("ui.hullmod.automated.attr.cooldown", "ui.hullmod.automated.value.cooldown"),
                    ),
                ),
            ),
        )
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    private fun spawnArcDischarge(ship: ShipAPI, engine: CombatEngineAPI, systemLevel: Float) {
        var arcsSpawned = 0
        val arcCore = ArcFlareOverdriveVisualState.lerpColor(ArcFlareOverdriveVisualState.coldFringe, ArcFlareOverdriveVisualState.hotFringe, systemLevel, 155)
        val arcFringe = ArcFlareOverdriveVisualState.lerpColor(ArcFlareOverdriveVisualState.coldCore, ArcFlareOverdriveVisualState.hotCore, systemLevel, 210)

        val ships = try {
            engine.ships
        } catch (_: Throwable) {
            null
        } ?: emptyList()

        for (target in ships) {
            if (arcsSpawned >= 2) break
            if (target.owner == ship.owner || target.isHulk || target.hitpoints <= 0f || target.isPhased) continue
            if (MathUtils.getDistance(ship.location, target.location) > ARC_RANGE) continue

            val from = randomArcPoint(ship, 0.48f, 0.90f)
            val to = Vector2f(target.location)
            val thickness = 10f + systemLevel * 6f
            val arcParams = EmpArcEntityAPI.EmpArcParams().apply {
                segmentLengthMult = 5f
                zigZagReductionFactor = 0.10f
                fadeOutDist = 30f
                minFadeOutMult = 6f
                flickerRateMult = 0.5f
            }
            try {
                val arc = engine.spawnEmpArcVisual(from, null, to, null, thickness, arcFringe, arcCore, arcParams)
                arc.setCoreWidthOverride(thickness * 0.4f)
                arc.setSingleFlickerMode(true)
                arc.setRenderGlowAtStart(false)
            } catch (_: Throwable) {}
            try {
                MagicLensFlare.createSharpFlare(engine, ship, to, 4f, 35f + 25f * systemLevel, 0f, arcFringe, arcCore)
            } catch (_: Throwable) {}

            if (target.isFighter) {
                engine.applyDamage(target, to, 110f + 70f * systemLevel, DamageType.ENERGY, 80f, false, false, ship)
            } else {
                try {
                    target.fluxTracker?.increaseFlux(90f + 110f * systemLevel, false)
                } catch (_: Throwable) {
                }
            }
            arcsSpawned++
        }

        val missiles = try {
            engine.missiles
        } catch (_: Throwable) {
            null
        } ?: emptyList<MissileAPI>()

        for (missile in missiles) {
            if (arcsSpawned >= 3) break
            if (missile.owner == ship.owner) continue
            if (MathUtils.getDistance(ship.location, missile.location) > ARC_RANGE) continue

            val from = randomArcPoint(ship, 0.50f, 0.95f)
            val to = Vector2f(missile.location)
            val thickness = 9f + systemLevel * 5f
            val arcParams = EmpArcEntityAPI.EmpArcParams().apply {
                segmentLengthMult = 5f
                zigZagReductionFactor = 0.10f
                fadeOutDist = 28f
                minFadeOutMult = 6f
                flickerRateMult = 0.5f
            }
            try {
                val arc = engine.spawnEmpArcVisual(from, null, to, null, thickness, arcFringe, arcCore, arcParams)
                arc.setCoreWidthOverride(thickness * 0.4f)
                arc.setSingleFlickerMode(true)
                arc.setRenderGlowAtStart(false)
            } catch (_: Throwable) {}
            try {
                MagicLensFlare.createSharpFlare(engine, ship, to, 3f, 25f + 20f * systemLevel, 0f, arcFringe, arcCore)
            } catch (_: Throwable) {}
            engine.applyDamage(missile, to, 180f + 90f * systemLevel, DamageType.ENERGY, 120f, false, false, ship)
            arcsSpawned++
        }

        if (arcsSpawned == 0) {
            val from = randomArcPoint(ship, 0.45f, 0.82f)
            val to = randomArcPoint(ship, 1.05f, 1.45f)
            val thickness = 9f + systemLevel * 5f
            val arcParams = EmpArcEntityAPI.EmpArcParams().apply {
                segmentLengthMult = 6f
                zigZagReductionFactor = 0.12f
                fadeOutDist = 35f
                minFadeOutMult = 8f
                flickerRateMult = 0.4f
            }
            try {
                val arc = engine.spawnEmpArcVisual(from, null, to, null, thickness, arcFringe, arcCore, arcParams)
                arc.setCoreWidthOverride(thickness * 0.4f)
                arc.setSingleFlickerMode(true)
                arc.setRenderGlowAtStart(false)
                arc.setFadedOutAtStart(true)
            } catch (_: Throwable) {}
            try {
                MagicLensFlare.createSharpFlare(engine, ship, to, 3f, 30f + 20f * systemLevel, 0f, arcFringe, arcCore)
            } catch (_: Throwable) {}
        }
    }

    private fun randomArcPoint(ship: ShipAPI, radiusMulMin: Float, radiusMulMax: Float): Vector2f {
        val bounds = try { ship.exactBounds } catch (_: Throwable) { null }
        if (bounds != null) {
            try {
                bounds.update(ship.location, ship.facing)
                val segments = bounds.segments
                if (segments.isNotEmpty()) {
                    val seg = segments[MathUtils.getRandomNumberInRange(0, segments.size - 1)]
                    val t = MathUtils.getRandomNumberInRange(0f, 1f)
                    val base = Vector2f(
                        seg.p1.x + (seg.p2.x - seg.p1.x) * t,
                        seg.p1.y + (seg.p2.y - seg.p1.y) * t,
                    )
                    // 按 radiusMulMin/Max 做微小偏移（向外）
                    if (radiusMulMin > 0.95f) {
                        val outAngle = com.fs.starfarer.api.util.Misc.getAngleInDegrees(ship.location, base)
                        val offset = ship.collisionRadius * MathUtils.getRandomNumberInRange(radiusMulMin - 0.90f, radiusMulMax - 0.90f)
                        val rad = Math.toRadians(outAngle.toDouble())
                        return Vector2f(
                            base.x + (cos(rad) * offset).toFloat(),
                            base.y + (sin(rad) * offset).toFloat(),
                        )
                    }
                    return base
                }
            } catch (_: Throwable) {}
        }
        val angle = MathUtils.getRandomNumberInRange(0f, 360f)
        val radius = ship.collisionRadius * MathUtils.getRandomNumberInRange(radiusMulMin, radiusMulMax)
        val rad = Math.toRadians(angle.toDouble())
        return Vector2f(
            ship.location.x + (cos(rad) * radius).toFloat(),
            ship.location.y + (sin(rad) * radius).toFloat(),
        )
    }
}
