package cn.kasuminova.astd.combat.effect.arc.eda

import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.combat.effect.generic.CombatVfxBootstrap
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import kotlin.math.roundToInt

class ElectricDriveAcceleratorWeaponEffect : EveryFrameWeaponEffectPlugin {

    private var warnedNaNFlux = false

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        // .wpn 只有一个 everyFrameEffect 槽，弹体 VFX 管线的 bootstrap 必须由此代行。
        CombatVfxBootstrap.ensureInstalled(engine)

        val ship = weapon.ship ?: return
        // hulk 后监听器恒返回 0 加成，无需额外摘除。
        if (ship.isHulk) return

        // 射程加成由 WeaponRangeModifier 监听器按武器结算（仅同类武器生效），此处只负责注册。
        if (!ship.hasListenerOfClass(ElectricDriveAcceleratorRangeModifier::class.java)) {
            ship.addListener(RANGE_MODIFIER)
        }

        // fluxLevel 为软+硬辐能合计。
        val levelRaw = ship.fluxLevel
        if (levelRaw.isNaN() && !warnedNaNFlux) {
            warnedNaNFlux = true
            log.warn("[ASTD] EDA 读到 NaN 辐能水平，按 0 加成处理: ship=${ship.id}, hull=${ship.hullSpec?.hullId}")
        }
        val bonus = ElectricDriveAcceleratorDifficulty.rangeBonus(DifficultyTuningImpl, ship.owner, levelRaw)

        if (bonus > 0f && ship === engine.playerShip && Global.getSettings().isDevMode) {
            feedback.maintainPlayerStatus(
                engine,
                HUD_KEY,
                HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.eda.range_status.title"],
                I18n.t(I18n.Categories.MOD, "ui.eda.range_status.desc", "bonus" to bonus.roundToInt()),
                negative = false,
            )
        }
    }

    companion object {
        private const val HUD_KEY = "astd_eda_range_status"

        private const val HUD_ICON = "graphics/hullmods/astd_arc_singularity_power.png"

        private val RANGE_MODIFIER = ElectricDriveAcceleratorRangeModifier()

        private val feedback: CombatFeedback = CombatFeedbackImpl

        private val log = Global.getLogger(ElectricDriveAcceleratorWeaponEffect::class.java)
    }
}
