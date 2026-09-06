package cn.kasuminova.astd.impl.render

import com.fs.starfarer.api.Global
import org.boxutil.define.struct.statictrail.StaticTrailData
import org.lwjgl.util.vector.Vector2f
import org.lwjgl.util.vector.Vector4f
import java.util.concurrent.ConcurrentHashMap

/**
 * [StaticTrailSpec] → BoxUtil [StaticTrailData] 的翻译与缓存。
 *
 * StaticTrailData 是"一种拖尾风格 + 一块专属 vRAM 环形池"的常量配置（池以 id 为键，配置须 const），
 * 故按 `树 id/层名` 缓存复用——调试期 DSL 字面量热交换对拖尾层不生效（需重启），附加层不受影响。
 *
 * 时长语义折算：DSL 声明预期带长（世界单位），Static Trail 只接受节点总寿命（秒）——
 * 总寿命 = 带长 / 弹体速度，再按 [FADE_IN_RATIO]/[FULL_RATIO]/[FADE_OUT_RATIO] 切三段
 * （对齐旧逐节点寿命观感：淡入 5%、满亮至 60%、线性消散到尾）。
 */
object StaticTrailDataFactory {

    private val log = Global.getLogger(StaticTrailDataFactory::class.java)
    private val cache = ConcurrentHashMap<String, StaticTrailData>()

    /** 三段时间占节点总寿命的比例（对齐旧 dissolveStart=0.6 观感：满亮后线性消散）。
     * 淡入 12%（原 5%）：带体亮度在弹头后方渐起，避免带体亮头与原版螺栓弹头（additive 高亮）同位叠加出彗星状过曝团。 */
    internal const val FADE_IN_RATIO = 0.12f
    internal const val FULL_RATIO = 0.48f
    internal const val FADE_OUT_RATIO = 0.4f

    /** 节点总寿命下限（秒）：BoxUtil 要求三段总和 ≥ 0.1，留余量防边界拒绝。 */
    internal const val MIN_TOTAL_DURATION = 0.15f

    /** 节点总寿命上限（秒）：vRAM 预算 = 时长 × 采样率，长寿拖尾显存线性膨胀。 */
    internal const val MAX_TOTAL_DURATION = 10f

    /** vRAM 池初始容量（同风格并发拖尾条数；高射速武器同走廊多发取足量，池不足会自扩容）。 */
    private const val INIT_CAPACITY: Short = 256

    /**
     * 取（或建）一条拖尾层的 StaticTrailData。
     * @param projectileSpeedSuPerSec 弹体速度（su/s，取 `DamagingProjectileAPI.getMoveSpeed`），用于带长→寿命折算。
     */
    fun trailData(treeId: String, layerName: String, spec: StaticTrailSpec, projectileSpeedSuPerSec: Float): StaticTrailData =
        cache.getOrPut("$treeId/$layerName") { build(treeId, layerName, spec, projectileSpeedSuPerSec) }

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
            data.material.setGlowPower(spec.glowPower)
        }
        log.info("[ASTD] static trail registered: id=$treeId/$layerName total=${total}s speed=$projectileSpeedSuPerSec tex=${spec.texturePath}")
        return data
    }

    /** 带长→节点总寿命折算（秒）：带长 / 弹体速度，钳到 [MIN_TOTAL_DURATION, MAX_TOTAL_DURATION]。 */
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
