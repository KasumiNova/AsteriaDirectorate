package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.effect.system.Xc002GhostWingsEffect
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

/**
 * 虚数之翼（XC-002 淬刃内置船插，规格 blue/10-unique.md XC-002 节）：
 *
 * - 战术系统（裂隙折跃）激活后的 [ImaginaryWingsTuning.WINDOW_SECONDS]s 内，舰船获得
 *   逐渐削减的最大航速与机动性加成（峰值随难度，砺刃档 +100%）；
 * - 武器伤害常驻随「当前航速 / 当前最大航速」比例缩放（[ImaginaryWingsTuning.damageMult]：
 *   静止 −fullBonus/2、满速 +fullBonus、超上限每 1% 再加 overCap%）。
 *
 * 状态存 `ship.customData`（系统激活边沿与窗口起点）；窗口结束 unmodify 全部速度键，
 * 伤害缩放为常驻通道（每帧重写乘区，modifierId 固定无叠乘路径）。
 *
 * 特效挂载：每帧向 [Xc002GhostWingsEffect] 登记本舰（虚数光翼渲染由渲染侧承担）。
 */
class ASTDImaginaryWingsHullMod : BaseHullMod() {

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        if (!ship.isAlive || ship.isHulk) return
        val engine = Global.getCombatEngine() ?: return
        Xc002GhostWingsEffect.track(engine, ship)
        if (engine.isPaused) return

        val isPlayer = ship.owner == 0
        val peakSpeedPercent = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.SPEED_BOOST_PERCENT, isPlayer)
        val fullSpeedBonus = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.FULL_SPEED_DAMAGE_PERCENT, isPlayer) / 100f
        val overCapPerPercent = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.OVER_CAP_DAMAGE_PER_PERCENT, isPlayer)

        // 系统激活边沿侦测：ACTIVE 起点开窗（chargeUp 不计入窗口）
        val systemActive = ship.system?.state == ShipSystemAPI.SystemState.ACTIVE
        val wasActive = ship.customData[KEY_PREV_ACTIVE] == true
        if (systemActive && !wasActive) {
            ship.customData[KEY_WINDOW_START] = engine.getTotalElapsedTime(false)
        }
        ship.customData[KEY_PREV_ACTIVE] = systemActive

        val windowStart = ship.customData[KEY_WINDOW_START] as? Float
        val elapsed = if (windowStart != null) engine.getTotalElapsedTime(false) - windowStart else Float.MAX_VALUE
        val speedBonus = ImaginaryWingsTuning.speedBonusPercent(elapsed, peakSpeedPercent)
        if (speedBonus > 0f) {
            ship.mutableStats.maxSpeed.modifyPercent(MOD_ID, speedBonus)
            ship.mutableStats.acceleration.modifyPercent(MOD_ID, speedBonus)
            ship.mutableStats.deceleration.modifyPercent(MOD_ID, speedBonus)
            ship.mutableStats.maxTurnRate.modifyPercent(MOD_ID, speedBonus)
            ship.mutableStats.turnAcceleration.modifyPercent(MOD_ID, speedBonus)
        } else {
            ship.mutableStats.maxSpeed.unmodifyPercent(MOD_ID)
            ship.mutableStats.acceleration.unmodifyPercent(MOD_ID)
            ship.mutableStats.deceleration.unmodifyPercent(MOD_ID)
            ship.mutableStats.maxTurnRate.unmodifyPercent(MOD_ID)
            ship.mutableStats.turnAcceleration.unmodifyPercent(MOD_ID)
        }

        // 伤害随航速比缩放（常驻通道）：ratio 用含窗口加成的当前上限
        val maxSpeed = ship.mutableStats.maxSpeed.modifiedValue
        val ratio = if (maxSpeed > 1f) ship.velocity.length() / maxSpeed else 0f
        val damageMult = ImaginaryWingsTuning.damageMult(ratio, fullSpeedBonus, overCapPerPercent)
        ship.mutableStats.ballisticWeaponDamageMult.modifyMult(MOD_ID, damageMult)
        ship.mutableStats.energyWeaponDamageMult.modifyMult(MOD_ID, damageMult)
        ship.mutableStats.missileWeaponDamageMult.modifyMult(MOD_ID, damageMult)

        // HUD（玩家船，攻击方视角）：窗口内显示当前机动加成与伤害倍率
        if (speedBonus > 0f && ship == engine.playerShip) {
            feedback.maintainPlayerStatus(
                engine, HUD_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.imaginary_wings.status.title"],
                I18n.t(
                    I18n.Categories.MOD, "ui.imaginary_wings.status.desc",
                    "bonus" to formatPercent(speedBonus),
                    "damage" to formatPercent((damageMult - 1f) * 100f, signed = true),
                ),
                negative = false,
            )
        }
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
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.imaginary_wings.summary"),
                ASTDHullModTooltipRenderer.heading("ui.hullmod.export.section.effect"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.imaginary_wings.line.1", padTop = 4f),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.imaginary_wings.line.2", padTop = 2f),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.imaginary_wings.line.3", padTop = 2f),
            ),
        )
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun isApplicableToShip(ship: ShipAPI): Boolean {
        val hullId = ship.hullSpec?.hullId
        val baseHullId = ship.hullSpec?.baseHullId
        return hullId == HULL_ID || baseHullId == HULL_ID
    }

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    companion object {
        private const val HULL_ID = "astd_xc_002"

        /** 乘区修饰键（= hullmod id 字面值；BaseHullMod 不开放 id 属性，修饰键需自备）。 */
        private const val MOD_ID = "astd_imaginary_wings"

        /** ship.customData 键：上一帧系统是否处于 ACTIVE（边沿侦测）。 */
        private const val KEY_PREV_ACTIVE = "astd_imaginary_wings_prev_active"

        /** ship.customData 键：当前速度窗口起点（战斗秒）。 */
        private const val KEY_WINDOW_START = "astd_imaginary_wings_window_start"

        private const val HUD_KEY = "astd_imaginary_wings_status"
        private const val HUD_ICON = "graphics/hullmods/astd_vectorized_jet_array.png"

        private val feedback: CombatFeedback = CombatFeedbackImpl

        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(216, 178, 255),
            borderColor = Color(170, 110, 255),
            headerBackground = Color(46, 24, 82, 180),
            sectionBackground = Color(32, 16, 58, 120),
            accentColor = Color(130, 80, 220),
        )

        /** 百分比显示格式：整数去小数点（如 100 / 12.5）；[signed] 时附带正负号。 */
        private fun formatPercent(value: Float, signed: Boolean = false): String {
            val rounded = Math.round(value * 10f) / 10f
            val body = if (rounded == Math.floor(rounded.toDouble()).toFloat()) {
                rounded.toInt().toString()
            } else {
                rounded.toString()
            }
            return if (signed && value > 0f) "+$body" else body
        }
    }
}
