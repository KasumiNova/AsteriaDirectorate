package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.combat.hullmods.base.isASTDShip
import cn.kasuminova.astd.renderer.effect.system.ASTDVectorThrustEngineManager.ENGINE_LEVELS_KEY
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
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
                val gone = try {
                    att.ship.isHulk || att.ship.hitpoints <= 0f || !combatEngine.isEntityInPlay(att.ship)
                } catch (_: Throwable) {
                    true
                }
                if (gone) {
                    it.remove()
                    continue
                }
                updateAttachment(att, amount)
            }
        }

        private fun scanShips() {
            val ships = try {
                combatEngine.ships
            } catch (_: Throwable) {
                null
            } ?: return

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
            val engines = try {
                ship.engineController?.shipEngines
            } catch (_: Throwable) {
                null
            } ?: return null
            if (engines.isEmpty()) return null

            val shipFacingRad = Math.toRadians(ship.facing.toDouble())
            val cosF = cos(shipFacingRad).toFloat()
            val sinF = sin(shipFacingRad).toFloat()
            val shipLoc = ship.location

            val states = ArrayList<EngineState>(engines.size)
            for (engine in engines) {
                if (engine == null) continue
                if (engine.isPermanentlyDisabled) continue
                val slot = try {
                    engine.engineSlot
                } catch (_: Throwable) {
                    null
                } ?: continue
                val angle = try {
                    slot.angle
                } catch (_: Throwable) {
                    180f
                }
                val thrustAngle = Math.toRadians((angle + 180f).toDouble())
                val fx = cos(thrustAngle).toFloat()
                val fy = sin(thrustAngle).toFloat()

                var rx = 0f
                var ry = 0f
                try {
                    val eLoc = engine.location
                    val dx = eLoc.x - shipLoc.x
                    val dy = eLoc.y - shipLoc.y
                    rx = dx * cosF + dy * sinF
                    ry = -dx * sinF + dy * cosF
                } catch (_: Throwable) {
                }
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
            try {
                log.info("[ASTD] vector-thrust attached ship=${ship.hullSpec?.hullId} engines=${states.size}")
            } catch (_: Throwable) {
            }
            return Attachment(ship, states)
        }

        private fun updateAttachment(att: Attachment, amount: Float) {
            val ship = att.ship
            val controller = try {
                ship.engineController
            } catch (_: Throwable) {
                null
            } ?: return

            var intentX = 0f
            var intentY = 0f
            var braking = false
            try {
                if (controller.isAccelerating) intentX += 1f
                if (controller.isDecelerating || controller.isAcceleratingBackwards) braking = true
                if (controller.isStrafingLeft) intentY += 1f
                if (controller.isStrafingRight) intentY -= 1f
            } catch (_: Throwable) {
            }

            var turnIntent = 0f
            try {
                if (controller.isTurningLeft) turnIntent += 1f
                if (controller.isTurningRight) turnIntent -= 1f
            } catch (_: Throwable) {
            }

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

            for (st in att.engines) {
                val engine = st.engine
                val usable = try {
                    !engine.isDisabled && !engine.isPermanentlyDisabled
                } catch (_: Throwable) {
                    false
                }

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

                st.level = moveToward(st.level, target, if (target > st.level) ATTACK_PER_SEC else RELEASE_PER_SEC, amount)

                val slot = try {
                    engine.engineSlot
                } catch (_: Throwable) {
                    null
                } ?: continue
                try {
                    controller.setFlameLevel(slot, st.level.coerceIn(0f, 1f))
                } catch (_: Throwable) {
                }
                levels[st.engine] = st.level
            }
        }
    }
}
