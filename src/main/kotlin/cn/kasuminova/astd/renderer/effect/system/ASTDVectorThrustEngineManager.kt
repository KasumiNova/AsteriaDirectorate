package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.combat.hullmods.arc.isASTDShip
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipEngineControllerAPI
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI
import com.fs.starfarer.api.input.InputEventAPI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * ASTD 全系“类矢量推进”引擎表现。
 *
 * 模拟矢量推进器——但**不改变推力朝向**（不转向），只让“当前出力方向的引擎”火焰满、
 * “反方向/不出力的引擎”火焰收小，表现推力方向性。
 *
 * 实现：每帧用 ShipEngineControllerAPI.setFlameLevel(slot, level) 逐引擎设置火焰长度。
 * 这是经其它模组（如 Galactic_Constellate 的 gr_Nunki）验证可用的做法——关键是**每帧重设**
 * （原版会重算，所以必须每帧覆盖）。
 *
 * level 计算（不依赖 getContribution，避免其在 AI 船/静止时恒 0 导致“全部停在最小”）：
 * - 由引擎喷射方向（slot.angle）推出该引擎的推力轴，与当前运动意图（加速/减速/横移/转向）
 *   做点积，决定该引擎该不该“出力”。
 * - 设有怠速基线 IDLE_FLAME，保证引擎始终可见、且静止时不全灭。
 *
 * 生效范围：所有 hullId 以 "astd_" 开头的舰船（含变体/D-mod，见 isASTDShip）。
 * 安装入口：经 CombatVfxBootstrap，由全局 ASTDGlobalCombatPlugin 每场战斗安装（不依赖武器）。
 */
internal object ASTDVectorThrustEngineManager {

    private const val ENGINE_KEY = "astd_vector_thrust_engine_manager"
    private const val SCAN_INTERVAL = 0.5f

    // 怠速基线：无明显推力时引擎保留的火焰，避免全灭。
    private const val IDLE_FLAME = 0.45f
    // 出力时的火焰上限。
    private const val FULL_FLAME = 1.0f
    // 反方向/不出力时压到的最低火焰。
    private const val LOW_FLAME = 0.18f
    // 火焰跟随速度（每秒插值系数），防抖。
    private const val LERP_PER_SEC = 9f

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

    private class EngineState(
        val engine: ShipEngineAPI,
        // 引擎推力方向单位向量（船体坐标系）：推力沿喷射方向的反方向。
        // 喷口朝 slot.angle 喷火 -> 推力指向 angle+180。
        val thrustAxisX: Float,
        val thrustAxisY: Float,
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
                // 喷口朝 angle 喷火，推力指向相反方向：angle + 180。
                val thrustAngle = Math.toRadians((angle + 180f).toDouble())
                states += EngineState(
                    engine = engine,
                    thrustAxisX = cos(thrustAngle).toFloat(),
                    thrustAxisY = sin(thrustAngle).toFloat(),
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

            // 当前推力意图向量（船体本地坐标系：+x=船头方向，+y=左舷）。
            // 加速=向前(+x)，减速/后退=向后(-x)，横移=左右(±y)。
            var intentX = 0f
            var intentY = 0f
            try {
                if (controller.isAccelerating) intentX += 1f
                if (controller.isDecelerating || controller.isAcceleratingBackwards) intentX -= 1f
                if (controller.isStrafingLeft) intentY += 1f
                if (controller.isStrafingRight) intentY -= 1f
            } catch (_: Throwable) {
            }

            // 转向：左转(逆时针)需要右侧/尾部不对称推力。用角向意图叠加到 y（近似）。
            var turnIntent = 0f
            try {
                if (controller.isTurningLeft) turnIntent += 1f
                if (controller.isTurningRight) turnIntent -= 1f
            } catch (_: Throwable) {
            }

            val hasIntent = abs(intentX) > 0.01f || abs(intentY) > 0.01f || abs(turnIntent) > 0.01f
            val intentLen = max(1e-3f, kotlin.math.sqrt(intentX * intentX + intentY * intentY))
            val nIntentX = intentX / intentLen
            val nIntentY = intentY / intentLen

            val lerp = (LERP_PER_SEC * amount).coerceIn(0f, 1f)

            for (st in att.engines) {
                val engine = st.engine
                val usable = try {
                    !engine.isDisabled && !engine.isPermanentlyDisabled
                } catch (_: Throwable) {
                    false
                }

                val target: Float = if (!usable) {
                    0f
                } else if (!hasIntent) {
                    IDLE_FLAME
                } else {
                    // 该引擎推力轴与运动意图的对齐度（点积，-1..1）。
                    val align = st.thrustAxisX * nIntentX + st.thrustAxisY * nIntentY
                    // 转向贡献：引擎离中轴越偏、且方向匹配转向，则出力。用推力轴 y 分量近似力矩方向。
                    val turnContribution = turnIntent * st.thrustAxisY
                    val combined = max(align, turnContribution * 0.8f)
                    // align>0 出力，align<0 收小。映射到 [LOW_FLAME, FULL_FLAME]。
                    val norm = ((combined + 1f) * 0.5f).coerceIn(0f, 1f) // 0..1
                    LOW_FLAME + (FULL_FLAME - LOW_FLAME) * norm
                }

                st.level += (target - st.level) * lerp

                val slot = try {
                    engine.engineSlot
                } catch (_: Throwable) {
                    null
                } ?: continue
                try {
                    controller.setFlameLevel(slot, st.level.coerceIn(0f, 1f))
                } catch (_: Throwable) {
                }
            }
        }
    }
}
