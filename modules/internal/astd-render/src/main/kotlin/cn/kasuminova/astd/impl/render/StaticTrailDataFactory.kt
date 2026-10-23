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

    private const val INIT_CAPACITY: Short = 256
    private const val LENGTH_BUCKET_SU = 25f

    fun trailData(treeId: String, layerName: String, spec: StaticTrailSpec, projectileSpeedSuPerSec: Float): StaticTrailData =
        cache.getOrPut("$treeId/$layerName@${(spec.bandLength / LENGTH_BUCKET_SU).roundToInt() * LENGTH_BUCKET_SU}") {
            build(treeId, layerName, spec, projectileSpeedSuPerSec)
        }

    private fun build(treeId: String, layerName: String, spec: StaticTrailSpec, projectileSpeedSuPerSec: Float): StaticTrailData {
        val total = totalDurationSeconds(spec.bandLength, projectileSpeedSuPerSec)
        val data = StaticTrailData("$treeId/$layerName", INIT_CAPACITY)
            .setDurFadeIn(total * FADE_IN_RATIO)
            .setDurFull(total * FULL_RATIO)
            .setDurFadeOut(total * FADE_OUT_RATIO)
            .setSizeIn(spec.width)
            .setSizeOut(spec.width * spec.tailWidthRatio)
            .setTexturePixels(spec.tileLength)
            .setTextureSpeed(spec.scrollSpeed)
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
        log.info("[ASTD] static trail registered: id=$treeId/$layerName total=${total}s speed=$projectileSpeedSuPerSec tex=${spec.texturePath}")
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
