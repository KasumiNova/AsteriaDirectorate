package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.combat.hullmods.arc.isASTDShip
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI
import com.fs.starfarer.api.input.InputEventAPI
import kotlin.math.max

/**
 * ASTD 全系“类矢量推进”引擎表现。
 *
 * 目标：
 * - 模拟矢量推进器——但**不改变推力朝向**（不转向），只让“当前出力的引擎”更亮、
 *   “没出力的引擎”收暗，从而表现出推力的方向性。
 * - 由于引擎 API 不支持运行时改几何宽度（EngineSlotAPI 无 setWidth），
 *   这里用原版 setFlameLevel(slot, level) 逐引擎调火焰强度来达成等效观感：
 *   推力大的引擎火焰满（视觉更粗更亮），推力小的引擎火焰弱。
 *
 * 关键点：
 * - setFlameLevel 每帧会被原版按输入重写，因此必须在 every-frame 里**每帧重设**才能持续生效。
 * - 每个引擎的方向贡献用 ShipEngineAPI.getContribution() 读取——它天然反映该引擎
 *   对“当前这一帧的运动”的贡献度，正是矢量推进所需的“哪个引擎在出力”。
 *
 * 生效范围：所有 hullId 以 "astd_" 开头的舰船（含变体/D-mod，见 isASTDShip）。
 */
internal object ASTDVectorThrustEngineManager {

    private const val ENGINE_KEY = "astd_vector_thrust_engine_manager"
    private const val SCAN_INTERVAL = 0.5f

    // 怠速（无输入）时引擎保留的最低火焰，避免引擎完全熄灭显得“掉电”。
    private const val IDLE_FLAME = 0.18f

    // 火焰强度对贡献变化的跟随速度（每秒插值系数），防止抖动/突变。
    private const val FLAME_LERP_PER_SEC = 8f

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

    private class Attachment(
        val ship: ShipAPI,
        // 每个引擎当前平滑后的火焰 level，按引擎在列表中的下标存。
        val flameLevels: FloatArray,
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
                val ship = att.ship
                val gone = try {
                    ship.isHulk || ship.hitpoints <= 0f || !combatEngine.isEntityInPlay(ship)
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

                val engines = try {
                    ship.engineController?.shipEngines
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (engines.isEmpty()) continue

                attachments[key] = Attachment(ship, FloatArray(engines.size) { IDLE_FLAME })
            }
        }

        private fun updateAttachment(att: Attachment, amount: Float) {
            val ship = att.ship
            val controller = try {
                ship.engineController
            } catch (_: Throwable) {
                null
            } ?: return
            val engines = try {
                controller.shipEngines
            } catch (_: Throwable) {
                null
            } ?: return

            // 是否有任何主动机动输入；无输入时回落到怠速火焰，不强行点亮。
            val maneuvering = try {
                controller.isAccelerating || controller.isAcceleratingBackwards ||
                    controller.isDecelerating || controller.isStrafingLeft ||
                    controller.isStrafingRight || controller.isTurningLeft ||
                    controller.isTurningRight
            } catch (_: Throwable) {
                false
            }

            val lerp = (FLAME_LERP_PER_SEC * amount).coerceIn(0f, 1f)

            val count = minOf(engines.size, att.flameLevels.size)
            for (i in 0 until count) {
                val engine: ShipEngineAPI = engines[i] ?: continue

                val usable = try {
                    !engine.isDisabled && !engine.isPermanentlyDisabled
                } catch (_: Throwable) {
                    false
                }

                // 目标火焰：矢量推进核心——按该引擎对当前运动的贡献度。
                // 有机动输入时贡献高的引擎接近满火、贡献低的收暗；无输入时统一怠速。
                val target = if (!usable) {
                    0f
                } else if (!maneuvering) {
                    IDLE_FLAME
                } else {
                    val contribution = try {
                        engine.contribution
                    } catch (_: Throwable) {
                        1f
                    }.coerceIn(0f, 1f)
                    max(IDLE_FLAME, contribution)
                }

                val current = att.flameLevels[i] + (target - att.flameLevels[i]) * lerp
                att.flameLevels[i] = current

                val slot = try {
                    engine.engineSlot
                } catch (_: Throwable) {
                    null
                } ?: continue

                try {
                    controller.setFlameLevel(slot, current.coerceIn(0f, 1f))
                } catch (_: Throwable) {
                }
            }
        }
    }
}
