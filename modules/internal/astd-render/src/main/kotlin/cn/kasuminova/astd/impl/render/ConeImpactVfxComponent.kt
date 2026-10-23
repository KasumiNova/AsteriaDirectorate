package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.impl.render.ConeImpactVfxComponent.Companion.SPRAY_ARC_MAX
import cn.kasuminova.astd.impl.render.ConeImpactVfxComponent.Companion.SPRAY_RAYS_PER_10DEG_MAX
import cn.kasuminova.astd.impl.render.ConeImpactVfxComponent.Companion.SPRAY_RAYS_PER_10DEG_MIN
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.roundToInt

class ConeImpactVfxComponent(
    id: String,
    private val origin: Vector2f,
    private val facingDeg: Float,
    private val halfAngleDeg: Float,
    private val length: Float,
    private val coreColor: Color,
    private val fringeColor: Color,
    private val flashColor: Color,
    private val distortion: DistortionSpec? = DistortionSpec(),
    private val shardCounts: ShardCountSpec = ShardCountSpec(),
) : RenderEntityImpl(id) {

    private val log = Global.getLogger(ConeImpactVfxComponent::class.java)

    private val sprayArcDeg = (halfAngleDeg * 2f * SPRAY_ARC_MUL).coerceAtMost(SPRAY_ARC_MAX)

    internal val sprayComponent = StrikeSprayComponent(
        "$id/spray",
        StrikeSprayVfx.StrikeSpraySpec(
            origin = Vector2f(origin),
            facingDeg = facingDeg,
            arcDeg = sprayArcDeg,
            coreColor = coreColor,
            fringeColor = fringeColor,
            style = StrikeSprayVfx.SprayStyle(
                arc = sprayArcDeg,
                lengthMin = length * SPRAY_LENGTH_MIN_MUL,
                lengthMax = length * SPRAY_LENGTH_MAX_MUL,
                widthMin = SPRAY_WIDTH_MIN,
                widthMax = SPRAY_WIDTH_MAX,
                fullMin = SPRAY_FULL_MIN,
                fullMax = SPRAY_FULL_MAX,
                fadeOutMin = SPRAY_FADE_OUT_MIN,
                fadeOutMax = SPRAY_FADE_OUT_MAX,
                speedMin = SPRAY_SPEED_MIN,
                speedMax = SPRAY_SPEED_MAX,
                impactScale = SPRAY_IMPACT_SCALE,
                introRampSeconds = SPRAY_INTRO_RAMP_SECONDS,
                exactRays = dynamicSprayRays(sprayArcDeg),
            ),
        ),
    )

    internal val shardComponent = TriShardComponent("$id/shards", length, coreColor, fringeColor)

    internal val arcComponent = ConeArcComponent("$id/arcs", origin, facingDeg, halfAngleDeg, length, fringeColor)

    private var shardBatch2Fired = false
    private var shardBatch3Fired = false

    init {
        addChild(sprayComponent)
        addChild(shardComponent)
        addChild(arcComponent)
    }

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        spawnVertexFlash(engine)
        distortion?.let { spawnDistortion(engine, it) }
        spawnVertexShards(shardCounts.vertex)
        return true
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val t = ctx.frame.elapsed
        if (!shardBatch2Fired && t >= SHARD_BATCH2_DELAY) {
            shardBatch2Fired = true
            spawnConeShards(BATCH_INDEX_CONE, shardCounts.cone, SHARD_BATCH2_AXIS_LO, SHARD_BATCH2_AXIS_HI)
        }
        if (!shardBatch3Fired && t >= SHARD_BATCH3_DELAY) {
            shardBatch3Fired = true
            spawnConeShards(BATCH_INDEX_EDGE, shardCounts.edge, SHARD_BATCH3_AXIS_LO, SHARD_BATCH3_AXIS_HI)
        }
    }

    private fun spawnVertexFlash(engine: CombatEngineAPI) {
        val flashSize = (length * FLASH_SIZE_MUL).coerceIn(FLASH_SIZE_MIN, FLASH_SIZE_MAX)
        engine.addSmoothParticle(origin, ZERO_VEL, flashSize, FLASH_BRIGHTNESS, FLASH_DURATION, flashColor)
        val core = Color(
            coreColor.red, coreColor.green, coreColor.blue,
            (coreColor.alpha * FLASH_CORE_ALPHA_MUL).toInt().coerceIn(0, 255),
        )
        engine.addSmoothParticle(origin, ZERO_VEL, flashSize * FLASH_CORE_SIZE_MUL, FLASH_CORE_BRIGHTNESS, FLASH_CORE_DURATION, core)
    }

    private fun spawnDistortion(engine: CombatEngineAPI, spec: DistortionSpec) {
        try {
            BoxUtilCombatVfx.ensureReady(engine)
            val e = DistortionEntity()
            e.setGlobalTimer(spec.fadeIn, spec.full, spec.fadeOut)
            e.setInnerFull(0.30f, 0.30f)
            e.innerHardness = 0.75f
            e.ringHardness = 0.50f
            e.setSizeIn(spec.sizeIn, spec.sizeIn)
            e.setSizeFull(spec.sizeFull, spec.sizeFull)
            e.setSizeOut(spec.sizeOut, spec.sizeOut)
            e.powerIn = 0f
            e.powerFull = spec.powerFull
            e.powerOut = 0f
            e.setLocation(Vector2f(origin))
            val result = BoxUtilCombatVfx.addEntity(engine, e)
            if (result != 0) {
                log.warn("锥面冲击特效 DistortionEntity 注册失败（addEntity 返回 $result，id=$id），扭曲层缺席（闪光/碎片/弧照常）")
            }
        } catch (t: Throwable) {
            log.warn("锥面冲击特效 DistortionEntity 生成异常（id=$id），扭曲层缺席（闪光/碎片/弧照常）", t)
        }
    }

    private fun spawnVertexShards(count: Int) {
        repeat(count) {
            shardComponent.addShard(
                BATCH_INDEX_VERTEX,
                MathUtils.getRandomPointInCircle(origin, SHARD_VERTEX_SCATTER_RADIUS),
                shardVelocity(),
            )
        }
    }

    private fun spawnConeShards(batchIndex: Int, count: Int, axisFracLo: Float, axisFracHi: Float) {
        repeat(count) {
            val dist = length * MathUtils.getRandomNumberInRange(axisFracLo, axisFracHi)
            val posAng = facingDeg + MathUtils.getRandomNumberInRange(-halfAngleDeg * SHARD_CONE_SPREAD_MUL, halfAngleDeg * SHARD_CONE_SPREAD_MUL)
            shardComponent.addShard(
                batchIndex,
                MathUtils.getPointOnCircumference(origin, dist, posAng),
                shardVelocity(),
            )
        }
    }

    private fun shardVelocity(): Vector2f {
        val velAng = facingDeg + MathUtils.getRandomNumberInRange(-halfAngleDeg * SHARD_VEL_SPREAD_MUL, halfAngleDeg * SHARD_VEL_SPREAD_MUL)
        val speed = length * SHARD_SPEED_MUL * MathUtils.getRandomNumberInRange(SHARD_SPEED_JITTER_LO, SHARD_SPEED_JITTER_HI)
        return MathUtils.getPointOnCircumference(ZERO_VEL, speed, velAng)
    }

    companion object {
        private val ZERO_VEL = Vector2f(0f, 0f)

        private const val FLASH_SIZE_MUL = 0.22f
        private const val FLASH_SIZE_MIN = 24f
        private const val FLASH_SIZE_MAX = 180f
        private const val FLASH_BRIGHTNESS = 1.2f
        private const val FLASH_DURATION = 0.16f
        private const val FLASH_CORE_SIZE_MUL = 0.5f
        private const val FLASH_CORE_BRIGHTNESS = 1.5f
        private const val FLASH_CORE_ALPHA_MUL = 0.8f
        private const val FLASH_CORE_DURATION = 0.11f

        private const val SPRAY_RAYS_PER_10DEG_MIN = 2f
        private const val SPRAY_RAYS_PER_10DEG_MAX = 3f
        private const val SPRAY_RAYS_CLAMP_MIN = 3
        private const val SPRAY_RAYS_CLAMP_MAX = 40

        private const val SPRAY_ARC_MUL = 0.8f
        private const val SPRAY_ARC_MAX = 65f

        private const val SPRAY_LENGTH_MIN_MUL = 0.30f
        private const val SPRAY_LENGTH_MAX_MUL = 0.70f
        private const val SPRAY_WIDTH_MIN = 7.5f
        private const val SPRAY_WIDTH_MAX = 15f
        private const val SPRAY_FULL_MIN = 0.05f
        private const val SPRAY_FULL_MAX = 0.10f
        private const val SPRAY_FADE_OUT_MIN = 0.30f
        private const val SPRAY_FADE_OUT_MAX = 0.52f
        private const val SPRAY_SPEED_MIN = 110f
        private const val SPRAY_SPEED_MAX = 260f
        private const val SPRAY_IMPACT_SCALE = 0.85f
        private const val SPRAY_INTRO_RAMP_SECONDS = 0.05f

        private const val BATCH_INDEX_VERTEX = 0
        private const val BATCH_INDEX_CONE = 1
        private const val BATCH_INDEX_EDGE = 2

        private const val SHARD_BATCH2_DELAY = 0.05f
        private const val SHARD_BATCH3_DELAY = 0.10f
        private const val SHARD_BATCH2_AXIS_LO = 0.2f
        private const val SHARD_BATCH2_AXIS_HI = 0.7f
        private const val SHARD_BATCH3_AXIS_LO = 0.8f
        private const val SHARD_BATCH3_AXIS_HI = 1.0f
        private const val SHARD_VERTEX_SCATTER_RADIUS = 10f

        private const val SHARD_CONE_SPREAD_MUL = 0.7f
        private const val SHARD_VEL_SPREAD_MUL = 0.8f
        private const val SHARD_SPEED_MUL = 0.35f
        private const val SHARD_SPEED_JITTER_LO = 0.6f
        private const val SHARD_SPEED_JITTER_HI = 1.4f

        internal fun dynamicSprayRays(arcDeg: Float): Int =
            (arcDeg / 10f * MathUtils.getRandomNumberInRange(SPRAY_RAYS_PER_10DEG_MIN, SPRAY_RAYS_PER_10DEG_MAX))
                .roundToInt()
                .coerceIn(SPRAY_RAYS_CLAMP_MIN, SPRAY_RAYS_CLAMP_MAX)
    }
}
