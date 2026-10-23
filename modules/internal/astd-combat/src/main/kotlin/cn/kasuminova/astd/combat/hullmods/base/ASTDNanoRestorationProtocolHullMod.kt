package cn.kasuminova.astd.combat.hullmods.base

import cn.kasuminova.astd.ui.dsl.HullmodTheme
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.IntervalUtil
import java.awt.Color
import kotlin.math.min

class ASTDNanoRestorationProtocolHullMod : BaseHullMod() {

    companion object {
        const val ID_ARC: String = "astd_arc_nano_restoration_protocol"

        const val ID_LENS: String = "astd_lens_nano_restoration_protocol"

        private const val BASE_REPAIR_RATIO = 0.005f

        private const val LOW_HULL_THRESHOLD = 0.50f
        private const val LOW_HULL_REPAIR_RATIO = 0.01f

        private const val ARMOR_REPAIR_RATIO = 0.20f
        private const val HULL_REPAIR_RATIO = 0.80f

        private const val ARMOR_TOTAL_CAP_RATIO = 0.005f

        private const val BOOST_DURATION_SEC = 5f
        private const val BOOST_MULT = 2.5f

        private const val FLUX_PER_HULL = 3f
        private const val FLUX_PER_ARMOR = 6f

        private const val TICK_INTERVAL = 0.2f

        private const val BOOST_KEY = "astd_nano_restoration_boost"
        private const val INTERVAL_KEY = "astd_nano_restoration_interval"

        private val THEME_ARC = HullmodThemes.ARC

        private val THEME_LENS = HullmodThemes.LENS
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f || ship.isHulk || ship.hitpoints <= 0f) return

        val flux = ship.fluxTracker ?: return
        val shipId = System.identityHashCode(ship)
        val boostTimerKey = "$BOOST_KEY:$shipId"
        var boostTimer = (engine.customData[boostTimerKey] as? Float) ?: 0f

        if (flux.isOverloaded || flux.isVenting) {
            engine.customData[boostTimerKey] = BOOST_DURATION_SEC
            return
        }

        if (boostTimer > 0f) {
            boostTimer = (boostTimer - amount).coerceAtLeast(0f)
            engine.customData[boostTimerKey] = boostTimer
        }

        val intervalKey = "$INTERVAL_KEY:$shipId"
        var interval = engine.customData[intervalKey] as? IntervalUtil
        if (interval == null) {
            interval = IntervalUtil(TICK_INTERVAL, TICK_INTERVAL)
            engine.customData[intervalKey] = interval
        }
        interval.advance(amount)
        if (!interval.intervalElapsed()) return

        val dt = interval.elapsed

        val armorGrid = ship.armorGrid
        val maxHull = ship.maxHitpoints.coerceAtLeast(1f)
        val maxArmorInCell = armorGrid?.maxArmorInCell?.coerceAtLeast(1f) ?: 0f

        var repairRatio = BASE_REPAIR_RATIO
        if (ship.hitpoints / maxHull < LOW_HULL_THRESHOLD) {
            repairRatio = LOW_HULL_REPAIR_RATIO
        }
        if (boostTimer > 0f) {
            repairRatio *= BOOST_MULT
        }

        val grid = armorGrid?.grid
        val hasArmorDamage = grid != null && maxArmorInCell > 0f && hasAnyArmorDamage(grid, maxArmorInCell)
        val hasHullDamage = ship.hitpoints < maxHull - 1f

        if (!hasArmorDamage && !hasHullDamage) return

        val fullArmorBudget = maxArmorInCell * repairRatio * dt
        val fullHullBudget = maxHull * repairRatio * dt

        var armorBudget: Float
        var hullBudget: Float

        if (hasArmorDamage && hasHullDamage) {
            armorBudget = fullArmorBudget * ARMOR_REPAIR_RATIO
            hullBudget = fullHullBudget * HULL_REPAIR_RATIO
        } else if (hasArmorDamage) {
            armorBudget = fullArmorBudget
            hullBudget = 0f
        } else {
            armorBudget = 0f
            hullBudget = fullHullBudget
        }

        var armorRepaired = 0f
        if (armorBudget > 0f && armorGrid != null && grid != null && maxArmorInCell > 0f) {
            val armorCapPerTick = maxHull * ARMOR_TOTAL_CAP_RATIO * repairRatio / BASE_REPAIR_RATIO * dt
            armorRepaired = repairAllDamagedArmor(armorGrid, grid, maxArmorInCell, armorBudget, armorCapPerTick)
        }

        var hullRepaired = 0f
        if (hullBudget > 0f && hasHullDamage) {
            val missingHull = (maxHull - ship.hitpoints).coerceAtLeast(0f)
            hullRepaired = min(missingHull, hullBudget)
            ship.hitpoints = (ship.hitpoints + hullRepaired).coerceAtMost(maxHull)
        }

        val fluxFromHull = hullRepaired * FLUX_PER_HULL
        val fluxFromArmor = armorRepaired * FLUX_PER_ARMOR
        val totalFlux = fluxFromHull + fluxFromArmor
        if (totalFlux > 0f) {
            flux.increaseFlux(totalFlux, false)
        }
    }

    private fun hasAnyArmorDamage(grid: Array<FloatArray>, maxArmor: Float): Boolean {
        for (x in grid.indices) {
            for (y in grid[x].indices) {
                if (grid[x][y] < maxArmor - 0.1f) return true
            }
        }
        return false
    }

    private fun repairAllDamagedArmor(
        armorGrid: com.fs.starfarer.api.combat.ArmorGridAPI,
        grid: Array<FloatArray>,
        maxArmor: Float,
        perCellBudget: Float,
        totalCap: Float,
    ): Float {
        var totalRepaired = 0f
        for (x in grid.indices) {
            for (y in grid[x].indices) {
                if (totalRepaired >= totalCap) return totalRepaired
                val current = grid[x][y]
                if (current >= maxArmor - 0.1f) continue
                val missing = maxArmor - current
                val repair = min(missing, min(perCellBudget, totalCap - totalRepaired))
                if (repair <= 0f) continue
                armorGrid.setArmorValue(x, y, current + repair)
                totalRepaired += repair
            }
        }
        return totalRepaired
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean
    ) {
        tooltip.hullmodCard(width, theme(), spec?.displayName) {
            para("ui.hullmod.nano.summary")
            heading("ui.hullmod.export.section.note")
            para("ui.hullmod.nano.line.1")
            para("ui.hullmod.nano.line.2")
            para("ui.hullmod.nano.line.3")
            para("ui.hullmod.nano.line.4")
            para("ui.hullmod.nano.line.5")
        }
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isASTDShip()

    override fun getBorderColor(): Color = theme().borderColor

    override fun getNameColor(): Color = theme().nameColor

    private fun theme(): HullmodTheme =
        if (spec?.id == ID_LENS) THEME_LENS else THEME_ARC
}
