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
 * level 计算（纯方向意图模型，不用 getContribution——实测其在玩家船上恒≈0 不可用）：
 * - 由引擎喷射方向（slot.angle）+ 本地力臂推出推力轴与转向力矩，与当前运动意图
 *   （加速/减速/横移/转向）对齐，决定该引擎该不该“出力”。
 * - 直推引擎转向按力臂归一；侧推引擎转向按力矩符号（中部侧推力臂≈0，符号驱动以保观感）。
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
        // 引擎推力方向单位向量（船体本地坐标系）：推力沿喷射方向的反方向。
        // 喷口朝 slot.angle 喷火 -> 推力指向 angle+180。
        val thrustAxisX: Float,
        val thrustAxisY: Float,
        // 该引擎对“纯转向”的力矩贡献符号：+ 为逆时针(左转)，- 为顺时针(右转)。
        // 力矩 z = rx*Fy - ry*Fx，r 为引擎相对船心的本地力臂，F 为推力轴。
        val turnMoment: Float,
        // 是否“侧推引擎”：推力轴横向分量占主导（朝侧面喷）。用于转向时的观感增强。
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

            // 船心与朝向，用于把引擎世界坐标转回船体本地力臂（仅建簇时算一次）。
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
                // 喷口朝 angle 喷火，推力指向相反方向：angle + 180。
                val thrustAngle = Math.toRadians((angle + 180f).toDouble())
                val fx = cos(thrustAngle).toFloat()
                val fy = sin(thrustAngle).toFloat()

                // 引擎相对船心的本地力臂 r：世界向量旋转 -shipFacing 回本地系。
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
                // 力矩 z 分量：rx*Fy - ry*Fx。正=逆时针(左转)贡献。
                val turnMoment = rx * fy - ry * fx

                states += EngineState(
                    engine = engine,
                    thrustAxisX = fx,
                    thrustAxisY = fy,
                    turnMoment = turnMoment,
                    // 横向分量大于纵向 -> 视为侧推引擎。
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

            val hasTranslation = abs(intentX) > 0.01f || abs(intentY) > 0.01f
            val hasTurn = abs(turnIntent) > 0.01f
            val hasIntent = hasTranslation || hasTurn
            // 平移意图归一化（纯转向时为 0 向量，align 自然为 0，由 turn 分支接管）。
            val intentLen = max(1e-3f, kotlin.math.sqrt(intentX * intentX + intentY * intentY))
            val nIntentX = if (hasTranslation) intentX / intentLen else 0f
            val nIntentY = if (hasTranslation) intentY / intentLen else 0f

            // 本船最大转向力矩，用于把各引擎 turnMoment 归一到 [-1,1]。
            var maxMoment = 1e-3f
            for (st in att.engines) {
                val m = abs(st.turnMoment)
                if (m > maxMoment) maxMoment = m
            }

            val lerp = (LERP_PER_SEC * amount).coerceIn(0f, 1f)

            for (st in att.engines) {
                val engine = st.engine
                val usable = try {
                    !engine.isDisabled && !engine.isPermanentlyDisabled
                } catch (_: Throwable) {
                    false
                }

                // 引擎熄火（被打掉/被系统禁用）时，**完全不调用 setFlameLevel**，把火焰表现
                // 与熄火修复计时整体交还原版。
                //
                // 根因：setFlameLevel 每帧外部强制覆盖引擎当前火焰长度。原版熄火后按
                // engineRepairTimeMult 逐步恢复，恢复过程由原版引擎控制器每帧重算火焰状态驱动。
                // 若每帧再用 setFlameLevel(slot, 0f) 把火焰写 0，会干扰原版的熄火/修复状态机，
                // 表现为修复时间被显著拖长。所以熄火引擎必须 `continue` 跳过本帧设置。
                //
                // 该做法与本类注释引用的、经验证可用的参考实现一致
                // （Galactic_Constellate gr_deco_engine：`if (engine.isDisabled()) return;`）。
                // 同时把 st.level 重置到 IDLE_FLAME，使引擎修复完成、重新可用时从怠速基线平滑起跳，
                // 而非从一个陈旧的高火焰值突变。
                if (!usable) {
                    st.level = IDLE_FLAME
                    continue
                }

                val target: Float = if (!hasIntent) {
                    IDLE_FLAME
                } else {
                    // 平移对齐度：该引擎推力轴与平移意图的点积（-1..1）。
                    val align = st.thrustAxisX * nIntentX + st.thrustAxisY * nIntentY
                    // 转向对齐度：该引擎力矩方向与转向意图一致则为正（-1..1）。
                    // turnIntent>0=左转(逆时针)，turnMoment>0 也是逆时针贡献 -> 同号即出力。
                    val turnAlign = if (!hasTurn) {
                        -1f // 无转向意图时，转向分支不贡献（取最低，由 max 让平移接管）
                    } else if (st.isLateral) {
                        // 侧推引擎：中部侧推力臂≈0、力矩很小，按力臂归一会被漏掉。
                        // 改为只看力矩符号——符号匹配转向就给较强出力（观感优先）。
                        val s = st.turnMoment * turnIntent
                        if (s > 0f) 0.85f else -1f
                    } else {
                        // 直推等引擎：保持力臂归一，避免所有直推转向都亮。
                        ((st.turnMoment / maxMoment) * turnIntent).coerceIn(-1f, 1f)
                    }
                    // 取平移与转向中更“出力”的一方。
                    val combined = max(align, turnAlign)
                    val norm = ((combined + 1f) * 0.5f).coerceIn(0f, 1f)
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
