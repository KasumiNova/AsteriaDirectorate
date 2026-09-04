package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.boxutil.base.api.InstanceDataAPI
import org.boxutil.base.api.InstanceRenderAPI
import org.boxutil.define.BoxEnum
import org.boxutil.define.InstanceType
import org.boxutil.units.standard.attribute.Instance2Data
import org.boxutil.units.standard.entity.FlareEntity
import org.magiclib.util.MagicLensFlare
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.sin

/**
 * Arc Flare（弧光耀斑）舰体 emissive 覆盖层。
 *
 * 设计：
 * - 整船 base emissive 交给 decorative weapon 在战斗内动态控光，确保与舰体严格对齐
 * - BoxUtil 这里只负责一层 halo / bloom 外扩，避免整船 SpriteEntity 覆盖带来的错位与观感炸裂
 */
internal object ArcFlareEmissiveOverlayManager {

    private const val ENGINE_KEY = "astd_arc_flare_emissive_overlay_manager"
    private const val HULL_ID = "astd_arc_flare"
    private const val SCAN_INTERVAL = 0.33f

    private val log = Global.getLogger(ArcFlareEmissiveOverlayManager::class.java)

    fun ensureInstalled(engine: CombatEngineAPI) {
        val existing = engine.customData[ENGINE_KEY]
        if (existing != null) return

        try {
            val plugin = Plugin(engine)
            engine.addPlugin(plugin)
            engine.customData[ENGINE_KEY] = plugin
        } catch (t: Throwable) {
            engine.customData[ENGINE_KEY] = false
            log.warn("[ASTD] ArcFlareEmissiveOverlayManager install failed", t)
        }
    }

    private data class Attachment(
        val ship: ShipAPI,
        val halo: FlareEntity,
        val bloomHalo: FlareEntity,
        val pulseHalo: FlareEntity,
        var prevSystemLevel: Float,
        var pulseTimer: Float = Float.MAX_VALUE,
        var pulseDuration: Float = 0.42f,
        var pulseMode: Int = 0,
        var flashTimer: Float = 0f,
    )

    private class Plugin(private val engine: CombatEngineAPI) : BaseEveryFrameCombatPlugin() {
        private val attachments = LinkedHashMap<Int, Attachment>()
        private var scanAcc = 0f
        private var installLogged = false

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (engine.isPaused) return

            if (!installLogged) {
                installLogged = true
                log.info("[ASTD] ArcFlareEmissiveOverlayManager active")
            }

            try {
                BoxUtilCombatVfx.ensureReady(engine)
            } catch (_: Throwable) {
            }

            scanAcc += amount
            if (scanAcc >= SCAN_INTERVAL) {
                scanAcc = 0f
                scanShips()
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

                updateAttachment(att, amount)
            }
        }

        private fun scanShips() {
            val ships = try {
                engine.ships
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
            val halo = createHaloEntity(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) ?: run {
                return null
            }
            val bloomHalo = createBloomHaloEntity(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) ?: run {
                try {
                    halo.delete()
                } catch (_: Throwable) {
                }
                return null
            }
            val pulseHalo = createPulseHaloEntity(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) ?: run {
                try {
                    halo.delete()
                } catch (_: Throwable) {
                }
                try {
                    bloomHalo.delete()
                } catch (_: Throwable) {
                }
                return null
            }
            updateHalo(halo, ship, 0.16f, 1.55f, 0f)
            updateBloomHalo(bloomHalo, ship, 0.08f, 1.75f, 0f)
            updatePulseHalo(pulseHalo, ship, 0f, 0.9f, 1, 0f, 0f)
            val systemLevel = try {
                ship.system?.effectLevel ?: 0f
            } catch (_: Throwable) {
                0f
            }.coerceIn(0f, 1f)
            try {
                log.info("[ASTD] ArcFlareEmissiveOverlayManager attached ship=${ship.hullSpec?.hullId} haloOnly=true")
            } catch (_: Throwable) {
            }
            return Attachment(
                ship = ship,
                halo = halo,
                bloomHalo = bloomHalo,
                pulseHalo = pulseHalo,
                prevSystemLevel = systemLevel,
            ).also {
                if (systemLevel > 0.05f) {
                    startPulse(it, mode = 1)
                }
            }
        }

        private fun createBloomHaloEntity(layer: CombatEngineLayers): FlareEntity? {
            val entity = try {
                FlareEntity()
            } catch (_: Throwable) {
                return null
            }

            try {
                entity.setLayer(layer)
                entity.setAdditiveBlend()
                entity.setSmooth()
                entity.setFlick(false)
                entity.setSyncFlick(false)
                entity.setGlowPower(1.75f)
                entity.setCoreColor(Color(255, 246, 236, 16))
                entity.setFringeColor(Color(122, 222, 255, 46))
                entity.setGlobalAlpha(0f)
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
                BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_FLARE, entity)
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

        private fun createHaloEntity(layer: CombatEngineLayers): FlareEntity? {
            val entity = try {
                FlareEntity()
            } catch (_: Throwable) {
                return null
            }

            try {
                entity.setLayer(layer)
                entity.setAdditiveBlend()
                entity.setSmooth()
                entity.setFlick(false)
                entity.setSyncFlick(false)
                entity.setGlowPower(1.15f)
                entity.setCoreColor(Color(255, 245, 238, 20))
                entity.setFringeColor(Color(148, 232, 255, 60))
                entity.setGlobalAlpha(0.18f)
                entity.setNoisePower(0.12f)
                entity.setFlickMixValue(0.72f)
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
                BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_FLARE, entity)
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

        private fun createPulseHaloEntity(layer: CombatEngineLayers): FlareEntity? {
            val entity = try {
                FlareEntity()
            } catch (_: Throwable) {
                return null
            }

            try {
                entity.setLayer(layer)
                entity.setAdditiveBlend()
                entity.setSmooth()
                entity.setFlick(false)
                entity.setSyncFlick(false)
                entity.setGlowPower(2.1f)
                entity.setCoreColor(Color(255, 248, 242, 12))
                entity.setFringeColor(Color(132, 228, 255, 30))
                entity.setGlobalAlpha(0f)
                entity.setNoisePower(0.10f)
                entity.setFlickMixValue(0.70f)
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
                BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_FLARE, entity)
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

            val stSubmit = submitFixedInstanceDataCompat(entity, apiList.size)
            return stSubmit == BoxEnum.STATE_SUCCESS && entity.haveValidInstanceData() && entity.getValidInstanceDataCount() >= 1
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

        private fun updateAttachment(att: Attachment, amount: Float) {
            val ship = att.ship
            val systemLevel = try {
                ship.system?.effectLevel ?: 0f
            } catch (_: Throwable) {
                0f
            }.coerceIn(0f, 1f)
            val visualLevel = ArcFlareOverdriveVisualState.getLevel(ship, engine).coerceAtLeast(systemLevel)
            val fluxLevel = try {
                ship.fluxTracker?.fluxLevel ?: 0f
            } catch (_: Throwable) {
                0f
            }.coerceIn(0f, 1f)
            val overloadBoost = try {
                if (ship.fluxTracker?.isOverloaded == true || ship.fluxTracker?.isVenting == true) 0.18f else 0f
            } catch (_: Throwable) {
                0f
            }
            if (att.prevSystemLevel <= 0.05f && systemLevel > 0.05f) {
                startPulse(att, mode = 1)
                spawnCenterLensFlare(ship, systemLevel.coerceAtLeast(0.5f))
            } else if (att.prevSystemLevel > 0.05f && systemLevel <= 0.05f) {
                startPulse(att, mode = -1)
                spawnCenterLensFlare(ship, 0.35f)
            }
            att.prevSystemLevel = systemLevel

            att.flashTimer -= amount
            if (systemLevel > 0.15f && att.pulseTimer > att.pulseDuration && att.flashTimer <= 0f) {
                startPulse(att, mode = 2)
                spawnCenterLensFlare(ship, systemLevel)
                att.flashTimer = (0.34f - 0.12f * systemLevel).coerceAtLeast(0.14f)
            }

            val now = try {
                engine.getTotalElapsedTime(false)
            } catch (_: Throwable) {
                0f
            }
            val pulse = if (systemLevel > 0.01f) {
                0.94f + 0.06f * (0.5f + 0.5f * sin(now * 3.2f))
            } else {
                1f
            }

            val haloAlpha = (0.005f + visualLevel * (0.02f + 0.01f * pulse) + overloadBoost * 0.015f).coerceIn(0f, 0.06f)
            val haloGlow = (0.85f + visualLevel * 0.85f + overloadBoost * 0.25f).coerceIn(0f, 1.9f)
            val bloomAlpha = (0.008f + visualLevel * (0.03f + 0.015f * pulse) + overloadBoost * 0.02f).coerceIn(0f, 0.08f)
            val bloomGlow = (0.95f + visualLevel * 1.05f + overloadBoost * 0.30f).coerceIn(0f, 2.2f)

            updateHalo(att.halo, ship, haloAlpha, haloGlow, visualLevel)
            updateBloomHalo(att.bloomHalo, ship, bloomAlpha, bloomGlow, visualLevel)

            if (att.pulseTimer <= att.pulseDuration) {
                att.pulseTimer += amount
                val t = (att.pulseTimer / att.pulseDuration).coerceIn(0f, 1f)
                val fade = (1f - t) * (1f - t)
                val expand = 1f - (1f - t) * (1f - t)
                val alpha = if (att.pulseMode == 2) {
                    (0.60f * fade).coerceIn(0f, 0.60f)
                } else if (att.pulseMode > 0) {
                    (0.44f * fade).coerceIn(0f, 0.44f)
                } else {
                    (0.28f * fade).coerceIn(0f, 0.28f)
                }
                val glow = if (att.pulseMode == 2) {
                    3.40f + fade * 1.35f + visualLevel * 1.10f
                } else if (att.pulseMode > 0) {
                    2.55f + fade * 1.15f + visualLevel * 0.80f
                } else {
                    1.90f + fade * 0.75f
                }
                updatePulseHalo(att.pulseHalo, ship, alpha, glow, att.pulseMode, expand, visualLevel)
            } else {
                updatePulseHalo(att.pulseHalo, ship, 0f, 0.9f, 1, 0f, visualLevel)
            }
        }

        private fun startPulse(att: Attachment, mode: Int) {
            att.pulseMode = mode
            att.pulseTimer = 0f
            att.pulseDuration = when {
                mode == 2 -> 0.18f
                mode > 0 -> 0.42f
                else -> 0.32f
            }
        }

        private fun updateHalo(entity: FlareEntity, ship: ShipAPI, alphaMul: Float, glowPower: Float, visualLevel: Float) {
            val size = ship.collisionRadius * (1.34f + alphaMul * 0.48f)
            try {
                entity.setStateVanilla(ship.location, 0f)
                entity.setSize(size, size)
                entity.setGlobalAlpha(alphaMul.coerceIn(0f, 1f))
                entity.setGlowPower(glowPower.coerceIn(0.8f, 4.8f))
                entity.setNoisePower((0.10f + alphaMul * 0.18f).coerceIn(0.10f, 0.28f))
                entity.setCoreColor(ArcFlareOverdriveVisualState.lerpColor(ArcFlareOverdriveVisualState.coldFringe, ArcFlareOverdriveVisualState.coldCore, 0.35f, 10))
                entity.setFringeColor(ArcFlareOverdriveVisualState.lerpColor(ArcFlareOverdriveVisualState.coldCore, ArcFlareOverdriveVisualState.coldFringe, 0.45f, 18))
            } catch (_: Throwable) {
            }
        }

        private fun updateBloomHalo(entity: FlareEntity, ship: ShipAPI, alphaMul: Float, glowPower: Float, visualLevel: Float) {
            val size = ship.collisionRadius * (1.58f + alphaMul * 0.66f)
            try {
                entity.setStateVanilla(ship.location, 0f)
                entity.setSize(size, size)
                entity.setGlobalAlpha(alphaMul.coerceIn(0f, 1f))
                entity.setGlowPower(glowPower.coerceIn(1.0f, 5.2f))
                entity.setNoisePower((0.08f + alphaMul * 0.12f).coerceIn(0.08f, 0.22f))
                entity.setCoreColor(ArcFlareOverdriveVisualState.lerpColor(ArcFlareOverdriveVisualState.coldFringe, ArcFlareOverdriveVisualState.coldCore, 0.40f, 12))
                entity.setFringeColor(ArcFlareOverdriveVisualState.lerpColor(ArcFlareOverdriveVisualState.coldCore, ArcFlareOverdriveVisualState.coldFringe, 0.55f, 20))
            } catch (_: Throwable) {
            }
        }

        private fun updatePulseHalo(entity: FlareEntity, ship: ShipAPI, alphaMul: Float, glowPower: Float, mode: Int, expand: Float = 0f, visualLevel: Float) {
            val size = if (mode == 2) {
                ship.collisionRadius * (0.40f + expand * 0.38f)
            } else if (mode > 0) {
                ship.collisionRadius * (1.42f + expand * 1.28f)
            } else {
                ship.collisionRadius * (1.78f + expand * 0.92f)
            }
            try {
                entity.setStateVanilla(ship.location, 0f)
                entity.setSize(size, size)
                entity.setGlobalAlpha(alphaMul.coerceIn(0f, 1f))
                entity.setGlowPower(glowPower.coerceIn(0.8f, 4.8f))
                entity.setNoisePower((0.09f + alphaMul * 0.10f).coerceIn(0.09f, 0.20f))
                if (mode == 2) {
                    entity.setCoreColor(Color(255, 248, 240, 180))
                    entity.setFringeColor(ArcFlareOverdriveVisualState.lerpColor(ArcFlareOverdriveVisualState.hotCore, Color(255, 112, 38, 220), visualLevel, 220))
                } else if (mode > 0) {
                    entity.setCoreColor(ArcFlareOverdriveVisualState.lerpColor(ArcFlareOverdriveVisualState.coldFringe, ArcFlareOverdriveVisualState.hotCore, visualLevel, 32))
                    entity.setFringeColor(ArcFlareOverdriveVisualState.lerpColor(ArcFlareOverdriveVisualState.coldCore, ArcFlareOverdriveVisualState.hotFringe, visualLevel, 126))
                } else {
                    entity.setCoreColor(ArcFlareOverdriveVisualState.lerpColor(ArcFlareOverdriveVisualState.coldFringe, ArcFlareOverdriveVisualState.hotCore, visualLevel, 20))
                    entity.setFringeColor(ArcFlareOverdriveVisualState.lerpColor(ArcFlareOverdriveVisualState.coldCore, ArcFlareOverdriveVisualState.hotFringe, visualLevel, 76))
                }
            } catch (_: Throwable) {
            }
        }

        private fun spawnCenterLensFlare(ship: ShipAPI, intensity: Float) {
            val t = intensity.coerceIn(0f, 1f)
            val fringe = ArcFlareOverdriveVisualState.lerpColor(ArcFlareOverdriveVisualState.hotFringe, Color(255, 108, 35), t, (120f + 60f * t).toInt())
            val core = Color(255, 245, 235, (180f + 60f * t).toInt().coerceIn(0, 255))
            val length = 100f + 120f * t
            val thickness = 4f + 5f * t
            val radius = ship.shieldRadiusEvenIfNoShield
            val count = (1 + (t * 2f).toInt()).coerceIn(1, 3)
            repeat(count) {
                try {
                    val point = org.lazywizard.lazylib.MathUtils.getRandomPointOnCircumference(ship.location, radius)
                    MagicLensFlare.createSharpFlare(engine, ship, point, thickness, length, 0f, fringe, core)
                } catch (_: Throwable) {
                }
            }
        }

        private fun deleteAttachment(att: Attachment) {
            try {
                att.halo.delete()
            } catch (_: Throwable) {
            }
            try {
                att.bloomHalo.delete()
            } catch (_: Throwable) {
            }
            try {
                att.pulseHalo.delete()
            } catch (_: Throwable) {
            }
        }

    }
}
