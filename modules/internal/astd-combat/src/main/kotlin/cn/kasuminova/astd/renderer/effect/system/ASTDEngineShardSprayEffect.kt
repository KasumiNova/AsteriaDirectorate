package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.combat.hullmods.base.isASTDShip
import cn.kasuminova.astd.impl.render.TriShardComponent
import cn.kasuminova.astd.impl.render.TriShardSpec
import cn.kasuminova.astd.renderer.effect.system.ASTDEngineShardSprayEffect.FLUSH_INTERVAL
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

internal object ASTDEngineShardSprayEffect {

    private const val PLUGIN_KEY = "astd_engine_shard_spray_effect"
    private const val SCAN_INTERVAL = 0.5f

    private const val FLUSH_INTERVAL = 0.1f

    private const val RATE_MIN = 1f
    private const val RATE_MAX = 12f

    private const val SPEED_MIN = 15f
    private const val SPEED_MAX = 180f

    private const val LEVEL_EPSILON = 0.05f

    private const val SPREAD_DEG = 10f

    private const val SHIP_VEL_INHERIT = 1f

    private const val CORE_BRIGHTEN = 1.4f

    private val log = Global.getLogger(ASTDEngineShardSprayEffect::class.java)

    fun ensureInstalled(engine: CombatEngineAPI) {
        if (engine.customData[PLUGIN_KEY] != null) return
        try {
            val plugin = Plugin(engine)
            engine.addPlugin(plugin)
            engine.customData[PLUGIN_KEY] = plugin
        } catch (t: Throwable) {
            engine.customData[PLUGIN_KEY] = false
            log.warn("[ASTD] ASTDEngineShardSprayEffect install failed", t)
        }
    }

    private class EngineSpray(
        val engine: ShipEngineAPI,
        val angleDeg: Float,
        val shards: TriShardComponent,
        var emitAcc: Float = 0f,
    )

    private class Attachment(
        val ship: ShipAPI,
        val sprays: List<EngineSpray>,
        var flushAcc: Float = 0f,
    )

    private class Plugin(private val combatEngine: CombatEngineAPI) : BaseEveryFrameCombatPlugin() {
        private val attachments = LinkedHashMap<Int, Attachment>()
        private var scanAcc = 0f

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (combatEngine.isPaused) return

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
                } catch (t: Throwable) {
                    log.warn("[ASTD] engine shard spray ship state check failed", t)
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
            } catch (t: Throwable) {
                log.warn("[ASTD] engine shard spray scan failed", t)
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
            } catch (t: Throwable) {
                log.warn("[ASTD] engine shard spray read engines failed hull=${ship.hullSpec?.hullId}", t)
                null
            } ?: return null
            if (engines.isEmpty()) return null

            val sprays = ArrayList<EngineSpray>(engines.size)
            for ((index, engine) in engines.withIndex()) {
                if (engine == null || engine.isPermanentlyDisabled) continue
                val slot = try {
                    engine.engineSlot
                } catch (t: Throwable) {
                    log.warn("[ASTD] engine shard spray read slot failed hull=${ship.hullSpec?.hullId}", t)
                    null
                } ?: continue

                val engineColor = try {
                    engine.engineColor ?: slot.color
                } catch (t: Throwable) {
                    log.warn("[ASTD] engine shard spray read color failed hull=${ship.hullSpec?.hullId}", t)
                    slot.color
                }
                val core = brighten(engineColor)

                val spec = TriShardSpec(
                    batchCount = 1,
                    layer = CombatEngineLayers.BELOW_SHIPS_LAYER,
                    sizeMul = 0.16f,
                    sizeMin = 4f,
                    sizeMax = 8f,
                    spinMin = 90f,
                    spinMax = 360f,
                    alphaLo = 110,
                    alphaHi = 170,
                    timerFullLo = 0.10f,
                    timerFullHi = 0.22f,
                    timerFadeOut = 0.28f,
                )
                sprays += EngineSpray(
                    engine = engine,
                    angleDeg = slot.angle,
                    shards = TriShardComponent(
                        "astd_engine_shards/${ship.id}/$index",
                        slot.width,
                        core,
                        engineColor,
                        spec,
                    ),
                )
            }
            if (sprays.isEmpty()) return null
            return Attachment(ship, sprays)
        }

        private fun updateAttachment(att: Attachment, amount: Float) {
            val ship = att.ship

            @Suppress("UNCHECKED_CAST")
            val levels = ship.customData[ASTDVectorThrustEngineManager.ENGINE_LEVELS_KEY] as? Map<ShipEngineAPI, Float>
            if (levels == null) return

            val shipVel = try {
                ship.velocity
            } catch (t: Throwable) {
                log.warn("[ASTD] engine shard spray read velocity failed", t)
                null
            }
            val shipFacing = ship.facing

            for (spray in att.sprays) {
                val level = levels[spray.engine] ?: 0f
                if (level <= LEVEL_EPSILON) {
                    spray.emitAcc = 0f
                    continue
                }

                val rate = RATE_MIN + (RATE_MAX - RATE_MIN) * level
                spray.emitAcc += rate * amount
                if (spray.emitAcc < 1f) continue

                val origin = try {
                    spray.engine.location
                } catch (t: Throwable) {
                    log.warn("[ASTD] engine shard spray read engine location failed", t)
                    spray.emitAcc = 0f
                    continue
                }
                val baseAngle = shipFacing + spray.angleDeg

                while (spray.emitAcc >= 1f) {
                    spray.emitAcc -= 1f
                    val speed = (SPEED_MIN + (SPEED_MAX - SPEED_MIN) * level) *
                            MathUtils.getRandomNumberInRange(0.8f, 1.2f)
                    val sizeScale = (0.6f + 0.5f * level) * MathUtils.getRandomNumberInRange(0.85f, 1.15f)
                    val angle = Math.toRadians((baseAngle + MathUtils.getRandomNumberInRange(-SPREAD_DEG, SPREAD_DEG)).toDouble())
                    var vx = cos(angle).toFloat() * speed
                    var vy = sin(angle).toFloat() * speed
                    if (shipVel != null) {
                        vx += shipVel.x * SHIP_VEL_INHERIT
                        vy += shipVel.y * SHIP_VEL_INHERIT
                    }
                    spray.shards.addShard(0, origin, Vector2f(vx, vy), sizeScale)
                }
            }

            att.flushAcc += amount
            if (att.flushAcc >= FLUSH_INTERVAL) {
                att.flushAcc = 0f
                for (spray in att.sprays) {
                    spray.shards.activatePendingBatches(combatEngine)
                }
            }
        }

        private fun brighten(color: Color): Color = Color(
            (color.red * CORE_BRIGHTEN).toInt().coerceAtMost(255),
            (color.green * CORE_BRIGHTEN).toInt().coerceAtMost(255),
            (color.blue * CORE_BRIGHTEN).toInt().coerceAtMost(255),
        )
    }
}
