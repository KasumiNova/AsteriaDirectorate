package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.ui.TooltipMakerAPI
import org.magiclib.util.MagicLensFlare
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

class ASTDIonizedRecoilAccumulatorHullMod : BaseHullMod() {

    companion object {
        private const val COOLDOWN_SECONDS = 1f
        private const val RANGE = 800f
        private const val HARD_FLUX_CONVERT_FRACTION = 0.03f
        private const val SOFT_FLUX_MULT = 1f
        private const val EMP_MULT = 2f
        private const val BASE_PROC_CHANCE = 0.25f
        private const val MAX_PROC_CHANCE = 0.85f
        private const val PROC_START_FLUX_LEVEL = 0.25f
        private const val PROC_MAX_FLUX_LEVEL = 0.85f
        private const val BEAM_PROC_MULT = 0.10f
        private const val HIT_STRENGTH_BASE_FLUX_FRACTION = 0.02f
        private const val MIN_HIT_STRENGTH_PROC_MULT = 0.10f
        private const val MAX_HIT_STRENGTH_PROC_MULT = 3f
        private const val ARC_THICKNESS = 27f
        private const val ARC_VISUAL_THICKNESS = 18f
        private const val ARC_CORE_WIDTH = 9f
        private const val RECOIL_LENS_FLARE_SPACING = 150f
        private const val RECOIL_LENS_FLARE_RANDOM_OFFSET = 50f

        private val ARC_FRINGE = Color(90, 210, 255, 220)
        private val ARC_CORE = Color(245, 252, 255, 240)

        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(160, 236, 255),
            borderColor = Color(92, 200, 255),
            headerBackground = Color(20, 52, 82, 190),
            sectionBackground = Color(14, 36, 58, 135),
            accentColor = Color(88, 190, 255),
        )
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        if (ship.isHulk || !ship.isAlive) return
        if (!ASTDArcAuraUtil.isArcProductionHull(ship, ASTDArcProductionShipIds.HULL_PLASMA_ARCH)) return
        if (!ship.hasListenerOfClass(IonizedRecoilListener::class.java)) {
            ship.addListener(IonizedRecoilListener(ship))
        }
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean =
        ASTDArcAuraUtil.isArcProductionHull(ship, ASTDArcProductionShipIds.HULL_PLASMA_ARCH)

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun addPostDescriptionSection(tooltip: TooltipMakerAPI, hullSize: ShipAPI.HullSize, ship: ShipAPI?, width: Float, isForModSpec: Boolean) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = ASTDArcProductionTooltipContracts.ionizedRecoilAccumulator.blocks,
            showTitle = false,
        )
    }

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    private class IonizedRecoilListener(private val ship: ShipAPI) : DamageTakenModifier, AdvanceableListener {
        private var cooldown = 0f

        override fun advance(amount: Float) {
            cooldown = (cooldown - amount).coerceAtLeast(0f)
            if (ship.isHulk || !ship.isAlive) ship.removeListener(this)
        }

        override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            if (target !== ship || damage == null || point == null || cooldown > 0f) return null
            val armorHit = !shieldHit && isArmorHit(point)
            if (!shieldHit && !armorHit) return null

            val chance = procChance(param, damage, fluxLevel())
            if (chance <= 0f) return null
            if (Math.random().toFloat() > chance) return null

            val boostLevel = ASTDPlasmaArmorShieldHullMod.boostLevel(ship)
            val conversionMult = 1f + boostLevel
            val converted = convertHardFlux(conversionMult)
            if (converted <= 1f) return null

            cooldown = COOLDOWN_SECONDS
            val hardFluxLevel = hardFluxLevel()
            discharge(point, converted, hardFluxLevel, boostLevel)
            return null
        }

        private fun isArmorHit(point: Vector2f): Boolean {
            val cell = ship.armorGrid.getCellAtLocation(point) ?: return false
            if (cell.size < 2) return false
            val armor = try { ship.armorGrid.getArmorValue(cell[0], cell[1]) } catch (_: Throwable) { 0f }
            return armor > 1f
        }

        private fun hardFluxLevel(): Float {
            val tracker = ship.fluxTracker
            return if (tracker.maxFlux <= 0f) 0f else (tracker.hardFlux / tracker.maxFlux).coerceIn(0f, 1f)
        }

        private fun fluxLevel(): Float {
            val tracker = ship.fluxTracker
            return if (tracker.maxFlux <= 0f) 0f else tracker.fluxLevel.coerceIn(0f, 1f)
        }

        private fun convertHardFlux(conversionMult: Float): Float {
            val tracker = ship.fluxTracker
            val converted = (tracker.hardFlux * HARD_FLUX_CONVERT_FRACTION * conversionMult.coerceAtLeast(0f)).coerceAtLeast(0f)
            if (converted <= 0f) return 0f
            tracker.setHardFlux((tracker.hardFlux - converted).coerceAtLeast(0f))
            tracker.increaseFlux(converted * SOFT_FLUX_MULT, false)
            return converted
        }

        private fun discharge(from: Vector2f, converted: Float, hardFluxLevel: Float, boostLevel: Float) {
            val engine = Global.getCombatEngine() ?: return
            val range = effectiveRecoilRange()
            val target = chooseTarget(engine, range)
            val damage = converted * (1f + boostLevel)
            val emp = converted * EMP_MULT * (1f + boostLevel)
            val pierceChance = (0.15f + 0.70f * hardFluxLevel).coerceIn(0f, 0.95f)
            Global.getSoundPlayer().playSound("system_emp_emitter_impact", 1f, 1f, from, ship.velocity)

            if (target != null) {
                val to = targetPoint(target)
                val params = arcParams()
                val arc = if (Math.random().toFloat() < pierceChance) {
                    engine.spawnEmpArcPierceShields(ship, from, ship, target, DamageType.ENERGY, damage, emp, range, null, ARC_THICKNESS, ARC_FRINGE, ARC_CORE, params)
                } else {
                    engine.spawnEmpArc(ship, from, ship, target, DamageType.ENERGY, damage, emp, range, null, ARC_THICKNESS, ARC_FRINGE, ARC_CORE, params)
                }
                arc.setCoreWidthOverride(ARC_CORE_WIDTH)
                arc.setSingleFlickerMode(true)
                emitRecoilArcPathFlares(engine, from, to, ARC_FRINGE, ARC_CORE)
            } else {
                val angle = MathUtils.getRandomNumberInRange(0f, 360f)
                val dist = MathUtils.getRandomNumberInRange(ship.collisionRadius * 0.45f, ship.collisionRadius * 0.95f)
                val rad = java.lang.Math.toRadians(angle.toDouble())
                val to = Vector2f(from.x + cos(rad).toFloat() * dist, from.y + sin(rad).toFloat() * dist)
                engine.spawnEmpArcVisual(from, ship, to, ship, ARC_VISUAL_THICKNESS, ARC_FRINGE, ARC_CORE, arcParams()).setSingleFlickerMode(true)
                emitRecoilArcPathFlares(engine, from, to, ARC_FRINGE, ARC_CORE)
            }
        }

        private fun procChance(param: Any?, damage: DamageAPI, fluxLevel: Float): Float {
            if (fluxLevel < PROC_START_FLUX_LEVEL) return 0f
            val span = (PROC_MAX_FLUX_LEVEL - PROC_START_FLUX_LEVEL).coerceAtLeast(0.0001f)
            val t = ((fluxLevel - PROC_START_FLUX_LEVEL) / span).coerceIn(0f, 1f)
            val base = BASE_PROC_CHANCE + (MAX_PROC_CHANCE - BASE_PROC_CHANCE) * t
            val beamMult = if (param is BeamAPI) BEAM_PROC_MULT else 1f
            return (base *
                beamMult *
                hitStrengthProcMult(damage.baseDamage)).coerceIn(0f, 1f)
        }

        private fun hitStrengthProcMult(baseDamage: Float): Float {
            val base = ship.fluxTracker.maxFlux * HIT_STRENGTH_BASE_FLUX_FRACTION
            if (base <= 0f) return MIN_HIT_STRENGTH_PROC_MULT
            return (baseDamage.coerceAtLeast(0f) / base).coerceIn(MIN_HIT_STRENGTH_PROC_MULT, MAX_HIT_STRENGTH_PROC_MULT)
        }

        private fun effectiveRecoilRange(): Float {
            val base = ship.allWeapons
                .asSequence()
                .filter { weapon -> weapon.type == WeaponAPI.WeaponType.ENERGY && !weapon.isBeam && !weapon.isDecorative }
                .map { weapon -> weapon.range }
                .filter { range -> range > 0f }
                .maxOrNull()
                ?.coerceAtLeast(RANGE)
                ?: RANGE
            return ship.mutableStats.energyWeaponRangeBonus.computeEffective(base).coerceAtLeast(RANGE)
        }

        private fun chooseTarget(engine: CombatEngineAPI, range: Float): ShipAPI? {
            var best: ShipAPI? = null
            var bestScore = Float.MAX_VALUE
            for (candidate in engine.ships) {
                if (candidate.owner == ship.owner || candidate.isAlly || !candidate.isAlive || candidate.isHulk) continue
                val dist = MathUtils.getDistance(ship.location, candidate.location)
                if (dist > range) continue
                val priority = when {
                    candidate.allWeapons.any { !it.isDecorative && !it.isPermanentlyDisabled } -> 0f
                    hasWorkingEngine(candidate) -> 80f
                    else -> 200f
                }
                val score = dist + priority
                if (score < bestScore) {
                    bestScore = score
                    best = candidate
                }
            }
            return best
        }

        private fun targetPoint(target: ShipAPI): Vector2f {
            val weapon = target.allWeapons
                .filter { !it.isDecorative && !it.isPermanentlyDisabled }
                .minByOrNull { MathUtils.getDistance(ship.location, it.location) }
            if (weapon != null) return Vector2f(weapon.location)

            val engine = target.engineController.shipEngines
                .filter { !it.isPermanentlyDisabled }
                .minByOrNull { MathUtils.getDistance(ship.location, it.location) }
            if (engine != null) return Vector2f(engine.location)

            return Vector2f(target.location)
        }

        private fun hasWorkingEngine(target: ShipAPI): Boolean =
            target.engineController.shipEngines.any { !it.isPermanentlyDisabled }

        private fun arcParams(): EmpArcEntityAPI.EmpArcParams = EmpArcEntityAPI.EmpArcParams().apply {
            segmentLengthMult = 5f
            zigZagReductionFactor = 0.12f
            fadeOutDist = 72f
            minFadeOutMult = 12f
            flickerRateMult = 0.30f
        }

        private fun emitRecoilArcPathFlares(
            engine: CombatEngineAPI,
            from: Vector2f,
            to: Vector2f,
            fringe: Color,
            core: Color,
        ) {
            val dx = to.x - from.x
            val dy = to.y - from.y
            val distance = MathUtils.getDistance(from, to)
            if (distance < RECOIL_LENS_FLARE_SPACING * 0.6f) return
            val count = ceil(distance / RECOIL_LENS_FLARE_SPACING).toInt().coerceAtLeast(1)
            val nx = -dy / distance
            val ny = dx / distance
            for (index in 1..count) {
                val t = ((index - 0.5f) / count).coerceIn(0.05f, 0.95f)
                val side = if (Math.random() < 0.5) -1f else 1f
                val offset = MathUtils.getRandomNumberInRange(0f, RECOIL_LENS_FLARE_RANDOM_OFFSET) * side
                val alongJitter = MathUtils.getRandomNumberInRange(-RECOIL_LENS_FLARE_RANDOM_OFFSET, RECOIL_LENS_FLARE_RANDOM_OFFSET) / distance
                val jt = (t + alongJitter).coerceIn(0.05f, 0.95f)
                val location = Vector2f(
                    from.x + dx * jt + nx * offset,
                    from.y + dy * jt + ny * offset,
                )
                MagicLensFlare.createSharpFlare(
                    engine,
                    ship,
                    location,
                    ARC_CORE_WIDTH * 0.42f,
                    ARC_THICKNESS * MathUtils.getRandomNumberInRange(2.0f, 3.2f),
                    MathUtils.getRandomNumberInRange(0f, 360f),
                    fringe,
                    core,
                )
            }
        }
    }
}
