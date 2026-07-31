package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.combat.hullmods.base.isASTDShip
import cn.kasuminova.astd.combat.shipsystems.ASTDArcFlareOverdriveSystemStats
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.IntervalUtil
import java.awt.Color
import kotlin.math.min

class ASTDNanoRestorationProtocolHullMod : BaseHullMod() {

    companion object {
        // 基础修复速率：
        // - 结构：按最大结构百分比
        // - 装甲：按最受损装甲格最大装甲百分比
        private const val BASE_REPAIR_RATIO = 0.005f            // 0.5%/s
        // 低结构阈值以下的修复速率
        private const val LOW_HULL_THRESHOLD = 0.50f
        private const val LOW_HULL_REPAIR_RATIO = 0.01f          // 1.0%/s（总计）

        // 装甲/结构分配比
        private const val ARMOR_REPAIR_RATIO = 0.20f
        private const val HULL_REPAIR_RATIO = 0.80f

        // 装甲修复总量上限（每秒最多修复结构值的此比例）
        private const val ARMOR_TOTAL_CAP_RATIO = 0.005f  // 0.5%

        // 过载/散热后增幅
        private const val BOOST_DURATION_SEC = 5f
        private const val BOOST_MULT = 2.5f

        // 辐能压力：每修复 1 结构产生 3 辐能，每修复 1 装甲产生 6 辐能
        private const val FLUX_PER_HULL = 3f
        private const val FLUX_PER_ARMOR = 6f

        // 更新间隔（避免每帧跑逻辑）
        private const val TICK_INTERVAL = 0.2f

        private const val BOOST_KEY = "astd_nano_restoration_boost"
        private const val INTERVAL_KEY = "astd_nano_restoration_interval"

        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(170, 255, 196),
            borderColor = Color(88, 212, 140),
            headerBackground = Color(18, 68, 44, 180),
            sectionBackground = Color(12, 46, 30, 120),
            accentColor = Color(60, 180, 100),
        )
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f || ship.isHulk || ship.hitpoints <= 0f) return

        val flux = ship.fluxTracker ?: return
        val shipId = System.identityHashCode(ship)
        val boostTimerKey = "$BOOST_KEY:$shipId"
        var boostTimer = (engine.customData[boostTimerKey] as? Float) ?: 0f

        // 过载/散热中：累计增幅计时器，不修复
        if (flux.isOverloaded || flux.isVenting) {
            engine.customData[boostTimerKey] = BOOST_DURATION_SEC
            return
        }

        // 增幅计时器递减
        if (boostTimer > 0f) {
            boostTimer = (boostTimer - amount).coerceAtLeast(0f)
            engine.customData[boostTimerKey] = boostTimer
        }

        // 间隔控制
        val intervalKey = "$INTERVAL_KEY:$shipId"
        var interval = engine.customData[intervalKey] as? IntervalUtil
        if (interval == null) {
            interval = IntervalUtil(TICK_INTERVAL, TICK_INTERVAL)
            engine.customData[intervalKey] = interval
        }
        interval.advance(amount)
        if (!interval.intervalElapsed()) return

        val dt = interval.elapsed

        // 系统增幅
        val shipKey = shipId.toString()
        val systemBoostKey = "${ASTDArcFlareOverdriveSystemStats.HULLMOD_BOOST_KEY}$shipKey"
        val systemBoost = (engine.customData[systemBoostKey] as? Float ?: 0f).coerceIn(0f, 1f)

        val armorGrid = ship.armorGrid
        val maxHull = ship.maxHitpoints.coerceAtLeast(1f)
        val maxArmorInCell = armorGrid?.maxArmorInCell?.coerceAtLeast(1f) ?: 0f

        // 确定修复速率
        var repairRatio = BASE_REPAIR_RATIO
        if (ship.hitpoints / maxHull < LOW_HULL_THRESHOLD) {
            repairRatio = LOW_HULL_REPAIR_RATIO
        }
        if (boostTimer > 0f) {
            repairRatio *= BOOST_MULT
        }
        // 系统增幅：+100% 效力
        repairRatio *= (1f + systemBoost)

        // 检测装甲与结构是否受损
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

        // 修复装甲（所有受损格，单格速率不变，总量有上限）
        var armorRepaired = 0f
        if (armorBudget > 0f && armorGrid != null && grid != null && maxArmorInCell > 0f) {
            val armorCapPerTick = maxHull * ARMOR_TOTAL_CAP_RATIO * repairRatio / BASE_REPAIR_RATIO * dt
            armorRepaired = repairAllDamagedArmor(armorGrid, grid, maxArmorInCell, armorBudget, armorCapPerTick)
        }

        // 修复结构
        var hullRepaired = 0f
        if (hullBudget > 0f && hasHullDamage) {
            val missingHull = (maxHull - ship.hitpoints).coerceAtLeast(0f)
            hullRepaired = min(missingHull, hullBudget)
            ship.hitpoints = (ship.hitpoints + hullRepaired).coerceAtMost(maxHull)
        }

        // 辐能压力
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

    override fun addPostDescriptionSection(tooltip: TooltipMakerAPI, hullSize: ShipAPI.HullSize, ship: ShipAPI?, width: Float, isForModSpec: Boolean) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = listOf(
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.nano.summary"),
                ASTDHullModTooltipRenderer.heading("ui.hullmod.export.section.note"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.nano.line.1"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.nano.line.2"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.nano.line.3"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.nano.line.4"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.nano.line.5"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.nano.line.6"),
            ),
        )
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isASTDShip()

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor
}
