package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.combat.hullmods.base.isASTDShip
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI
import com.fs.starfarer.api.input.InputEventAPI
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal object ASTDVectorThrustEngineManager {

    private const val ENGINE_KEY = "astd_vector_thrust_engine_manager"

    const val ENGINE_LEVELS_KEY = "astd_vector_thrust_engine_levels"
    private const val SCAN_INTERVAL = 0.5f

    private const val IDLE_FLAME = 0.45f

    private const val FULL_FLAME = 1.0f

    private const val LOW_FLAME = 0.18f

    private const val BRAKE_FLAME = 0.4f

    private const val ATTACK_PER_SEC = 0.75f
    private const val RELEASE_PER_SEC = 1f

    private val log = Global.getLogger(ASTDVectorThrustEngineManager::class.java)

    private fun moveToward(current: Float, target: Float, ratePerSec: Float, amount: Float): Float {
        val diff = target - current
        val step = ratePerSec * amount
        return if (abs(diff) <= step) target else current + step * Math.signum(diff)
    }

    fun ensureInstalled(engine: CombatEngineAPI) {
        if (engine.customData[ENGINE_KEY] != null) return
        try {
            val plugin = Plugin(engine)
            engine.addPlugin(plugin)
            engine.customData[ENGINE_KEY] = plugin
        } catch (t: Throwable) {
            engine.customData[ENGINE_KEY] = false
            log.warn("[ASTD] ASTDVectorThrustEngineManager install failed", t)
        }
    }

    fun setSystemFullFlame(engine: CombatEngineAPI, ship: ShipAPI, enabled: Boolean): Float {
        if (enabled) ensureInstalled(engine)
        val plugin = engine.customData[ENGINE_KEY] as? Plugin ?: return 0f
        return plugin.setSystemFullFlame(ship, enabled)
    }

    private class EngineState(
        val engine: ShipEngineAPI,
        val thrustAxisX: Float,
        val thrustAxisY: Float,
        val turnMoment: Float,
        val isLateral: Boolean,
        var level: Float,
    )

    private class Attachment(
        val ship: ShipAPI,
        val engines: List<EngineState>,
        var systemFullFlame: Boolean = false,
        var systemFlameElapsed: Float = 0f,
        var systemStartLevels: FloatArray = FloatArray(0),
    )

    private class Plugin(private val combatEngine: CombatEngineAPI) : BaseEveryFrameCombatPlugin() {
        private val attachments = LinkedHashMap<Int, Attachment>()
        private var scanAcc = 0f
        private var installLogged = false

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (combatEngine.isPaused) return

            if (!installLogged) {
                installLogged = true
                log.info("[ASTD] ASTDVectorThrustEngineManager active")
            }

            scanAcc += amount
            if (scanAcc >= SCAN_INTERVAL) {
                scanAcc = 0f
                scanShips()
            }

            val it = attachments.entries.iterator()
            while (it.hasNext()) {
                val (_, att) = it.next()
                val gone = att.ship.isHulk || att.ship.hitpoints <= 0f || !combatEngine.isEntityInPlay(att.ship)
                if (gone) {
                    att.ship.removeCustomData(ENGINE_LEVELS_KEY)
                    it.remove()
                    continue
                }
                updateAttachment(att, amount)
            }
        }

        fun setSystemFullFlame(ship: ShipAPI, enabled: Boolean): Float {
            val key = System.identityHashCode(ship)
            val att = attachments[key] ?: if (enabled && !ship.isHulk) {
                createAttachment(ship)?.also { attachments[key] = it }
            } else null
            att ?: return 0f
            if (enabled && !att.systemFullFlame) {
                att.systemFlameElapsed = 0f
                att.systemStartLevels = FloatArray(att.engines.size) { att.engines[it].level }
            }
            att.systemFullFlame = enabled
            return flameBlend(att)
        }

        private fun scanShips() {
            val ships = combatEngine.ships
            for (ship in ships) {
                if (ship.isFighter || ship.isHulk) continue
                if (!ship.isASTDShip()) continue
                val key = System.identityHashCode(ship)
                if (attachments.containsKey(key)) continue
                val att = createAttachment(ship) ?: continue
                attachments[key] = att
            }
        }

        private fun createAttachment(ship: ShipAPI): Attachment? {
            if (ship.isFighter || !ship.isASTDShip()) return null
            val engines = ship.engineController?.shipEngines ?: return null
            if (engines.isEmpty()) return null

            val shipFacingRad = Math.toRadians(ship.facing.toDouble())
            val cosF = cos(shipFacingRad).toFloat()
            val sinF = sin(shipFacingRad).toFloat()
            val shipLoc = ship.location

            val states = ArrayList<EngineState>(engines.size)
            for (engine in engines) {
                if (engine == null) continue
                if (engine.isPermanentlyDisabled) continue
                val slot = engine.engineSlot
                val angle = slot.angle
                val thrustAngle = Math.toRadians((angle + 180f).toDouble())
                val fx = cos(thrustAngle).toFloat()
                val fy = sin(thrustAngle).toFloat()

                val eLoc = engine.location
                val dx = eLoc.x - shipLoc.x
                val dy = eLoc.y - shipLoc.y
                val rx = dx * cosF + dy * sinF
                val ry = -dx * sinF + dy * cosF
                val turnMoment = rx * fy - ry * fx

                states += EngineState(
                    engine = engine,
                    thrustAxisX = fx,
                    thrustAxisY = fy,
                    turnMoment = turnMoment,
                    isLateral = abs(fy) > abs(fx),
                    level = IDLE_FLAME,
                )
            }
            if (states.isEmpty()) return null
            log.info("[ASTD] vector-thrust attached ship=${ship.hullSpec?.hullId} engines=${states.size}")
            return Attachment(ship, states)
        }

        private fun flameBlend(att: Attachment): Float {
            val ramp = (att.systemFlameElapsed / 0.35f).coerceIn(0f, 1f)
            return ramp * ramp * (3f - 2f * ramp)
        }

        private fun updateAttachment(att: Attachment, amount: Float) {
            val ship = att.ship
            val controller = ship.engineController ?: return
            val fullFlame = att.systemFullFlame && ship.system?.state == ShipSystemAPI.SystemState.ACTIVE
            if (!fullFlame) att.systemFullFlame = false
            if (fullFlame) att.systemFlameElapsed += amount
            val blend = flameBlend(att)

            var intentX = 0f
            var intentY = 0f
            if (controller.isAccelerating) intentX += 1f
            val braking = controller.isDecelerating || controller.isAcceleratingBackwards
            if (controller.isStrafingLeft) intentY += 1f
            if (controller.isStrafingRight) intentY -= 1f

            var turnIntent = 0f
            if (controller.isTurningLeft) turnIntent += 1f
            if (controller.isTurningRight) turnIntent -= 1f

            val hasTranslation = abs(intentX) > 0.01f || abs(intentY) > 0.01f
            val hasTurn = abs(turnIntent) > 0.01f
            val hasIntent = hasTranslation || hasTurn
            val intentLen = max(1e-3f, kotlin.math.sqrt(intentX * intentX + intentY * intentY))
            val nIntentX = if (hasTranslation) intentX / intentLen else 0f
            val nIntentY = if (hasTranslation) intentY / intentLen else 0f

            var maxMoment = 1e-3f
            for (st in att.engines) {
                val m = abs(st.turnMoment)
                if (m > maxMoment) maxMoment = m
            }

            val pureBraking = braking && !hasTranslation && !hasTurn

            @Suppress("UNCHECKED_CAST")
            var levels = ship.customData[ENGINE_LEVELS_KEY] as? IdentityHashMap<ShipEngineAPI, Float>
            if (levels == null) {
                levels = IdentityHashMap()
                ship.setCustomData(ENGINE_LEVELS_KEY, levels)
            }

            for ((index, st) in att.engines.withIndex()) {
                val engine = st.engine
                val usable = !controller.isFlamedOut && !controller.isFlamingOut &&
                    !controller.isDisabled && !engine.isDisabled && !engine.isPermanentlyDisabled

                // 熄火引擎禁止调用 setFlameLevel，否则会干扰原版的熄火修复计时。
                if (!usable) {
                    st.level = IDLE_FLAME
                    levels[st.engine] = 0f
                    continue
                }

                val target: Float = if (pureBraking) {
                    BRAKE_FLAME
                } else if (!hasIntent) {
                    IDLE_FLAME
                } else {
                    val align = st.thrustAxisX * nIntentX + st.thrustAxisY * nIntentY
                    val turnAlign = if (!hasTurn) {
                        -1f
                    } else if (st.isLateral) {
                        val s = st.turnMoment * turnIntent
                        if (s > 0f) 0.85f else -1f
                    } else {
                        ((st.turnMoment / maxMoment) * turnIntent).coerceIn(-1f, 1f)
                    }
                    val combined = max(align, turnAlign)
                    val norm = ((combined + 1f) * 0.5f).coerceIn(0f, 1f)
                    LOW_FLAME + (FULL_FLAME - LOW_FLAME) * norm
                }

                st.level = if (fullFlame) {
                    att.systemStartLevels[index] + (FULL_FLAME - att.systemStartLevels[index]) * blend
                } else {
                    moveToward(st.level, target, if (target > st.level) ATTACK_PER_SEC else RELEASE_PER_SEC, amount)
                }

                controller.setFlameLevel(engine.engineSlot, st.level.coerceIn(0f, 1f))
                levels[st.engine] = st.level
            }
        }
    }
}
