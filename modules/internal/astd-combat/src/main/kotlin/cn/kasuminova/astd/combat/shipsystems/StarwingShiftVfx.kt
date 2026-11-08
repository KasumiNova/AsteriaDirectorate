package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.api.render.BloomFlareVfx
import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.BloomFlareSpec
import cn.kasuminova.astd.impl.render.BoxFlareStyle
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import cn.kasuminova.astd.renderer.effect.explosion.BloomFlareVfxImpl
import cn.kasuminova.astd.renderer.effect.system.ASTDAfterimageEffect
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** 星翼折跃的视觉：起跳十字光斑、位移残影拖尾、落点双冲击波与扭曲。 */
object StarwingShiftVfx {

    private val log = AstdLog.logger

    private val bloomFlare: BloomFlareVfx = BloomFlareVfxImpl

    private val FLARE_CORE = ASTDColor(0xFFE1F2FF)
    private val FLARE_FRINGE = ASTDColor(0xFF8CCDFF)
    private val TRAIL_COLOR = Color(120, 190, 255)
    private val NEBULA_COLOR = Color(140, 200, 255, 110)
    private val SHOCKWAVE_COLOR = Color(150, 205, 255, 110)
    private val SHOCKWAVE_EMISSIVE = Color(170, 215, 255, 100)

    private val SHOCKWAVE_POOL = PooledCombatVfx.SpritePoolKey(
        spritePath = "graphics/fx/astd_shockwave.png",
        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
    )
    private val SHOCKWAVE_RING_POOL = PooledCombatVfx.SpritePoolKey(
        spritePath = "graphics/fx/astd_shockwave_ring.png",
        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
    )
    private val SHARD_POOL = PooledCombatVfx.SpritePoolKey(
        spritePath = "graphics/fx/astd_shard_tri.png",
        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
    )

    private const val CROSS_SIZE_START = 280f
    private const val CROSS_SIZE_END = 600f
    private const val CROSS_HEIGHT = 36f
    private const val CROSS_FADE_IN = 0.05f
    private const val CROSS_FADE_OUT = 0.25f

    private const val TRAIL_SPACING = 30f
    private const val TRAIL_MAX_STEPS = 24
    private const val TRAIL_ALPHA = 0.55f
    private const val TRAIL_DURATION = 0.45f

    private const val SHOCK_FAST_SCALE_START = 40f
    private const val SHOCK_FAST_SCALE_RATE = 900f
    private const val SHOCK_RING_SCALE_START = 30f
    private const val SHOCK_RING_SCALE_RATE = 320f

    private const val SHARD_COUNT = 46
    private const val SHARD_SPEED_MIN = 90f
    private const val SHARD_SPEED_MAX = 260f
    private const val SHARD_SIDE_MIN = 10f
    private const val SHARD_SIDE_MAX = 24f
    private const val SHARD_SKEW_LO = 0.7f
    private const val SHARD_SKEW_HI = 1.3f
    private const val SHARD_CORE_RATIO = 0.25f
    private const val SHARD_ALPHA_LO = 120
    private const val SHARD_ALPHA_HI = 180
    private const val SHARD_EMISSIVE_ALPHA_MUL = 0.4f
    private const val SHARD_SPIN_MIN = 140f
    private const val SHARD_SPIN_MAX = 420f
    private const val SHARD_FULL_LO = 0.16f
    private const val SHARD_FULL_HI = 0.30f

    private val SHARD_CORE_COLOR = Color(0xE1, 0xF2, 0xFF)
    private val SHARD_FRINGE_COLOR = Color(0x8C, 0xCD, 0xFF)

    private const val NEBULA_COUNT = 8

    private const val DISTORTION_FADE_IN = 0.10f
    private const val DISTORTION_FULL = 0.25f
    private const val DISTORTION_FADE_OUT = 0.55f
    private const val DISTORTION_LIFE = DISTORTION_FADE_IN + DISTORTION_FULL + DISTORTION_FADE_OUT

    /** 起跳：在原位生成缓慢扩散的十字光斑，存续到位移结束。 */
    fun activate(engine: CombatEngineAPI, at: Vector2f, facingDeg: Float, durationSeconds: Float) {
        val spawned = bloomFlare.spawn(
            engine, at, durationSeconds,
            listOf(crossSpec(facingDeg), crossSpec(facingDeg + 90f)),
            timerFadeIn = CROSS_FADE_IN, timerFull = durationSeconds, timerFadeOut = CROSS_FADE_OUT,
        )
        if (spawned == 0) log.warn("[ASTD] 星翼折跃十字光斑缺席（辉星实体注册失败）")
    }

    private fun crossSpec(facingDeg: Float) = BloomFlareSpec(
        style = BoxFlareStyle.SMOOTH_DISC,
        sizeStart = CROSS_SIZE_START,
        sizeEnd = CROSS_SIZE_END,
        heightStart = CROSS_HEIGHT,
        heightEnd = CROSS_HEIGHT,
        facingDeg = facingDeg,
        coreColor = FLARE_CORE,
        fringeColor = FLARE_FRINGE,
        glowPower = 0.1f,
        noisePower = 0.05f,
    )

    /** 位移中沿 [from] 到 [to] 的路径按固定间距补残影，高速帧也不留空隙。 */
    fun trail(engine: CombatEngineAPI, ship: ShipAPI, from: Vector2f, to: Vector2f) {
        val length = MathUtils.getDistance(from, to)
        if (length < 1f) return
        val steps = ceil(length / TRAIL_SPACING).toInt().coerceIn(1, TRAIL_MAX_STEPS)
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            val point = Vector2f(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
            ASTDAfterimageEffect.spawnShip(engine, ship, point, TRAIL_COLOR, TRAIL_ALPHA, TRAIL_DURATION)
        }
    }

    /** 落点：快慢双冲击波、三角碎片、扭曲震荡与蓝色星云。全部锚定 [ship] 随舰体一起移动。 */
    fun impact(engine: CombatEngineAPI, ship: ShipAPI) {
        val at = ship.location
        spawnShockwaves(engine, ship, at)
        spawnShards(engine, ship, at)
        spawnDistortion(engine, ship)
        spawnNebula(engine, ship, at)
    }

    private fun spawnShockwaves(engine: CombatEngineAPI, ship: ShipAPI, at: Vector2f) {
        val fast = PooledCombatVfx.spawnSprite(
            engine, SHOCKWAVE_POOL,
            at.x, at.y,
            scaleX = SHOCK_FAST_SCALE_START, scaleY = SHOCK_FAST_SCALE_START,
            scaleRateX = SHOCK_FAST_SCALE_RATE, scaleRateY = SHOCK_FAST_SCALE_RATE,
            color = SHOCKWAVE_COLOR, emissiveColor = SHOCKWAVE_EMISSIVE,
            fadeIn = 0.03f, full = 0.05f, fadeOut = 0.35f,
            anchor = ship,
        )
        val ring = PooledCombatVfx.spawnSprite(
            engine, SHOCKWAVE_RING_POOL,
            at.x, at.y,
            scaleX = SHOCK_RING_SCALE_START, scaleY = SHOCK_RING_SCALE_START,
            scaleRateX = SHOCK_RING_SCALE_RATE, scaleRateY = SHOCK_RING_SCALE_RATE,
            color = SHOCKWAVE_COLOR, emissiveColor = SHOCKWAVE_EMISSIVE,
            fadeIn = 0.05f, full = 0.22f, fadeOut = 0.70f,
            anchor = ship,
        )
        if (!fast || !ring) log.warn("[ASTD] 星翼折跃冲击波部分缺席（fast=$fast ring=$ring）")
    }

    private fun spawnShards(engine: CombatEngineAPI, ship: ShipAPI, at: Vector2f) {
        var spawned = 0
        repeat(SHARD_COUNT) {
            val angle = MathUtils.getRandomNumberInRange(0f, 360f)
            val speed = MathUtils.getRandomNumberInRange(SHARD_SPEED_MIN, SHARD_SPEED_MAX)
            val side = MathUtils.getRandomNumberInRange(SHARD_SIDE_MIN, SHARD_SIDE_MAX)
            val skew = MathUtils.getRandomNumberInRange(SHARD_SKEW_LO, SHARD_SKEW_HI)
            val base = if (MathUtils.getRandomNumberInRange(0f, 1f) < SHARD_CORE_RATIO) SHARD_CORE_COLOR else SHARD_FRINGE_COLOR
            val alpha = MathUtils.getRandomNumberInRange(SHARD_ALPHA_LO, SHARD_ALPHA_HI).toInt()
            val spinMag = MathUtils.getRandomNumberInRange(SHARD_SPIN_MIN, SHARD_SPIN_MAX)
            val spin = if (MathUtils.getRandomNumberInRange(0f, 1f) < 0.5f) -spinMag else spinMag
            val accepted = PooledCombatVfx.spawnSprite(
                engine, SHARD_POOL,
                at.x, at.y,
                velX = (cos(Math.toRadians(angle.toDouble())) * speed).toFloat(),
                velY = (sin(Math.toRadians(angle.toDouble())) * speed).toFloat(),
                facingDeg = MathUtils.getRandomNumberInRange(0f, 360f),
                turnRateDeg = spin,
                // Instance2Data 的 scale 是半尺寸（边长的一半）。
                scaleX = side * 0.5f,
                scaleY = side * 0.5f * skew,
                color = Color(base.red, base.green, base.blue, alpha),
                emissiveColor = Color(base.red, base.green, base.blue, (alpha * SHARD_EMISSIVE_ALPHA_MUL).toInt()),
                fadeIn = 0.02f,
                full = MathUtils.getRandomNumberInRange(SHARD_FULL_LO, SHARD_FULL_HI),
                fadeOut = 0.32f,
                anchor = ship,
            )
            if (accepted) spawned++
        }
        if (spawned < SHARD_COUNT) {
            log.warn("[ASTD] 星翼折跃落点碎片灌入粒子池部分缺席（$spawned/$SHARD_COUNT）")
        }
    }

    private fun spawnDistortion(engine: CombatEngineAPI, ship: ShipAPI) {
        BoxUtilCombatVfx.ensureReady(engine)
        val distortion = DistortionEntity()
        distortion.setGlobalTimer(DISTORTION_FADE_IN, DISTORTION_FULL, DISTORTION_FADE_OUT)
        distortion.setSizeIn(80f, 80f)
        distortion.setSizeFull(280f, 280f)
        distortion.setSizeOut(720f, 720f)
        distortion.setInnerIn(0.25f, 0.25f)
        distortion.setInnerFull(0.25f, 0.25f)
        distortion.setInnerOut(0.60f, 0.60f)
        distortion.innerHardness = 0.80f
        distortion.ringHardness = 0.60f
        distortion.powerIn = 0.70f
        distortion.powerFull = 0.45f
        distortion.powerOut = 0f
        distortion.setLocation(Vector2f(ship.location))
        val addState = BoxUtilCombatVfx.addEntity(engine, distortion)
        if (addState != 0) {
            log.warn("[ASTD] 星翼折跃扭曲实体注册失败（addEntity 返回 $addState），本次扭曲视觉缺席")
            distortion.delete()
            return
        }
        engine.addPlugin(DistortionFollowPlugin(engine, ship, distortion))
    }

    /** 扭曲震荡本身没有锚点能力，逐帧把实体位置钉到舰体上，寿命走完或实体被回收即退出。 */
    private class DistortionFollowPlugin(
        private val engine: CombatEngineAPI,
        private val ship: ShipAPI,
        private val distortion: DistortionEntity,
    ) : BaseEveryFrameCombatPlugin() {

        private var elapsed = 0f

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (engine.isPaused) return
            if (distortion.hasDelete() || ship.isHulk || !ship.isAlive || elapsed >= DISTORTION_LIFE) {
                engine.removePlugin(this)
                return
            }
            elapsed += amount.coerceAtLeast(0f)
            distortion.setLocation(Vector2f(ship.location))
        }
    }

    private fun spawnNebula(engine: CombatEngineAPI, ship: ShipAPI, at: Vector2f) {
        BoxUtilCombatVfx.resetNebulaControllerIfIdle(engine)
        var spawned = 0
        repeat(NEBULA_COUNT) {
            val angle = Math.toRadians(MathUtils.getRandomNumberInRange(0f, 360f).toDouble())
            val radius = MathUtils.getRandomNumberInRange(0f, 80f)
            val speed = MathUtils.getRandomNumberInRange(20f, 60f)
            val pos = Vector2f(
                at.x + (cos(angle) * radius).toFloat(),
                at.y + (sin(angle) * radius).toFloat(),
            )
            val vel = Vector2f((cos(angle) * speed).toFloat(), (sin(angle) * speed).toFloat())
            val accepted = BoxUtilCombatVfx.addNebulaParticle(
                engine, pos, vel,
                MathUtils.getRandomNumberInRange(80f, 160f),
                1.6f, 0.12f, 0.3f,
                MathUtils.getRandomNumberInRange(0.8f, 1.4f),
                NEBULA_COLOR,
            )
            if (accepted) spawned++
        }
        if (spawned < NEBULA_COUNT) {
            log.warn("[ASTD] 星翼折跃落点星云部分缺席（$spawned/$NEBULA_COUNT）")
        }
    }
}
