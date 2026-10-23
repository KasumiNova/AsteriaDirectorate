package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.combat.lens.system.GravTimeflowTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.effect.system.GravTimeflowLinkVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import com.fs.starfarer.api.util.Misc
import java.awt.Color

class GravTimeflowInterferenceSystemStats : BaseShipSystemScript() {

    private class TimeflowActivation(
        val target: ShipAPI,
        val link: GravTimeflowLinkVfx?,
    )

    override fun apply(
        stats: MutableShipStatsAPI,
        id: String,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ) {
        val ship = stats.entity as? ShipAPI ?: return
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        if (ship.isHulk) {
            // 源舰残骸化后引擎不保证回调 unapply，必须主动清理，否则友军目标舰整场保持时流修饰
            teardown(engine, ship)
            return
        }

        when (state) {
            ShipSystemStatsScript.State.IN -> activateOnce(engine, ship)
            else -> Unit
        }
        when (state) {
            ShipSystemStatsScript.State.IN,
            ShipSystemStatsScript.State.ACTIVE,
            ShipSystemStatsScript.State.OUT,
            -> driveInterference(engine, ship, state, effectLevel)

            else -> Unit
        }
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        val ship = stats.entity as? ShipAPI ?: return
        val engine = Global.getCombatEngine() ?: return
        teardown(engine, ship)
    }

    private fun teardown(engine: CombatEngineAPI, ship: ShipAPI) {
        val activation = engine.customData.remove(activationKey(ship)) as? TimeflowActivation ?: return
        activation.link?.dispose()
        removeTargetTimeflow(activation.target)
    }

    private fun activateOnce(engine: CombatEngineAPI, ship: ShipAPI) {
        if (engine.customData[activationKey(ship)] != null) return

        val target = findTarget(ship)
        if (target == null) {
            log.error("引力时流干涉器激活瞬间无有效目标（ship=${ship.id}），isUsable 闸门被绕过？")
            return
        }

        val values = GravTimeflowTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
        val targetSystem = target.system
        if (targetSystem != null && targetSystem.cooldownRemaining > 0f) {
            targetSystem.cooldownRemaining = targetSystem.cooldownRemaining * (1f - values.cooldownReduction)
        }

        val link = GravTimeflowLinkVfx.attach(engine, ship, target)
        engine.customData[activationKey(ship)] = TimeflowActivation(target, link)
    }

    private fun driveInterference(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ) {
        val activation = engine.customData[activationKey(ship)] as? TimeflowActivation ?: return
        val target = activation.target
        if (!engine.isEntityInPlay(target) || !target.isAlive || target.isHulk) {
            activation.link?.dispose()
            return
        }

        val values = GravTimeflowTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
        val timeMult = 1f + (values.timeMult - 1f) * effectLevel.coerceIn(0f, 1f)

        val jitter = computeJitterParams(ship, state, effectLevel)
        val selfJitterLevel = jitter.level * SELF_JITTER_MULT
        val selfRangeBonus = jitter.rangeBonus * SELF_JITTER_MULT

        target.mutableStats.timeMult.modifyMult(TIMEFLOW_MOD_ID, timeMult)
        for (wing in target.allWings) {
            for (fighter in wing.wingMembers) {
                if (fighter.isHulk) continue
                fighter.mutableStats.timeMult.modifyMult(TIMEFLOW_MOD_ID, timeMult)
                fighter.setJitter(
                    JITTER_KEY, JITTER_COLOR, selfJitterLevel, FIGHTER_JITTER_COPIES,
                    0f, selfRangeBonus,
                )
            }
        }

        ship.setJitterUnder(
            JITTER_KEY, JITTER_UNDER_COLOR, selfJitterLevel, SELF_JITTER_UNDER_COPIES,
            0f, SELF_JITTER_UNDER_BASE_RANGE + selfRangeBonus,
        )
        target.setJitterUnder(
            JITTER_KEY, JITTER_UNDER_COLOR, jitter.level, TARGET_JITTER_UNDER_COPIES,
            0f, TARGET_JITTER_UNDER_BASE_RANGE + jitter.rangeBonus,
        )

        activation.link?.update(engine.elapsedInLastFrame, effectLevel)
    }

    private data class JitterParams(val level: Float, val rangeBonus: Float)

    // jitter 包络对齐原版 TemporalShellStats
    private fun computeJitterParams(
        ship: ShipAPI,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ): JitterParams {
        val clampedEffect = effectLevel.coerceIn(0f, 1f)
        var rawLevel = clampedEffect
        var rangeBonus = 0f
        when (state) {
            ShipSystemStatsScript.State.IN -> {
                val chargeUpDur = ship.system?.chargeUpDur ?: 0f
                rawLevel = (clampedEffect * chargeUpDur).coerceIn(0f, 1f)
                rangeBonus = rawLevel * MAX_JITTER_RANGE_BONUS
            }

            ShipSystemStatsScript.State.ACTIVE -> {
                rawLevel = 1f
                rangeBonus = MAX_JITTER_RANGE_BONUS
            }

            ShipSystemStatsScript.State.OUT -> {
                rangeBonus = clampedEffect * MAX_JITTER_RANGE_BONUS
            }

            ShipSystemStatsScript.State.COOLDOWN,
            ShipSystemStatsScript.State.IDLE,
            -> Unit
        }
        return JitterParams(kotlin.math.sqrt(rawLevel), rangeBonus)
    }

    private fun removeTargetTimeflow(target: ShipAPI) {
        target.mutableStats.timeMult.unmodifyMult(TIMEFLOW_MOD_ID)
        for (wing in target.allWings) {
            for (fighter in wing.wingMembers) {
                if (fighter.isHulk) continue
                fighter.mutableStats.timeMult.unmodifyMult(TIMEFLOW_MOD_ID)
            }
        }
    }

    private fun findTarget(ship: ShipAPI): ShipAPI? {
        var target: ShipAPI? = null
        if (ship.shipAI != null && ship.aiFlags.hasFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)) {
            target = ship.aiFlags.getCustom(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM) as? ShipAPI
        }
        if (target == null) target = ship.shipTarget
        if (!isValidTarget(ship, target)) return null

        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, GravTimeflowTuning.BASE_RANGE)
        val dist = Misc.getDistance(ship.location, target!!.location)
        val radSum = ship.collisionRadius + target.collisionRadius
        return if (dist <= range + radSum) target else null
    }

    private fun isValidTarget(ship: ShipAPI, target: ShipAPI?): Boolean =
        target != null && target !== ship && target.owner == ship.owner &&
                !target.isFighter && !target.isDrone && !target.isHulk && target.isAlive &&
                // isStation 仅覆盖站体本体，空间站模块需另行排除
                !target.isStation && !target.isStationModule

    override fun isUsable(system: ShipSystemAPI, ship: ShipAPI): Boolean =
        findTarget(ship) != null

    override fun getInfoText(system: ShipSystemAPI, ship: ShipAPI): String? {
        if (system.isOutOfAmmo) return null
        if (system.state != ShipSystemAPI.SystemState.IDLE) return null
        if (findTarget(ship) != null) return null
        val raw = ship.shipTarget
        return if (raw != null && isValidTarget(ship, raw)) {
            I18n[I18n.Categories.MOD, "ui.grav_timeflow.info.out_of_range"]
        } else {
            I18n[I18n.Categories.MOD, "ui.grav_timeflow.info.no_target"]
        }
    }

    override fun getStatusData(
        index: Int,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ): ShipSystemStatsScript.StatusData? {
        if (index != 0) return null
        return when (state) {
            ShipSystemStatsScript.State.IN -> ShipSystemStatsScript.StatusData(
                I18n[I18n.Categories.MOD, "system.grav_timeflow.status.in"],
                false,
            )

            ShipSystemStatsScript.State.ACTIVE -> {
                val engine = Global.getCombatEngine()
                val playerShip = engine?.playerShip
                val activation = if (playerShip != null) {
                    engine.customData[activationKey(playerShip)] as? TimeflowActivation
                } else {
                    null
                }
                val targetName = activation?.target?.name
                if (targetName != null) {
                    ShipSystemStatsScript.StatusData(
                        I18n.t(I18n.Categories.MOD, "system.grav_timeflow.status.active", "targetName" to targetName),
                        false,
                    )
                } else {
                    ShipSystemStatsScript.StatusData(
                        I18n[I18n.Categories.MOD, "system.grav_timeflow.status.active_unknown"],
                        false,
                    )
                }
            }

            ShipSystemStatsScript.State.OUT -> ShipSystemStatsScript.StatusData(
                I18n[I18n.Categories.MOD, "system.grav_timeflow.status.out"],
                false,
            )

            else -> null
        }
    }

    companion object {
        private val log = Global.getLogger(GravTimeflowInterferenceSystemStats::class.java)

        private const val TIMEFLOW_MOD_ID = "astd_grav_timeflow_interference_buff"

        private const val ACTIVATION_KEY = "astd_grav_timeflow_activation:"

        // 原版 jitter 无显式清除路径，结束停止刷新后由衰减机制自然消退
        private val JITTER_KEY = Any()

        private val JITTER_COLOR = Color(165, 80, 255, 55)
        private val JITTER_UNDER_COLOR = Color(165, 80, 255, 155)

        private const val MAX_JITTER_RANGE_BONUS = 10f

        private const val TARGET_JITTER_UNDER_COPIES = 25
        private const val TARGET_JITTER_UNDER_BASE_RANGE = 7f

        private const val SELF_JITTER_MULT = 0.5f
        private const val SELF_JITTER_UNDER_COPIES = 12
        private const val SELF_JITTER_UNDER_BASE_RANGE = 3.5f

        private const val FIGHTER_JITTER_COPIES = 2

        private fun activationKey(ship: ShipAPI): String = "$ACTIVATION_KEY${ship.id}"
    }
}
