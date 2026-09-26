package cn.kasuminova.astd.combat.effect.arc.cuifeng

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.input.InputEventAPI
import org.boxutil.units.standard.entity.FlareEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 摧锋鱼雷命中特效触发层（blue/30-superlative.md §特效）：
 * 十字辉星 ×2（BoxUtil FlareEntity SHARP 同位叠放，1s 内 100px→200px 扩散并逐渐变淡）
 * + 爆炸星云 ×10（50~100px，ARC 蓝白主色）。
 *
 * 十字辉星扩散/变淡无 BoxUtil 内建动画通道（FlareEntity 只有全局计时器，无尺寸关键帧），
 * 由本文件内 [CrossFlarePlugin] 每帧推进尺寸与透明度（Xc001EmissiveOverlayEffect 逐帧调参先例）。
 */
object CuifengTorpedoVfx {
    private val log = Global.getLogger(CuifengTorpedoVfx::class.java)

    /** 十字辉星存续。 */
    private const val CROSS_FLARE_DURATION = 0.5f

    /** 十字辉星起止尺寸（px；第二枚 0.7 倍错位叠放）。 */
    private const val FLARE_SIZE_START = 100f
    private const val FLARE_SIZE_END = 400f
    private const val FLARE_SECOND_SCALE = 0.7f

    /** 辉星核心色（ARC 冷蓝白近白）。 */
    private val FLARE_CORE_COLOR = Color(225, 242, 255)

    /** 辉星辉光色（ARC 蓝）。 */
    private val FLARE_FRINGE_COLOR = Color(140, 205, 255)

    /** 星云色（ARC 蓝白，alpha 由 addNebulaParticle 亮度参数调制）。 */
    private val NEBULA_COLOR = Color(140, 200, 255, 130)

    /** 爆炸闪光色（星云同色系的顶点补光）。 */
    private val FLASH_COLOR = Color(160, 210, 255, 90)

    /** 静止速度矢量（闪光/星云用，避免逐次分配）。 */
    private val ZERO_VEL = Vector2f(0f, 0f)

    /** 触发一次命中特效：顶点闪光 → 十字辉星 ×2 → 爆炸星云 ×10（同帧）。 */
    fun spawnImpact(engine: CombatEngineAPI, point: Vector2f) = spawnImpact(engine, point, Random.Default)

    /** 可注入随机源的入口（单元测试与运行共用同一路径）。 */
    fun spawnImpact(engine: CombatEngineAPI, point: Vector2f, random: Random) {
        engine.addHitParticle(point, ZERO_VEL, FLASH_SIZE, 1.1f, 0.12f, FLARE_CORE_COLOR)
        engine.addSmoothParticle(point, ZERO_VEL, FLASH_SIZE * 1.6f, 0.8f, 0.2f, FLASH_COLOR)
        spawnCrossFlare(engine, point)
        spawnNebulaBurst(engine, point, random)
    }

    /** 十字辉星：两枚 SHARP 光斑同位叠放（第二枚 0.7× 尺寸、45° 错位），由插件推进 1s 扩散消散。 */
    private fun spawnCrossFlare(engine: CombatEngineAPI, point: Vector2f) {
        BoxUtilCombatVfx.ensureReady(engine)
        val first = buildFlare(point, FLARE_SIZE_START, 0f) ?: return
        val second = buildFlare(point, FLARE_SIZE_START, 90f) ?: run {
            first.delete()
            return
        }
        engine.addPlugin(CrossFlarePlugin(engine, first, second))
        bumpTelemetry(engine, TELEMETRY_CROSS_FLARE)
    }

    /** 建一枚钉住生命周期的 SHARP 光斑（全局计时器钉超长 full，扩散/消散由插件接管）。 */
    private fun buildFlare(point: Vector2f, size: Float, facingDeg: Float): FlareEntity? {
        // FlareEntity 构造在无 GL 环境（单测/无头）会抛异常，收住并降级为无辉星（星云/闪光不受影响）
        val entity = try {
            FlareEntity()
        } catch (t: Throwable) {
            log.warn("摧锋十字辉星建实体失败（${t.javaClass.simpleName}），本次跳过辉星", t)
            return null
        }
        entity.setLayer(CombatEngineLayers.ABOVE_PARTICLES)
        entity.setAdditiveBlend()
        entity.setSharpDisc()
        entity.isFlick = false
        entity.isSyncFlick = false
        entity.glowPower = 0.1f
        entity.noisePower = 0.05f
        // 用 Color 重载：BoxUtil 的 setCoreColor(float×4) 有源码 bug（误写 fringe 槽位，BoxFlareComponent 注记）
        entity.setCoreColor(FLARE_CORE_COLOR)
        entity.setFringeColor(FLARE_FRINGE_COLOR)
        entity.setSize(size, size / 4)
        entity.autoAspect()
        entity.setGlobalTimer(0f, 1e7f, 0f)
        entity.setStateVanilla(Vector2f(point), facingDeg)
        val engine = Global.getCombatEngine() ?: run {
            entity.delete()
            return null
        }
        val state = BoxUtilCombatVfx.addEntity(engine, entity)
        if (state != 0) {
            log.warn("摧锋十字辉星注册失败（addEntity 返回 $state），本次跳过辉星（星云/闪光不受影响）")
            entity.delete()
            return null
        }
        return entity
    }

    /** 爆炸星云。 */
    private fun spawnNebulaBurst(engine: CombatEngineAPI, point: Vector2f, random: Random) {
        repeat(NEBULA_COUNT) {
            val angle = random.nextFloat() * 360f
            val speed = NEBULA_SPEED_MIN + random.nextFloat() * (NEBULA_SPEED_MAX - NEBULA_SPEED_MIN)
            val size = NEBULA_SIZE_MIN + random.nextFloat() * (NEBULA_SIZE_MAX - NEBULA_SIZE_MIN)
            val vel = Vector2f(
                (cos(Math.toRadians(angle.toDouble())) * speed).toFloat(),
                (sin(Math.toRadians(angle.toDouble())) * speed).toFloat(),
            )
            engine.addNebulaParticle(
                Vector2f(point), vel, size, 1.9f, 0.25f, 0.55f,
                NEBULA_DURATION_AVG + (random.nextFloat() - 0.5f) * 0.6f, NEBULA_COLOR,
            )
        }
        engine.spawnExplosion(point, ZERO_VEL, NEBULA_COLOR, 100f, 1f)
        bumpTelemetry(engine, TELEMETRY_NEBULA_BURST)
    }

    /** 十字辉星推进插件：1s 内尺寸 100→200px 线性扩散、透明度线性归零，到期删实体自注销。 */
    private class CrossFlarePlugin(
        private val engine: CombatEngineAPI,
        private val first: FlareEntity,
        private val second: FlareEntity,
    ) : BaseEveryFrameCombatPlugin() {
        private var elapsed = 0f

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (engine.isPaused) return
            elapsed += amount
            val t = (elapsed / CROSS_FLARE_DURATION).coerceIn(0f, 1f)
            val size = FLARE_SIZE_START + (FLARE_SIZE_END - FLARE_SIZE_START) * t
            val alpha = 1f - t
            first.setSize(size, first.height)
            first.globalAlpha = alpha
            second.setSize(size, second.height)
            second.globalAlpha = alpha
            if (t >= 1f) {
                first.delete()
                second.delete()
                engine.removePlugin(this)
            }
        }
    }

    /** dev 自动化烟测证据计数（对齐贯星 VFX 遥测先例）：engine.customData 整数自增。 */
    private fun bumpTelemetry(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }

    // ---- dev 自动化烟测遥测键（engine.customData）----
    const val TELEMETRY_CROSS_FLARE = "astd_cuifeng_cross_flare"
    const val TELEMETRY_NEBULA_BURST = "astd_cuifeng_nebula_burst"

    /** 读整数遥测计数（无记录为 0）。 */
    fun telemetryCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

    /** 顶点闪光尺寸（su）。 */
    private const val FLASH_SIZE = 80f

    /** 星云参数。 */
    private const val NEBULA_COUNT = 8
    private const val NEBULA_SIZE_MIN = 50f
    private const val NEBULA_SIZE_MAX = 100f
    private const val NEBULA_SPEED_MIN = 10f
    private const val NEBULA_SPEED_MAX = 20f
    private const val NEBULA_DURATION_AVG = 1.0f
}
