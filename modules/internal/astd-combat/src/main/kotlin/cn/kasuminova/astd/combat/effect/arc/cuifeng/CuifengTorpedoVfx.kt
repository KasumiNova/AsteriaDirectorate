package cn.kasuminova.astd.combat.effect.arc.cuifeng

import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.BloomFlareSpec
import cn.kasuminova.astd.impl.render.BoxFlareStyle
import cn.kasuminova.astd.api.render.BloomFlareVfx
import cn.kasuminova.astd.renderer.effect.explosion.BloomFlareVfxImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 摧锋鱼雷命中特效触发层（blue/30-superlative.md §特效）：
 * 十字辉星 ×2（[BloomFlareVfx] 绽放辉星，SMOOTH_DISC 随导弹命中朝向 90° 交叉，
 * 0.5s 内长轴扩散并渐隐，短轴固定不收窄）+ 爆炸星云（ARC 蓝白主色）。
 */
object CuifengTorpedoVfx {

    /** 绽放辉星通用 API（接口持有实现，AGENTS.md 面向接口口径）。 */
    private val bloomFlare: BloomFlareVfx = BloomFlareVfxImpl

    /** 十字辉星存续。 */
    private const val CROSS_FLARE_DURATION = 0.5f

    /** 十字辉星长轴起止尺寸（px）；短轴固定为长轴起值的 1/8。 */
    private const val FLARE_SIZE_START = 200f
    private const val FLARE_SIZE_END = 600f
    private const val FLARE_HEIGHT = FLARE_SIZE_START / 8f

    /** 辉星核心色（ARC 冷蓝白近白）。 */
    private val FLARE_CORE_COLOR = ASTDColor(0xFFE1F2FF)

    /** 辉星辉光色（ARC 蓝）。 */
    private val FLARE_FRINGE_COLOR = ASTDColor(0xFF8CCDFF)

    /** 星云色（ARC 蓝白，alpha 由 addNebulaParticle 亮度参数调制）。 */
    private val NEBULA_COLOR = java.awt.Color(140, 200, 255, 130)

    /** 爆炸闪光色（星云同色系的顶点补光）。 */
    private val FLASH_COLOR = java.awt.Color(160, 210, 255, 90)

    /** 静止速度矢量（闪光/星云用，避免逐次分配）。 */
    private val ZERO_VEL = Vector2f(0f, 0f)

    /** 触发一次命中特效：顶点闪光 → 十字辉星 ×2（随 [hitAngleDeg] 旋转）→ 爆炸星云 ×10（同帧）。 */
    fun spawnImpact(engine: CombatEngineAPI, point: Vector2f, hitAngleDeg: Float) =
        spawnImpact(engine, point, hitAngleDeg, Random.Default)

    /** 可注入随机源的入口（单元测试与运行共用同一路径）。 */
    fun spawnImpact(engine: CombatEngineAPI, point: Vector2f, hitAngleDeg: Float, random: Random) {
//        engine.addHitParticle(point, ZERO_VEL, FLASH_SIZE, 1.1f, 0.12f, FLARE_CORE_COLOR)
//        engine.addSmoothParticle(point, ZERO_VEL, FLASH_SIZE * 1.6f, 0.8f, 0.2f, FLASH_COLOR)
        spawnCrossFlare(engine, point, hitAngleDeg)
        spawnNebulaBurst(engine, point, random)
    }

    /** 十字辉星：两枚 SMOOTH_DISC 光斑随命中角 90° 交叉同位叠放，由绽放辉星 API 推进扩散消散。 */
    private fun spawnCrossFlare(engine: CombatEngineAPI, point: Vector2f, hitAngleDeg: Float) {
        val spawned = bloomFlare.spawn(
            engine, point, CROSS_FLARE_DURATION,
            listOf(
                crossFlareSpec(hitAngleDeg),
                crossFlareSpec(hitAngleDeg + 90f),
            ),
        )
        if (spawned > 0) bumpTelemetry(engine, TELEMETRY_CROSS_FLARE)
    }

    /** 单枚十字光柱规格：长轴 200→600 扩散，短轴固定 25，低 glow + 轻噪点。 */
    private fun crossFlareSpec(facingDeg: Float) = BloomFlareSpec(
        style = BoxFlareStyle.SMOOTH_DISC,
        sizeStart = FLARE_SIZE_START,
        sizeEnd = FLARE_SIZE_END,
        heightStart = FLARE_HEIGHT,
        heightEnd = FLARE_HEIGHT,
        facingDeg = facingDeg,
        coreColor = FLARE_CORE_COLOR,
        fringeColor = FLARE_FRINGE_COLOR,
        glowPower = 0.1f,
        noisePower = 0.05f,
    )

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
    private const val NEBULA_COUNT = 12
    private const val NEBULA_SIZE_MIN = 75f
    private const val NEBULA_SIZE_MAX = 150f
    private const val NEBULA_SPEED_MIN = 20f
    private const val NEBULA_SPEED_MAX = 40f
    private const val NEBULA_DURATION_AVG = 1.0f
}
