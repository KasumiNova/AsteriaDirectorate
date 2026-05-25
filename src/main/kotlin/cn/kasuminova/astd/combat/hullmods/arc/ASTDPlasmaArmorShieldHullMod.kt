package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

class ASTDPlasmaArmorShieldHullMod : BaseHullMod() {

    companion object {
        private const val SHIELD_SHUNT_ID = "shield_shunt"
        private const val ARMOR_BONUS_GAIN_FRACTION = 0.66f

        private const val BASE_SHIELD_ARMOR_MIN = 0.05f
        private const val BASE_SHIELD_ARMOR_MAX = 0.20f
        private const val BOOST_SHIELD_ARMOR_MIN = 0.10f
        private const val BOOST_SHIELD_ARMOR_MAX = 0.40f

        private const val ENERGY_SHIELD_MULT = 0.85f
        private const val KINETIC_SHIELD_MULT = 0.67f
        private const val HE_SHIELD_MULT = 1.33f
        private const val FRAG_SHIELD_MULT = 1.20f
        private const val ARMOR_KINETIC_MULT = 0.67f

        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(150, 232, 255),
            borderColor = Color(90, 180, 255),
            headerBackground = Color(20, 52, 82, 190),
            sectionBackground = Color(14, 36, 58, 135),
            accentColor = Color(88, 190, 255),
        )

        internal fun boostLevel(ship: ShipAPI): Float =
            (ship.customData[ASTDArcProductionShipIds.DATA_PLASMA_SHIELD_BOOST_LEVEL] as? Float ?: 0f).coerceIn(0f, 1f)

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        stats.kineticArmorDamageTakenMult.modifyMult(id, ARMOR_KINETIC_MULT)
        stats.energyShieldDamageTakenMult.modifyMult(id, ENERGY_SHIELD_MULT)
        stats.kineticShieldDamageTakenMult.modifyMult(id, KINETIC_SHIELD_MULT)
        stats.highExplosiveShieldDamageTakenMult.modifyMult(id, HE_SHIELD_MULT)
        stats.fragmentationShieldDamageTakenMult.modifyMult(id, FRAG_SHIELD_MULT)
        val baseArmor = stats.variant?.hullSpec?.armorRating ?: return
        correctPositiveArmorBonuses(stats, baseArmor, id)
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || ship.isHulk || !ship.isAlive) return
        if (!ASTDArcAuraUtil.isArcProductionHull(ship, ASTDArcProductionShipIds.HULL_PLASMA_ARCH)) return

        val state = plasmaGridState(ship)
        state.advance(amount)
        val shield = ship.shield
        if (shield?.isOn == true) {
            ASTDArcProductionVfx.setCounter(engine, ASTDArcProductionVfx.TELEMETRY_PLASMA_ARCH_SHIELD_OPEN, 1)
        }
        if (!ship.hasListenerOfClass(PlasmaArmorShieldListener::class.java)) {
            ship.addListener(PlasmaArmorShieldListener(ship, state))
        }

        correctPositiveArmorBonuses(
            ship.mutableStats,
            ship.armorGrid.armorRating,
            ASTDArcProductionShipIds.HULLMOD_PLASMA_ARMOR_SHIELD,
        )
        renderOpenShieldArcs(ship, engine, amount, boostLevel(ship))
        maintainPlayerHud(ship, engine, state)
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean {
        if (ship.variant?.hasHullMod(SHIELD_SHUNT_ID) == true) return false
        return ASTDArcAuraUtil.isArcProductionHull(ship, ASTDArcProductionShipIds.HULL_PLASMA_ARCH)
    }

    override fun getUnapplicableReason(ship: ShipAPI): String? {
        if (ship.variant?.hasHullMod(SHIELD_SHUNT_ID) == true) {
            return I18n[I18n.Categories.MOD, "ui.hullmod.plasma_armor_shield.incompatible_shunt"]
        }
        return null
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun addPostDescriptionSection(tooltip: TooltipMakerAPI, hullSize: ShipAPI.HullSize, ship: ShipAPI?, width: Float, isForModSpec: Boolean) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = ASTDArcProductionTooltipContracts.plasmaArmorShield.blocks,
        )
    }

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    private fun plasmaGridState(ship: ShipAPI): ASTDPlasmaShieldGridState {
        val existing = ship.customData[ASTDArcProductionShipIds.DATA_PLASMA_SHIELD_GRID_STATE] as? ASTDPlasmaShieldGridState
        if (existing != null) return existing
        val created = ASTDPlasmaShieldGridState(ship.mutableStats.armorBonus.computeEffective(ship.armorGrid.armorRating))
        ship.setCustomData(ASTDArcProductionShipIds.DATA_PLASMA_SHIELD_GRID_STATE, created)
        return created
    }

    private fun correctPositiveArmorBonuses(stats: MutableShipStatsAPI, baseArmor: Float, id: String) {
        val armorBonus = stats.armorBonus
        val uncorrected = armorBonus.createCopy().also { it.unmodify(id) }.computeEffective(baseArmor)
        val excess = (uncorrected - baseArmor).coerceAtLeast(0f)
        val desired = baseArmor + excess * ARMOR_BONUS_GAIN_FRACTION
        val correction = desired - uncorrected
        if (correction < -0.01f) {
            armorBonus.modifyFlat(id, correction)
        } else {
            armorBonus.unmodify(id)
        }
    }

    private fun renderOpenShieldArcs(ship: ShipAPI, engine: CombatEngineAPI, amount: Float, boostLevel: Float) {
        val shield = ship.shield ?: return
        if (!shield.isOn) return
        var timer = (ship.customData["astd_plasma_shield_arc_timer"] as? Float ?: 0f) - amount
        if (timer > 0f) {
            ship.setCustomData("astd_plasma_shield_arc_timer", timer)
            return
        }
        val interval = if (boostLevel > 0.05f) MathUtils.getRandomNumberInRange(0.12f, 0.22f) else MathUtils.getRandomNumberInRange(0.28f, 0.48f)
        timer = interval
        ship.setCustomData("astd_plasma_shield_arc_timer", timer)

        ASTDArcProductionVfx.emitPlasmaShieldArc(engine, ship, boostLevel > 0.05f)
    }

    private fun maintainPlayerHud(ship: ShipAPI, engine: CombatEngineAPI, state: ASTDPlasmaShieldGridState) {
        if (engine.playerShip !== ship) return
        val sectors = buildString {
            for (idx in 0 until ASTDPlasmaShieldGridState.SECTOR_COUNT) {
                val fraction = state.effectiveArmorFraction(idx)
                append(
                    when {
                        fraction >= 0.66f -> '+'
                        fraction >= 0.33f -> '='
                        else -> '-'
                    },
                )
            }
        }
        engine.maintainStatusForPlayerShip(
            ASTDArcProductionShipIds.DATA_PLASMA_SHIELD_GRID_STATE,
            null,
            I18n[I18n.Categories.MOD, "ui.hullmod.plasma_armor_shield.hud_grid"],
            sectors,
            false,
        )
    }

    private class PlasmaArmorShieldListener(
        private val ship: ShipAPI,
        private val grid: ASTDPlasmaShieldGridState,
    ) : DamageTakenModifier, AdvanceableListener {

        override fun advance(amount: Float) {
            if (ship.isHulk || !ship.isAlive) {
                ship.removeListener(this)
            }
        }

        override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            val dmg = damage ?: return null
            val hitPoint = point ?: return null
            if (target !== ship || ship.isHulk || !ship.isAlive) return null

            if (shieldHit) {
                val sector = sectorForPoint(ship, hitPoint)
                val finalMaxArmor = ship.mutableStats.armorBonus.computeEffective(ship.armorGrid.armorRating).coerceAtLeast(1f)
                val boosted = boostLevel(ship)
                val minFraction = lerp(BASE_SHIELD_ARMOR_MIN, BOOST_SHIELD_ARMOR_MIN, boosted)
                val maxFraction = lerp(BASE_SHIELD_ARMOR_MAX, BOOST_SHIELD_ARMOR_MAX, boosted)
                val shieldArmor = finalMaxArmor * lerp(minFraction, maxFraction, grid.effectiveArmorFraction(sector))
                val incoming = effectiveDamageAmount(param, dmg).coerceAtLeast(0f)
                val mult = armorStyleShieldMultiplier(incoming, shieldArmor)
                if (mult < 0.999f) dmg.modifier.modifyMult(ASTDArcProductionShipIds.STAT_PLASMA_ARMOR_SHIELD, mult)
                grid.applyAbsorbedDamage(sector, incoming * (1f - mult))
                Global.getCombatEngine()?.let { engine ->
                    ASTDArcProductionVfx.emitPlasmaShieldHit(engine, ship, hitPoint, boosted)
                }
            }

            return null
        }

        private fun armorStyleShieldMultiplier(incomingDamage: Float, armor: Float): Float {
            if (incomingDamage <= 0f || armor <= 0f) return 1f
            val mitigated = incomingDamage * incomingDamage / (incomingDamage + armor)
            return (mitigated / incomingDamage).coerceIn(0.05f, 1f)
        }

        private fun sectorForPoint(ship: ShipAPI, point: Vector2f): Int {
            val angle = Misc.getAngleInDegrees(ship.location, point)
            return grid.sectorForHitAngle(angle - ship.facing)
        }
        private fun effectiveDamageAmount(param: Any?, damage: DamageAPI): Float {
            val duration = if (damage.isDps) damage.dpsDuration.coerceAtLeast(0f) else 1f
            return damage.damage.coerceAtLeast(0f) * duration
        }
    }
}
