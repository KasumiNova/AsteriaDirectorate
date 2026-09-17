package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.combat.hullmods.base.isASTDShip
import cn.kasuminova.astd.impl.render.TriShardComponent
import cn.kasuminova.astd.impl.render.TriShardSpec
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

/**
 * ASTD 全系引擎常驻三角碎片喷散：按引擎当前火焰 level 动态喷散三角碎片——
 * 数量（喷散频率）、速度、尺寸随出力（level）线性放大，颜色/基准尺寸取自引擎自身。
 *
 * 渲染复用 astd-render 的 [TriShardComponent] 树外持续发射用法：每舰每引擎一个组件
 * （单批 spec），逐帧按 level 累积碎片，按 [FLUSH_INTERVAL] 节拍灌成 SpriteEntity
 * 实例化批；实体全局定时器走完由 BoxUtil 自删，无句柄回收负担。
 *
 * level 来源：[ASTDVectorThrustEngineManager] 每帧发布到
 * ship.customData[ASTDVectorThrustEngineManager.ENGINE_LEVELS_KEY]；缺失
 * （管理器未 attach 该舰）时该舰不喷散。熄火引擎 level 发布为 0，自然停喷。
 *
 * 生效范围与安装入口同矢量推进管理器（isASTDShip + CombatVfxBootstrap 每场战斗安装）。
 */
internal object ASTDEngineShardSprayEffect {

    private const val PLUGIN_KEY = "astd_engine_shard_spray_effect"
    private const val SCAN_INTERVAL = 0.5f

    /** 碎片灌批节拍（秒）：每节拍把各引擎累积的碎片灌成一个实例化批。 */
    private const val FLUSH_INTERVAL = 0.1f

    /** 每引擎每秒喷散数量域（按火焰 level 线性插值）。 */
    private const val RATE_MIN = 1f
    private const val RATE_MAX = 12f

    /** 碎片喷射初速域（su/s，按 level 插值；方向沿喷口喷射方向）。 */
    private const val SPEED_MIN = 15f
    private const val SPEED_MAX = 180f

    /** 低于该 level 不喷散（熄火/近零出力）。 */
    private const val LEVEL_EPSILON = 0.05f

    /** 喷散锥半角（度）：绕喷射方向的随机散布。 */
    private const val SPREAD_DEG = 10f

    /** 继承船体速度比例（1.0=碎片与舰体同速基准，喷散仅体现引擎相对喷射速度）。 */
    private const val SHIP_VEL_INHERIT = 1f

    /** 核心色提亮倍率（引擎色的提亮副本，碎片按 coreRatio 概率取用）。 */
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

    /** 单引擎喷散口：引擎句柄、碎片组件（颜色/尺寸随引擎）与发射累积器。 */
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

                // 尺寸基准取喷口宽度：碎片边长 ≈ 宽度×0.16（clamp 4~8）×抖动，随 level 再乘 sizeScale。
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
                // 喷射世界方向：船体朝向 + 喷口本地角。速度/尺寸逐颗抖动，破同帧碎片的机械一致感。
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
