package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.effect.system.Xc002GhostWingsEffect
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.v
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

/**
 * 虚数之翼（XC-002 星翼内置船插，规格 blue/10-unique.md XC-002 节）：
 *
 * - 战术系统（裂隙折跃）激活后的 [ImaginaryWingsTuning.WINDOW_SECONDS]s 内，舰船获得
 *   逐渐削减的最大航速与机动性加成（峰值随难度，砺刃档 +100%）；
 * - 武器备弹恢复速度与非导弹武器射速常驻随「当前航速 / 基础最大航速」比例缩放
 *   （[ImaginaryWingsTuning.sustainedMult]：静止 rest%、满速 +full%）；
 * - 航速超出基础最大航速时获得额外武器伤害加成（[ImaginaryWingsTuning.overCapDamageMult]，
 *   每超 1% 加 overCap%，2026-10 裁定：基准为基础最大航速而非含窗口加成的当前上限）。
 *
 * 状态存 `ship.customData`（系统激活边沿与窗口起点）；窗口结束 unmodify 全部速度键，
 * 备弹/射速与超速伤害为常驻通道（每帧重写乘区，modifierId 固定无叠乘路径）。
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
        val fullSpeedPercent = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.FULL_SPEED_BOOST_PERCENT, isPlayer)
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

        // 航速比基准：基础最大航速（不含窗口加成与其他修饰，2026-10 裁定）
        val baseMaxSpeed = ship.mutableStats.maxSpeed.baseValue
        val ratio = if (baseMaxSpeed > 1f) ship.velocity.length() / baseMaxSpeed else 0f

        // 备弹恢复与非导弹武器射速随航速比缩放（常驻通道）
        val sustainMult = ImaginaryWingsTuning.sustainedMult(ratio, fullSpeedPercent)
        ship.mutableStats.ballisticAmmoRegenMult.modifyMult(MOD_ID, sustainMult)
        ship.mutableStats.energyAmmoRegenMult.modifyMult(MOD_ID, sustainMult)
        ship.mutableStats.missileAmmoRegenMult.modifyMult(MOD_ID, sustainMult)
        ship.mutableStats.ballisticRoFMult.modifyMult(MOD_ID, sustainMult)
        ship.mutableStats.energyRoFMult.modifyMult(MOD_ID, sustainMult)

        // 超速段武器伤害加成（常驻通道，ratio ≤ 1 恒 1）
        val damageMult = ImaginaryWingsTuning.overCapDamageMult(ratio, overCapPerPercent)
        ship.mutableStats.ballisticWeaponDamageMult.modifyMult(MOD_ID, damageMult)
        ship.mutableStats.energyWeaponDamageMult.modifyMult(MOD_ID, damageMult)
        ship.mutableStats.missileWeaponDamageMult.modifyMult(MOD_ID, damageMult)

        // HUD（玩家船，攻击方视角）：窗口内显示当前机动加成、备弹/射速与伤害倍率
        if (speedBonus > 0f && ship == engine.playerShip) {
            feedback.maintainPlayerStatus(
                engine, HUD_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.imaginary_wings.status.title"],
                I18n.t(
                    I18n.Categories.MOD, "ui.imaginary_wings.status.desc",
                    "bonus" to formatPercent(speedBonus),
                    "sustain" to formatPercent((sustainMult - 1f) * 100f, signed = true),
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
        // tooltip 展示口径：无舰上下文（装配面板）按我方档位展示（默认砺刃 v2）
        val isPlayer = ship == null || ship.owner == 0
        val peakSpeedPercent = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.SPEED_BOOST_PERCENT, isPlayer)
        val fullSpeedPercent = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.FULL_SPEED_BOOST_PERCENT, isPlayer)
        val overCapPerPercent = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.OVER_CAP_DAMAGE_PER_PERCENT, isPlayer)
        val restPercent = ImaginaryWingsTuning.restBoostPercent(fullSpeedPercent)
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            para("ui.hullmod.imaginary_wings.desc", hl("\"灵能\"", Color(219, 40, 40, 255)))
            heading("ui.hullmod.imaginary_wings.section.overload")
            para(
                "ui.hullmod.imaginary_wings.line.overload",
                v("secs", ImaginaryWingsTuning.WINDOW_SECONDS.toInt()),
                v("pct", formatPercent(peakSpeedPercent) + "%"),
                hl(ImaginaryWingsTuning.WINDOW_SECONDS.toInt().toString(), HullmodTone.HIGHLIGHT),
                hl(formatPercent(peakSpeedPercent) + "%", HullmodTone.POSITIVE)
            )
            heading("ui.hullmod.imaginary_wings.section.glide")
            para(
                "ui.hullmod.imaginary_wings.line.glide",
                v("rest", formatPercent(kotlin.math.abs(restPercent)) + "%"),
                v("full", formatPercent(fullSpeedPercent) + "%"),
                hl(formatPercent(kotlin.math.abs(restPercent)) + "%", if (restPercent < 0f) HullmodTone.RED else HullmodTone.POSITIVE),
                hl(formatPercent(fullSpeedPercent) + "%", HullmodTone.POSITIVE)
            )
            para(
                "ui.hullmod.imaginary_wings.line.glide_overcap",
                v("pct", formatPercent(overCapPerPercent) + "%"),
                hl("1%", HullmodTone.HIGHLIGHT), hl(formatPercent(overCapPerPercent) + "%", HullmodTone.POSITIVE)
            )
            spacer(6f)
            para("ui.hullmod.export.difficulty_note", hl("难度系数", HullmodTone.DEFAULT))
        }
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
        private const val HUD_ICON = "graphics/hullmods/astd_imaginary_wings.png"

        private val feedback: CombatFeedback = CombatFeedbackImpl

        private val THEME = HullmodThemes.IMAGINARY

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
