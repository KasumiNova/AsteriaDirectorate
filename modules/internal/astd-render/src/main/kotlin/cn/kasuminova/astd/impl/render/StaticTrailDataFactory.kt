package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.impl.render.StaticTrailDataFactory.FADE_IN_RATIO
import cn.kasuminova.astd.impl.render.StaticTrailDataFactory.FADE_OUT_RATIO
import cn.kasuminova.astd.impl.render.StaticTrailDataFactory.FULL_RATIO
import com.fs.starfarer.api.Global
import org.boxutil.define.struct.statictrail.StaticTrailData
import org.lwjgl.util.vector.Vector2f
import org.lwjgl.util.vector.Vector4f
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

// StaticTrailData 是带专属 vRAM 环形池的常量配置，必须按树和层缓存复用。
object StaticTrailDataFactory {

    private val log = Global.getLogger(StaticTrailDataFactory::class.java)
    private val cache = ConcurrentHashMap<String, StaticTrailData>()

    internal const val FADE_IN_RATIO = 0.12f
    internal const val FULL_RATIO = 0.48f
    internal const val FADE_OUT_RATIO = 0.4f

    internal const val MIN_TOTAL_DURATION = 0.15f
    internal const val MAX_TOTAL_DURATION = 10f

    /** 逐实例缩放量化步长。0.5~2.5 落到 41 档，档数与池数量有界。 */
    internal const val SCALE_BUCKET_STEP = 0.05f

    private const val INIT_CAPACITY: Short = 256
    private const val SCALED_INIT_CAPACITY: Short = 32
    private const val LENGTH_BUCKET_SU = 25f
    private const val SPEED_BUCKET_SU = 25f

    fun trailData(
        treeId: String,
        layerName: String,
        spec: StaticTrailSpec,
        projectileSpeedSuPerSec: Float,
    ): StaticTrailData = trailData(treeId, layerName, spec, projectileSpeedSuPerSec, null)

    /**
     * [visualScale] 为 null 时该弹体未启用缩放。
     *
     * [projectileSpeedSuPerSec] 是寿命折算基准速度（主炮面板速度或弹体 spec 速度），不随实例速度加成变化。
     * 缩放启用时带长与速度同乘缩放值，总寿命与缩放档无关。
     */
    fun trailData(
        treeId: String,
        layerName: String,
        spec: StaticTrailSpec,
        projectileSpeedSuPerSec: Float,
        visualScale: Float?,
    ): StaticTrailData {
        val sampling = resolveTrailSampling(treeId, layerName, spec.bandLength, projectileSpeedSuPerSec, visualScale)
        return cache.getOrPut(sampling.cacheKey) { build(treeId, layerName, spec, sampling) }
    }

    internal class TrailSampling(
        val cacheKey: String,
        /** 传给 [StaticTrailData] 的池标识。BoxUtil 以 [StaticTrailData] 为池键，其 equals 只比较 id。 */
        val dataId: String,
        val bandLength: Float,
        val speedSuPerSec: Float,
        val scale: Float,
        val initCapacity: Short,
    )

    internal fun resolveTrailSampling(
        treeId: String,
        layerName: String,
        baseBandLength: Float,
        baseSpeedSuPerSec: Float,
        visualScale: Float?,
    ): TrailSampling {
        // 带长与时长一律取桶值：缓存键只含桶，配置必须由桶唯一决定，不得沿用首个调用方的原始带长
        val lengthBucket = (baseBandLength / LENGTH_BUCKET_SU).roundToInt() * LENGTH_BUCKET_SU
        val speedBucket = (baseSpeedSuPerSec / SPEED_BUCKET_SU).roundToInt() * SPEED_BUCKET_SU
        if (visualScale == null) {
            return TrailSampling(
                cacheKey = "$treeId/$layerName@$lengthBucket",
                dataId = "$treeId/$layerName",
                bandLength = lengthBucket,
                speedSuPerSec = baseSpeedSuPerSec,
                scale = 1f,
                initCapacity = INIT_CAPACITY,
            )
        }
        val scaleBucket = scaleBucketOf(visualScale)
        val scale = scaleBucket * SCALE_BUCKET_STEP
        return TrailSampling(
            cacheKey = "${treeId}/$layerName@${lengthBucket}_${speedBucket}_$scaleBucket",
            dataId = "$treeId/$layerName@${lengthBucket}_${speedBucket}_$scaleBucket",
            bandLength = lengthBucket * scale,
            speedSuPerSec = speedBucket * scale,
            scale = scale,
            initCapacity = SCALED_INIT_CAPACITY,
        )
    }

    internal fun scaleBucketOf(visualScale: Float): Int = (visualScale / SCALE_BUCKET_STEP).roundToInt()

    private fun build(
        treeId: String,
        layerName: String,
        spec: StaticTrailSpec,
        sampling: TrailSampling,
    ): StaticTrailData {
        val scale = sampling.scale
        val total = totalDurationSeconds(sampling.bandLength, sampling.speedSuPerSec)
        val data = StaticTrailData(sampling.dataId, sampling.initCapacity)
            .setDurFadeIn(total * FADE_IN_RATIO)
            .setDurFull(total * FULL_RATIO)
            .setDurFadeOut(total * FADE_OUT_RATIO)
            .setSizeIn(spec.width * scale)
            .setSizeOut(spec.width * spec.tailWidthRatio * scale)
            .setTexturePixels(spec.tileLength * scale)
            .setTextureSpeed(spec.scrollSpeed * scale)
            .setColorIn(spec.headColor.toVector4f())
            .setColorOut(spec.tailColor.toVector4f())
            .setAdditiveBlend(true)
        spec.angularInRange?.let { data.setAngularInRange(Vector2f(it.start, it.endInclusive)) }
        spec.angularOutRange?.let { data.setAngularOutRange(Vector2f(it.start, it.endInclusive)) }
        spec.velocityInRange?.let { data.setVelocityInRange(Vector4f(it.minX, it.minY, it.maxX, it.maxY)) }
        spec.velocityOutRange?.let { data.setVelocityOutRange(Vector4f(it.minX, it.minY, it.maxX, it.maxY)) }
        val sprite = Global.getSettings().getSprite(spec.texturePath)
        if (sprite.textureId <= 0) {
            // 贴图未上传（多因未在 settings.json graphics 段注册）：BoxUtil 直接按 id 绑定，不触发原版懒加载
            log.warn("[ASTD] static trail texture not uploaded: id=$treeId/$layerName tex=${spec.texturePath}，拖尾将不可见，请检查 settings.json graphics 注册")
        }
        data.material.setDiffuse(sprite)
        if (spec.glowPower > 0f) {
            // emissive 复用同一贴图（形在 alpha）：发光颜色由头部色染色，强度走 glowPower → bloom G-buffer
            data.material.setEmissive(sprite)
            data.material.setEmissiveColor(spec.headColor.toVector4f())
            data.material.glowPower = spec.glowPower
        }
        log.info(
            "[ASTD] static trail registered: id=${sampling.dataId} total=${total}s " +
                "speed=${sampling.speedSuPerSec} scale=$scale tex=${spec.texturePath}",
        )
        return data
    }

    internal fun totalDurationSeconds(bandLength: Float, speedSuPerSec: Float): Float =
        (bandLength.coerceAtLeast(0f) / speedSuPerSec.coerceAtLeast(MIN_SPEED_SU_PER_SEC))
            .coerceIn(MIN_TOTAL_DURATION, MAX_TOTAL_DURATION)

    private const val MIN_SPEED_SU_PER_SEC = 1f
}

private fun ASTDColor.toVector4f(): Vector4f = Vector4f(
    red.coerceIn(0f, 1f),
    green.coerceIn(0f, 1f),
    blue.coerceIn(0f, 1f),
    alpha.coerceIn(0f, 1f),
)
