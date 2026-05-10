package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.boxutil.base.api.InstanceDataAPI
import org.boxutil.base.api.InstanceRenderAPI
import org.boxutil.define.BoxEnum
import org.boxutil.define.InstanceType
import org.boxutil.units.standard.attribute.Instance2Data
import org.boxutil.units.standard.entity.FlareEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Arc Flare（弧光耀斑）引擎动态 flare。
 *
 * 目标：
 * - 保留原版 HIGH_TECH 尾焰主体
 * - 额外叠一层轻量 BoxUtil flare，让主喷口在机动/提速/系统激活时更有“白蓝电离”质感
 */
internal object ArcFlareEngineFlareManager {

    private const val ENGINE_KEY = "astd_arc_flare_engine_flare_manager"
    private const val HULL_ID = "astd_arc_flare"
    private const val SCAN_INTERVAL = 0.33f

    private val log = Global.getLogger(ArcFlareEngineFlareManager::class.java)

    fun ensureInstalled(engine: CombatEngineAPI) {
        val existing = engine.customData[ENGINE_KEY]
        if (existing != null) return

        try {
            val plugin = Plugin(engine)
            engine.addPlugin(plugin)
            engine.customData[ENGINE_KEY] = plugin
        } catch (t: Throwable) {
            engine.customData[ENGINE_KEY] = false
            log.warn("[ASTD] ArcFlareEngineFlareManager install failed", t)
        }
    }

    private data class EngineFlare(
        val engine: ShipEngineAPI,
        val entity: FlareEntity,
        val baseSize: Float,
        val phase: Float,
    )

    private data class Attachment(
        val ship: ShipAPI,
        val flares: List<EngineFlare>,
    )

    private class Plugin(private val combatEngine: CombatEngineAPI) : BaseEveryFrameCombatPlugin() {
        private val attachments = LinkedHashMap<Int, Attachment>()
        private var scanAcc = 0f
        private var installLogged = false

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (combatEngine.isPaused) return

            if (!installLogged) {
                installLogged = true
                log.info("[ASTD] ArcFlareEngineFlareManager active")
            }

            try {
                BoxUtilCombatVfx.ensureReady(combatEngine)
            } catch (_: Throwable) {
            }

            scanAcc += amount
            if (scanAcc >= SCAN_INTERVAL) {
                scanAcc = 0f
                scanShips()
            }

            val now = try {
                combatEngine.getTotalElapsedTime(false)
            } catch (_: Throwable) {
                0f
            }

            val it = attachments.entries.iterator()
            while (it.hasNext()) {
                val (_, att) = it.next()
                val ship = att.ship
                if (ship.isHulk || ship.hitpoints <= 0f) {
                    deleteAttachment(att)
                    it.remove()
                    continue
                }

                updateAttachment(att, now)
            }
        }

        private fun scanShips() {
            val ships = try {
                combatEngine.ships
            } catch (_: Throwable) {
                null
            } ?: return

            for (ship in ships) {
                if (ship.isFighter) continue
                if (ship.isHulk) continue
                val hullId = try {
                    ship.hullSpec?.hullId
                } catch (_: Throwable) {
                    null
                }
                if (hullId != HULL_ID) continue

                val key = System.identityHashCode(ship)
                if (attachments.containsKey(key)) continue

                val attachment = createAttachment(ship) ?: continue
                attachments[key] = attachment
            }
        }

        private fun createAttachment(ship: ShipAPI): Attachment? {
            val shipEngines = try {
                ship.engineController?.shipEngines
            } catch (_: Throwable) {
                null
            } ?: return null

            if (shipEngines.isEmpty()) return null

            val flares = ArrayList<EngineFlare>(shipEngines.size)
            shipEngines.forEachIndexed { index, shipEngine ->
                if (shipEngine == null) return@forEachIndexed
                if (shipEngine.isPermanentlyDisabled) return@forEachIndexed

                val slot = try {
                    shipEngine.engineSlot
                } catch (_: Throwable) {
                    null
                } ?: return@forEachIndexed

                val width = try {
                    slot.width
                } catch (_: Throwable) {
                    15f
                }
                val length = try {
                    slot.length
                } catch (_: Throwable) {
                    40f
                }

                val entity = createFlareEntity() ?: return@forEachIndexed
                val baseSize = max(width * 1.65f, length * 0.82f) + 10f
                flares += EngineFlare(
                    engine = shipEngine,
                    entity = entity,
                    baseSize = baseSize,
                    phase = index * 0.57f,
                )
            }

            if (flares.isEmpty()) return null
            try {
                log.info("[ASTD] ArcFlareEngineFlareManager attached ship=${ship.hullSpec?.hullId} engines=${flares.size}")
            } catch (_: Throwable) {
            }
            return Attachment(ship = ship, flares = flares)
        }

        private fun createFlareEntity(): FlareEntity? {
            val entity = try {
                FlareEntity()
            } catch (_: Throwable) {
                return null
            }

            try {
                entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
                entity.setAdditiveBlend()
                entity.setSmooth()
                entity.setFlick(false)
                entity.setSyncFlick(false)
                entity.setGlowPower(1.06f)
                entity.setCoreColor(Color(255, 248, 240, 18))
                entity.setFringeColor(Color(145, 236, 255, 70))
                entity.setGlobalAlpha(0.14f)
                entity.setNoisePower(0.10f)
                entity.setFlickMixValue(0.68f)
            } catch (_: Throwable) {
            }

            if (!initFixedOneInstance(entity)) {
                try {
                    entity.delete()
                } catch (_: Throwable) {
                }
                return null
            }

            val state = try {
                BoxUtilCombatVfx.addEntity(combatEngine, BoxEnum.ENTITY_FLARE, entity)
            } catch (_: Throwable) {
                -1
            }
            if (state != 0) {
                try {
                    entity.delete()
                } catch (_: Throwable) {
                }
                return null
            }

            return entity
        }

        private fun initFixedOneInstance(entity: InstanceRenderAPI): Boolean {
            val inst = Instance2Data().apply {
                setLocation(0f, 0f)
                setFacing(0f)
                setTurnRate(0f)
                setScale(1f, 1f)
                setTimer(0f, 99999f, 0f)
                setColor(255, 255, 255, 255)
                setEmissiveColor(255, 255, 255, 255)
                try {
                    setFixedInstanceAlpha(1f, BoxEnum.TIMER_FULL)
                } catch (_: Throwable) {
                }
            }

            val apiList: MutableList<InstanceDataAPI> = mutableListOf(inst)
            val stSet = try {
                entity.setInstanceData(apiList, 0f, 99999f, 0f)
            } catch (_: Throwable) {
                BoxEnum.STATE_FAILED_OTHER
            }
            if (stSet != BoxEnum.STATE_SUCCESS) return false

            try {
                entity.setRenderingCount(1)
                entity.setInstanceDataRefreshIndex(0)
                entity.setInstanceDataRefreshSize(1)
                entity.setInstanceTimerOverride(1f, BoxEnum.TIMER_FULL)
            } catch (_: Throwable) {
            }

            return submitFixedInstanceDataCompat(entity, apiList.size) == BoxEnum.STATE_SUCCESS
                && entity.haveValidInstanceData()
                && entity.getValidInstanceDataCount() >= 1
        }

        private fun submitFixedInstanceDataCompat(entity: InstanceRenderAPI, instanceCount: Int): Byte {
            if (instanceCount < 1) return BoxEnum.STATE_FAILED_OTHER
            return try {
                val memory = entity.instanceDataMemory
                val needAlloc = memory == null || !memory.is_type_fixed()
                if (needAlloc) {
                    entity.mallocInstance(InstanceType.FIXED_2D, instanceCount)
                    entity.setInstanceDataRefreshIndex(0)
                    entity.setInstanceDataRefreshOffset(0)
                    entity.setInstanceDataRefreshAllFromCurrentIndex()
                }

                val after = entity.instanceDataMemory
                if (after == null || !after.is_type_fixed()) return BoxEnum.STATE_FAILED_OTHER

                entity.submitInstance()
                BoxEnum.STATE_SUCCESS
            } catch (_: Throwable) {
                BoxEnum.STATE_FAILED_OTHER
            }
        }

        private fun updateAttachment(att: Attachment, now: Float) {
            val ship = att.ship
            val systemLevel = try {
                ship.system?.effectLevel ?: 0f
            } catch (_: Throwable) {
                0f
            }.coerceIn(0f, 1f)
            val visualLevel = ArcFlareOverdriveVisualState.getLevel(ship, combatEngine).coerceAtLeast(systemLevel)
            val fluxLevel = try {
                ship.fluxTracker?.fluxLevel ?: 0f
            } catch (_: Throwable) {
                0f
            }.coerceIn(0f, 1f)
            val engineController = try {
                ship.engineController
            } catch (_: Throwable) {
                null
            }
            val speedLevel = try {
                val maxSpeed = max(1f, ship.maxSpeed * 0.9f)
                ship.velocity.length() / maxSpeed
            } catch (_: Throwable) {
                0f
            }.coerceIn(0f, 1.2f)
            val inputLevel = try {
                var level = 0f
                if (engineController?.isAccelerating == true) level = max(level, 1f)
                if (engineController?.isAcceleratingBackwards == true || engineController?.isDecelerating == true) level = max(level, 0.70f)
                if (engineController?.isStrafingLeft == true || engineController?.isStrafingRight == true) level = max(level, 0.62f)
                if (engineController?.isTurningLeft == true || engineController?.isTurningRight == true) level = max(level, 0.52f)
                level
            } catch (_: Throwable) {
                0f
            }
            val turnLevel = try {
                abs(ship.angularVelocity) / max(1f, ship.maxTurnRate)
            } catch (_: Throwable) {
                0f
            }.coerceIn(0f, 1.2f)
            val movementLevel = max(max(speedLevel * 0.55f, turnLevel), inputLevel)
            val overloadPenalty = try {
                if (ship.fluxTracker?.isOverloaded == true) 0.35f else if (ship.fluxTracker?.isVenting == true) 0.65f else 1f
            } catch (_: Throwable) {
                1f
            }
            val controllerBoost = (movementLevel * 0.34f + visualLevel * 0.60f + fluxLevel * 0.08f).coerceIn(0f, 1.0f) * overloadPenalty
            val primaryTint = ArcFlareOverdriveVisualState.lerpColor(ArcFlareOverdriveVisualState.coldFringe, ArcFlareOverdriveVisualState.hotFringe, visualLevel, 168)
            val secondaryTint = ArcFlareOverdriveVisualState.lerpColor(ArcFlareOverdriveVisualState.coldCore, ArcFlareOverdriveVisualState.hotCore, visualLevel, 62)

            try {
                engineController?.fadeToOtherColor(
                    ENGINE_KEY,
                    primaryTint,
                    secondaryTint,
                    controllerBoost.coerceIn(0f, 1f),
                    0.65f,
                )
                engineController?.extendFlame(
                    ENGINE_KEY,
                    0.08f + controllerBoost * 0.60f,
                    0.04f + controllerBoost * 0.20f,
                    0.05f + controllerBoost * 0.30f,
                )
            } catch (_: Throwable) {
            }

            for ((index, flare) in att.flares.withIndex()) {
                val shipEngine = flare.engine
                val active = try {
                    !shipEngine.isDisabled && !shipEngine.isPermanentlyDisabled
                } catch (_: Throwable) {
                    true
                }
                if (!active) {
                    updateFlare(flare.entity, ship.location, flare.baseSize * 0.55f, 0f, 0.10f, visualLevel)
                    continue
                }

                val systemBoost = try {
                    if (shipEngine.isSystemActivated) 0.12f else 0f
                } catch (_: Throwable) {
                    0f
                }
                val contributionBoost = try {
                    shipEngine.contribution.coerceIn(0.4f, 1f)
                } catch (_: Throwable) {
                    1f
                }
                val edgeBias = try {
                    (abs(shipEngine.engineSlot.angle) / 180f) * 0.08f
                } catch (_: Throwable) {
                    0f
                }
                val pulse = 0.94f + 0.06f * sin(now * 6.6f + flare.phase + index * 0.11f)
                val thrust = (0.04f + movementLevel * 0.50f + visualLevel * 0.26f + fluxLevel * 0.06f + systemBoost + edgeBias * 0.35f)
                    .coerceIn(0f, 0.95f) * overloadPenalty * contributionBoost
                val alpha = (0.015f + thrust * 0.22f).coerceIn(0f, 0.36f) * pulse
                val size = flare.baseSize * (0.78f + thrust * 0.58f + systemBoost * 0.28f + visualLevel * 0.08f) * pulse
                val noise = (0.08f + thrust * 0.08f).coerceIn(0.08f, 0.18f)

                updateFlare(
                    flare.entity,
                    shipEngine.location,
                    size,
                    alpha,
                    noise,
                    visualLevel,
                )
            }
        }

        private fun updateFlare(entity: FlareEntity, location: Vector2f, size: Float, alpha: Float, noisePower: Float, visualLevel: Float) {
            try {
                entity.setStateVanilla(location, 0f)
                entity.setSize(size, size)
                entity.setGlobalAlpha(alpha.coerceIn(0f, 1f))
                entity.setGlowPower((0.95f + alpha * 2.0f + visualLevel * 0.75f).coerceIn(0.95f, 4.0f))
                entity.setNoisePower(noisePower)
                entity.setCoreColor(ArcFlareOverdriveVisualState.lerpColor(ArcFlareOverdriveVisualState.coldFringe, ArcFlareOverdriveVisualState.hotFringe, visualLevel, 20))
                entity.setFringeColor(ArcFlareOverdriveVisualState.lerpColor(ArcFlareOverdriveVisualState.coldCore, ArcFlareOverdriveVisualState.hotCore, visualLevel, 78))
            } catch (_: Throwable) {
            }
        }

        private fun deleteAttachment(att: Attachment) {
            att.flares.forEach { flare ->
                try {
                    flare.entity.delete()
                } catch (_: Throwable) {
                }
            }
        }
    }
}