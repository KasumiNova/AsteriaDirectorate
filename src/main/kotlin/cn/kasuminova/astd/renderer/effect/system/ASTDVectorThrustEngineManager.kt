package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.combat.hullmods.arc.isASTDShip
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
import kotlin.math.max

/**
 * ASTD 全系“类矢量推进”引擎表现。
 *
 * 目标：模拟矢量推进器——但**不改变推力朝向**（不转向），只让“当前出力的引擎”更亮更“粗”、
 * “没出力的引擎”收暗，表现出推力的方向性。
 *
 * 实现选择（重要）：
 * - 不用 ShipEngineControllerAPI.setFlameLevel：该值会被原版引擎控制器每帧重算覆盖，
 *   做持续效果会闪烁/无效（实测/社区已知坑）。
 * - 改为在每个引擎位置叠一层 BoxUtil FlareEntity（加色发光，渲染层独立，不与原版引擎
 *   控制器竞态），按 ShipEngineAPI.getContribution() 调 flare 的 size/alpha：
 *   contribution 高（正在出力）→ flare 大而亮，看起来该引擎“更粗”；contribution 低 → 收暗。
 *   这与 mod 内已验证可用的 ArcFlareEngineFlareManager 同一套渲染做法。
 *
 * getContribution() 0~1，随当前推力方向变化（加速时主喷≈1、横移时侧喷≈1），正是矢量推进所需。
 *
 * 生效范围：所有 hullId 以 "astd_" 开头的舰船（含变体/D-mod，见 isASTDShip）。
 * 安装入口：经 CombatVfxBootstrap，由全局 ASTDGlobalCombatPlugin 每场战斗安装（不依赖武器）。
 */
internal object ASTDVectorThrustEngineManager {

    private const val ENGINE_KEY = "astd_vector_thrust_engine_manager"
    private const val SCAN_INTERVAL = 0.5f

    // flare 颜色：电离白蓝，加色发光。亮度由 alpha 控制。
    private val FLARE_CORE = Color(255, 250, 245, 30)
    private val FLARE_FRINGE = Color(150, 220, 255, 90)

    private val log = Global.getLogger(ASTDVectorThrustEngineManager::class.java)

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

    private class EngineFlare(
        val engine: ShipEngineAPI,
        val entity: FlareEntity,
        val baseSize: Float,
    )

    private class Attachment(
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
                log.info("[ASTD] ASTDVectorThrustEngineManager active")
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

            val it = attachments.entries.iterator()
            while (it.hasNext()) {
                val (_, att) = it.next()
                val gone = try {
                    att.ship.isHulk || att.ship.hitpoints <= 0f || !combatEngine.isEntityInPlay(att.ship)
                } catch (_: Throwable) {
                    true
                }
                if (gone) {
                    deleteAttachment(att)
                    it.remove()
                    continue
                }
                updateAttachment(att)
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

            val flares = ArrayList<EngineFlare>(engines.size)
            for (engine in engines) {
                if (engine == null) continue
                if (engine.isPermanentlyDisabled) continue

                val slot = try {
                    engine.engineSlot
                } catch (_: Throwable) {
                    null
                } ?: continue
                val width = try {
                    slot.width
                } catch (_: Throwable) {
                    10f
                }
                val length = try {
                    slot.length
                } catch (_: Throwable) {
                    40f
                }

                val entity = createFlareEntity() ?: continue
                // baseSize 关联引擎尺寸：大引擎 flare 也大。
                val baseSize = max(width * 1.8f, length * 0.7f) + 6f
                flares += EngineFlare(engine, entity, baseSize)
            }

            if (flares.isEmpty()) return null
            try {
                log.info("[ASTD] vector-thrust attached ship=${ship.hullSpec?.hullId} engines=${flares.size}")
            } catch (_: Throwable) {
            }
            return Attachment(ship, flares)
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
                entity.setGlowPower(1.1f)
                entity.setCoreColor(FLARE_CORE)
                entity.setFringeColor(FLARE_FRINGE)
                entity.setGlobalAlpha(0f)
                entity.setNoisePower(0.10f)
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

        private fun updateAttachment(att: Attachment) {
            val ship = att.ship
            val controller = try {
                ship.engineController
            } catch (_: Throwable) {
                null
            }

            // 是否有主动机动输入；无输入时所有 flare 收到最低，避免静止时全亮。
            val maneuvering = try {
                controller?.let {
                    it.isAccelerating || it.isAcceleratingBackwards || it.isDecelerating ||
                        it.isStrafingLeft || it.isStrafingRight || it.isTurningLeft || it.isTurningRight
                } ?: false
            } catch (_: Throwable) {
                false
            }

            for (flare in att.flares) {
                val engine = flare.engine
                val usable = try {
                    !engine.isDisabled && !engine.isPermanentlyDisabled
                } catch (_: Throwable) {
                    false
                }

                // 矢量推进核心：thrust = 该引擎对当前运动的贡献度。
                val thrust = if (!usable || !maneuvering) {
                    0f
                } else {
                    try {
                        engine.contribution
                    } catch (_: Throwable) {
                        0f
                    }.coerceIn(0f, 1f)
                }

                val loc = try {
                    engine.location
                } catch (_: Throwable) {
                    null
                } ?: ship.location

                // alpha/size 随 thrust：出力越大越亮越大。怠速时 alpha=0（不画）。
                val alpha = (thrust * 0.55f).coerceIn(0f, 0.55f)
                val size = flare.baseSize * (0.55f + thrust * 0.85f)

                try {
                    flare.entity.setStateVanilla(loc, 0f)
                    flare.entity.setSize(size, size)
                    flare.entity.setGlobalAlpha(alpha)
                    flare.entity.setGlowPower((1.0f + thrust * 2.2f).coerceIn(1.0f, 3.5f))
                } catch (_: Throwable) {
                }
            }
        }

        private fun deleteAttachment(att: Attachment) {
            att.flares.forEach {
                try {
                    it.entity.delete()
                } catch (_: Throwable) {
                }
            }
        }
    }
}
