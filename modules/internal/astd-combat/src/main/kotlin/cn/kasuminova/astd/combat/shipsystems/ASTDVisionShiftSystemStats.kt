package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.effect.joint.VisionShiftTuning
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.combat.shipsystems.ASTDVisionShiftSystemStats.Companion.targetStatId
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.effect.system.ASTDAfterimageEffect
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

class ASTDVisionShiftSystemStats : BaseShipSystemScript() {

    companion object {
        const val MOD_ID = "astd_vision_shift"

        private const val MARK_KEY_PREFIX = "astd_vision_shift_mark:"
        private const val MOUSE_PICK_TOLERANCE = 100f
        private const val SELF_AFTERIMAGE_INTERVAL = 0.3f
        private const val TARGET_AFTERIMAGE_INTERVAL = 1.0f
        private const val PLAYER_TIME_MULT_OWNER_KEY = "astd_vision_shift_player_time_mult_owner"
        private const val SELF_AFTERIMAGE_KEY_PREFIX = "astd_vision_shift_self_afterimage:"
        private const val TARGET_AFTERIMAGE_KEY_PREFIX = "astd_vision_shift_target_afterimage:"
        private val AFTERIMAGE_COLOR = Color(186, 120, 255, 230)
        private val JITTER_UNDER = Color(168, 96, 255, 150)
        private val JITTER = Color(168, 96, 255, 55)

        internal fun markKey(ship: ShipAPI): String = MARK_KEY_PREFIX + System.identityHashCode(ship)

        internal fun targetStatId(source: ShipAPI): String = "$MOD_ID:${System.identityHashCode(source)}"
    }

    class VisionMark(
        val source: ShipAPI,
        val target: ShipAPI,
        val values: VisionShiftTuning.Values,
    )

    private var ownerShip: ShipAPI? = null

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val ship = stats.entity as? ShipAPI
        val engine = Global.getCombatEngine()
        if (ship == null || engine == null) {
            unapply(stats, id)
            return
        }
        ownerShip = ship
        if (!engine.isPaused) {
            renderSelfStreak(ship, id, state, engine)
        }
        if (state != ShipSystemStatsScript.State.ACTIVE) {
            clearMark(ship, engine)
            unapply(stats, id)
            return
        }

        val mark = obtainMark(ship, engine)
        if (mark == null && !engine.isPaused) {
            ship.system?.deactivate()
        }
        val isPlayer = ship.owner == 0
        val values = mark?.values ?: VisionShiftTuning.resolve(DifficultyTuningImpl, isPlayer, null)

        stats.timeMult.modifyMult(id, values.selfTimeMult)
        if (!engine.isPaused && ship === engine.playerShip) {
            engine.timeMult.modifyMult("${id}_player", 1f / values.selfTimeMult)
            engine.customData[PLAYER_TIME_MULT_OWNER_KEY] = System.identityHashCode(ship)
        }

        if (mark != null && !engine.isPaused) {
            applyToTarget(mark, engine)
        }
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        stats.timeMult.unmodifyMult(id)
        val ship = stats.entity as? ShipAPI
        val engine = Global.getCombatEngine()
        if (ship != null && engine?.customData?.get(PLAYER_TIME_MULT_OWNER_KEY) == System.identityHashCode(ship)) {
            engine.timeMult.unmodifyMult("${id}_player")
            engine.customData.remove(PLAYER_TIME_MULT_OWNER_KEY)
        }
        ship ?: return
        ship.isJitterShields = false
        engine?.customData?.remove(SELF_AFTERIMAGE_KEY_PREFIX + System.identityHashCode(ship))
    }

    override fun isUsable(system: ShipSystemAPI, ship: ShipAPI): Boolean {
        val engine = Global.getCombatEngine() ?: return false
        return pickTarget(ship, engine) != null
    }

    override fun getInfoText(system: ShipSystemAPI, ship: ShipAPI): String? {
        if (system.state != ShipSystemAPI.SystemState.IDLE) return null
        val engine = Global.getCombatEngine() ?: return null
        if (pickTarget(ship, engine) != null) return null
        val anyValidTarget = isValidTarget(ship, ship.shipTarget, engine) ||
            engine.ships.any { isValidTarget(ship, it, engine) }
        return if (anyValidTarget) {
            I18n[I18n.Categories.MOD, "ui.vision_shift.info.out_of_range"]
        } else {
            I18n[I18n.Categories.MOD, "ui.vision_shift.info.no_target"]
        }
    }

    override fun getStatusData(
        index: Int,
        state: ShipSystemStatsScript.State,
        effectLevel: Float
    ): ShipSystemStatsScript.StatusData? {
        if (state != ShipSystemStatsScript.State.ACTIVE) return null
        val ship = ownerShip ?: return null
        val engine = Global.getCombatEngine() ?: return null
        val mark = engine.customData[markKey(ship)] as? VisionMark ?: return null
        return when (index) {
            0 -> ShipSystemStatsScript.StatusData(
                I18n.t(I18n.Categories.MOD, "system.vision_shift.status.self", "percent" to formatPercent(mark.values.selfTimeMult - 1f)),
                false,
            )

            1 -> ShipSystemStatsScript.StatusData(
                I18n.t(
                    I18n.Categories.MOD, "system.vision_shift.status.target",
                    "name" to (mark.target.name ?: mark.target.hullSpec.hullName),
                    "percent" to formatPercent(1f - mark.values.targetTimeMult),
                ),
                false,
            )

            else -> null
        }
    }

    private fun obtainMark(ship: ShipAPI, engine: CombatEngineAPI): VisionMark? {
        val key = markKey(ship)
        val existing = engine.customData[key] as? VisionMark
        if (existing != null) {
            if (isValidTarget(ship, existing.target, engine)) return existing
            releaseTarget(existing)
            engine.customData.remove(key)
            return null
        }
        val target = pickTarget(ship, engine) ?: return null
        val values = VisionShiftTuning.resolve(DifficultyTuningImpl, isPlayer = ship.owner == 0, target.hullSize)
        val mark = VisionMark(ship, target, values)
        engine.customData[key] = mark
        target.addListener(VisionShiftDamageListener(target, key, targetStatId(ship)))
        return mark
    }

    private fun clearMark(ship: ShipAPI, engine: CombatEngineAPI) {
        val key = markKey(ship)
        val mark = engine.customData[key] as? VisionMark ?: return
        releaseTarget(mark)
        engine.customData.remove(key)
    }

    private fun releaseTarget(mark: VisionMark) {
        mark.target.mutableStats.timeMult.unmodifyMult(targetStatId(mark.source))
        Global.getCombatEngine()?.customData?.remove(TARGET_AFTERIMAGE_KEY_PREFIX + System.identityHashCode(mark.target))
    }

    private fun applyToTarget(mark: VisionMark, engine: CombatEngineAPI) {
        val target = mark.target
        val statId = targetStatId(mark.source)
        val stat = target.mutableStats.timeMult
        stat.unmodifyMult(statId)
        stat.modifyMult(statId, mark.values.targetTimeMult)

        target.isJitterShields = false
        target.setJitterUnder(statId, JITTER_UNDER, 0.7f, 18, 0f, 15f)
        target.setJitter(statId, JITTER, 0.6f, 6, 0f, 0f)

        val timerKey = TARGET_AFTERIMAGE_KEY_PREFIX + System.identityHashCode(target)
        val elapsed = (engine.customData[timerKey] as? Float ?: TARGET_AFTERIMAGE_INTERVAL) + engine.elapsedInLastFrame
        if (elapsed >= TARGET_AFTERIMAGE_INTERVAL) {
            engine.customData[timerKey] = elapsed - TARGET_AFTERIMAGE_INTERVAL
            spawnAfterimage(engine, target)
        } else {
            engine.customData[timerKey] = elapsed
        }
    }

    private fun pickTarget(ship: ShipAPI, engine: CombatEngineAPI): ShipAPI? {
        val locked = ship.shipTarget
        if (isValidTarget(ship, locked, engine) && withinSystemRange(ship, locked!!)) return locked
        if (ship === engine.playerShip) {
            // mouseTarget 是鼠标位置（Vector2f）而非目标舰，取鼠标附近有效敌舰作为玩家意图目标
            val mouse = ship.mouseTarget
            if (mouse != null) {
                val nearMouse = engine.ships
                    .asSequence()
                    .filter { isValidTarget(ship, it, engine) }
                    .filter { withinSystemRange(ship, it) }
                    .filter { MathUtils.getDistance(mouse, it.location) <= it.collisionRadius + MOUSE_PICK_TOLERANCE }
                    .minByOrNull { MathUtils.getDistance(mouse, it.location) }
                if (nearMouse != null) return nearMouse
            }
        }
        return engine.ships
            .asSequence()
            .filter { isValidTarget(ship, it, engine) }
            .filter { withinSystemRange(ship, it) }
            .minByOrNull { MathUtils.getDistance(ship.location, it.location) }
    }

    private fun withinSystemRange(ship: ShipAPI, target: ShipAPI): Boolean {
        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, VisionShiftTuning.SYSTEM_RANGE)
        val dist = Misc.getDistance(ship.location, target.location)
        return dist <= range + ship.collisionRadius + target.collisionRadius
    }

    private fun isValidTarget(ship: ShipAPI, target: ShipAPI?, engine: CombatEngineAPI): Boolean {
        if (target == null || target === ship) return false
        if (target.owner == ship.owner || !target.isAlive || target.isHulk) return false
        if (target.isFighter || target.isDrone) return false
        return engine.isEntityInPlay(target)
    }

    private fun renderSelfStreak(ship: ShipAPI, id: String, state: ShipSystemStatsScript.State, engine: CombatEngineAPI) {
        val level = when (state) {
            ShipSystemStatsScript.State.IN -> 0.5f
            ShipSystemStatsScript.State.ACTIVE -> 1f
            ShipSystemStatsScript.State.OUT -> 0.45f
            else -> 0f
        }
        if (level > 0f) {
            ship.isJitterShields = false
            ship.setJitterUnder(id, JITTER_UNDER, level, 25, 0f, 15f)
            ship.setJitter(id, JITTER, 0.6f * level, 6, 0f, 0f)
        }
        if (state != ShipSystemStatsScript.State.ACTIVE) return

        val timerKey = SELF_AFTERIMAGE_KEY_PREFIX + System.identityHashCode(ship)
        val elapsed = (engine.customData[timerKey] as? Float ?: 0f) + engine.elapsedInLastFrame
        if (elapsed < SELF_AFTERIMAGE_INTERVAL) {
            engine.customData[timerKey] = elapsed
            return
        }
        engine.customData[timerKey] = elapsed - SELF_AFTERIMAGE_INTERVAL
        spawnAfterimage(engine, ship)
    }

    private fun spawnAfterimage(engine: CombatEngineAPI, ship: ShipAPI) {
        ASTDAfterimageEffect.spawn(
            engine,
            ASTDAfterimageEffect.Snapshot(
                spritePath = ship.hullSpec.spriteName,
                location = Vector2f(ship.location),
                facing = ship.facing,
                width = ship.spriteAPI.width,
                height = ship.spriteAPI.height,
                color = AFTERIMAGE_COLOR,
                startAlpha = 0.9f,
                duration = 1f,
                growth = 0.05f,
            ),
        )
    }

    private fun formatPercent(value: Float): String {
        val pct = value * 100f
        val rounded = kotlin.math.round(pct * 10f) / 10f
        return if (rounded == kotlin.math.floor(rounded)) rounded.toInt().toString() else rounded.toString()
    }

    class VisionShiftDamageListener(
        private val host: ShipAPI,
        private val markKey: String,
        private val statId: String,
    ) : DamageTakenModifier, AdvanceableListener {

        override fun advance(amount: Float) {
            val engine = Global.getCombatEngine()
            val mark = engine?.customData?.get(markKey) as? VisionMark
            if (mark == null || mark.target !== host || !host.isAlive || host.isHulk ||
                !mark.source.isAlive || mark.source.isHulk
            ) {
                host.mutableStats.timeMult.unmodifyMult(statId)
                host.removeListener(this)
            }
        }

        override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            if (target !== host || damage == null) return null
            val engine = Global.getCombatEngine() ?: return null
            val mark = engine.customData[markKey] as? VisionMark ?: return null
            if (mark.target !== host) return null
            val attacker = when (param) {
                is ShipAPI -> param
                is DamagingProjectileAPI -> param.source
                is BeamAPI -> param.source
                else -> null
            }
            if (attacker !== mark.source) return null
            val mult = mark.values.damageFromSelfMult
            if (mult != 1f) {
                damage.modifier.modifyMult(statId, mult)
            }
            return null
        }
    }
}
