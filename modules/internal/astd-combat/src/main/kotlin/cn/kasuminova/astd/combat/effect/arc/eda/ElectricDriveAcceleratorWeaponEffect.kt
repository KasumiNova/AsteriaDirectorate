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
    private var warnedMissingSlot = false

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        // .wpn 只有一个 everyFrameEffect 槽，弹体 VFX 管线的 bootstrap 必须由此代行。
        CombatVfxBootstrap.ensureInstalled(engine)

        val ship = weapon.ship ?: return
        // hulk 后 stats 不再结算，modifier 随实体终结。
        if (ship.isHulk) return

        // fluxLevel 为软+硬辐能合计。
        val levelRaw = ship.fluxLevel
        if (levelRaw.isNaN() && !warnedNaNFlux) {
            warnedNaNFlux = true
            log.warn("[ASTD] EDA 读到 NaN 辐能水平，按 0 加成处理: ship=${ship.id}, hull=${ship.hullSpec?.hullId}")
        }
        val bonus = ElectricDriveAcceleratorDifficulty.rangeBonus(DifficultyTuningImpl, ship.owner, levelRaw)

        val modId = RANGE_MOD_PREFIX + modIdSuffix(weapon)
        if (bonus > 0f) {
            ship.mutableStats.ballisticWeaponRangeBonus.modifyFlat(modId, bonus)
        } else {
            ship.mutableStats.ballisticWeaponRangeBonus.unmodify(modId)
        }

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

    private fun modIdSuffix(weapon: WeaponAPI): String {
        val slotId = weapon.slot?.id
        if (slotId != null) return slotId
        if (!warnedMissingSlot) {
            warnedMissingSlot = true
            log.warn("[ASTD] EDA 武器无槽位引用，射程 modifierId 退化用武器实例 id: weapon=${weapon.id}")
        }
        return weapon.id ?: "unknown"
    }

    companion object {
        private const val RANGE_MOD_PREFIX = "astd_eda_range_bonus:"

        private const val HUD_KEY = "astd_eda_range_status"

        private const val HUD_ICON = "graphics/hullmods/astd_arc_singularity_power.png"

        private val feedback: CombatFeedback = CombatFeedbackImpl

        private val log = Global.getLogger(ElectricDriveAcceleratorWeaponEffect::class.java)
    }
}
