package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ViewportAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * 锥面冲击特效的「三角碎片」组件（计划 00-锥面冲击特效重做计划 §10.7 v2.2）：
 * 一簇随机旋转的三角形碎片替代 v2 的星云烟雾——用户实机反馈「飞出的小星云全部换成
 * 随机旋转的三角形碎片就够了」。
 *
 * 职责（生成模型由根组件 [ConeImpactVfxComponent] 保留：批次数/轴向角向散布/速度模型不变，
 * 本组件只换「生成什么」）：
 * - [addShard]：按锥长与调色派生的随机错参（边长/偏斜/自旋/颜色/alpha/寿命）生成一颗碎片；
 * - advanceSelf：积分位置与自旋角，摘除过寿命碎片，并向渲染插件推快照；
 * - 渲染：自注册引擎分层插件（[ShardPlugin]），GL_TRIANGLES + additive，逐碎片 alpha 随寿命线性淡出。
 *
 * 渲染走引擎分层插件而非树 render()：本树的驱动 [OneShotVfxPlugin] 只有逻辑帧没有渲染帧，
 * 与 [TexTrailComponent] 推顶点流给 [TexTrailRenderer] 同属「组件持有数据、插件负责绘制」的既有惯例。
 */
class ConeShardComponent(
    id: String,
    private val length: Float,
    private val coreColor: Color,
    private val fringeColor: Color,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER, RENDER_ORDER) {

    private val log = Global.getLogger(ConeShardComponent::class.java)

    /** 在册碎片（advance 逐帧积分；internal 供单测直接断言积分与摘除）。 */
    internal val shards = ArrayList<Shard>()

    private var plugin: ShardPlugin? = null

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        val created = ShardPlugin()
        engine.addLayeredRenderingPlugin(created)
        plugin = created
        return true
    }

    /**
     * 加一颗碎片：位置/速度由调用方（根组件的批次散布模型）给定；形状（等边三角 + 两边比 0.7~1.3
     * 随机偏斜）、自旋（初始角 0~360°、角速度 ±180~540°/s）、颜色（1/4 概率 coreColor 提亮、
     * 其余 fringeColor）、alpha（140~200）、寿命（0.45~0.65s）逐颗随机错参。
     */
    fun addShard(pos: Vector2f, vel: Vector2f) {
        val side = (length * SHARD_SIZE_MUL).coerceIn(SHARD_SIZE_MIN, SHARD_SIZE_MAX) *
            MathUtils.getRandomNumberInRange(SHARD_SIZE_JITTER_LO, SHARD_SIZE_JITTER_HI)
        val skew = MathUtils.getRandomNumberInRange(SHARD_SKEW_LO, SHARD_SKEW_HI)
        // 等边带偏斜：从一顶点出发的两边长 side 与 side×skew、夹角 60°；顶点坐标平移到质心居中，自旋不偏心
        val v1x = side
        val v2x = side * skew * 0.5f
        val v2y = side * skew * EQUILATERAL_SIN
        val cx = (v1x + v2x) / 3f
        val cy = v2y / 3f
        val brighten = MathUtils.getRandomNumberInRange(0f, 1f) < SHARD_CORE_RATIO
        val color = if (brighten) coreColor else fringeColor
        val spinMag = MathUtils.getRandomNumberInRange(SHARD_SPIN_MIN, SHARD_SPIN_MAX)
        val spin = if (MathUtils.getRandomNumberInRange(0f, 1f) < 0.5f) -spinMag else spinMag
        shards += Shard(
            x = pos.x,
            y = pos.y,
            vx = vel.x,
            vy = vel.y,
            angleDeg = MathUtils.getRandomNumberInRange(0f, 360f),
            spinDegPerSec = spin,
            local = floatArrayOf(-cx, -cy, v1x - cx, -cy, v2x - cx, v2y - cy),
            red = color.red / 255f,
            green = color.green / 255f,
            blue = color.blue / 255f,
            alpha = MathUtils.getRandomNumberInRange(SHARD_ALPHA_LO.toFloat(), SHARD_ALPHA_HI.toFloat()) / 255f,
            lifetime = MathUtils.getRandomNumberInRange(SHARD_LIFE_LO, SHARD_LIFE_HI),
        )
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val dt = amount.coerceAtLeast(0f)
        val iterator = shards.iterator()
        while (iterator.hasNext()) {
            val shard = iterator.next()
            shard.age += dt
            if (shard.age >= shard.lifetime) {
                iterator.remove()
                continue
            }
            shard.x += shard.vx * dt
            shard.y += shard.vy * dt
            shard.angleDeg += shard.spinDegPerSec * dt
        }
        plugin?.snapshot = shards.toList()
    }

    override fun onDetachSelf() {
        shards.clear()
        plugin?.let {
            it.snapshot = emptyList()
            it.expired = true
        }
        plugin = null
    }

    /** 一颗碎片：世界系位置/速度、自旋角与角速度、质心居中的局部三角形（6 浮点）、颜色与寿命。 */
    internal class Shard(
        var x: Float,
        var y: Float,
        val vx: Float,
        val vy: Float,
        var angleDeg: Float,
        val spinDegPerSec: Float,
        val local: FloatArray,
        val red: Float,
        val green: Float,
        val blue: Float,
        val alpha: Float,
        val lifetime: Float,
        var age: Float = 0f,
    )

    /**
     * 碎片绘制插件（每组件一枚，attach 注册、detach 过期）：GL_TRIANGLES + additive，
     * 逐碎片 glColor4f（alpha 随寿命线性淡出）。状态保存/恢复对齐 [OglEllipseRingRenderer] 惯例。
     */
    private class ShardPlugin : BaseCombatLayeredRenderingPlugin(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) {

        /** 本帧待绘碎片快照（逻辑帧 advance 写入，渲染帧读取；对齐 TexTrailRenderer.Handle 的 volatile 快照惯例）。 */
        @Volatile
        var snapshot: List<Shard> = emptyList()

        @Volatile
        var expired: Boolean = false

        override fun isExpired(): Boolean = expired

        override fun getRenderRadius(): Float = 999999f

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (expired || layer != CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) return
            val batch = snapshot
            if (batch.isEmpty()) return

            GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT or GL11.GL_TEXTURE_BIT)
            try {
                GL11.glDisable(GL11.GL_TEXTURE_2D)
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE) // additive
                GL11.glBegin(GL11.GL_TRIANGLES)
                for (shard in batch) {
                    val fade = 1f - (shard.age / shard.lifetime).coerceIn(0f, 1f)
                    val alpha = (shard.alpha * fade).coerceIn(0f, 1f)
                    if (alpha <= 0.004f) continue
                    GL11.glColor4f(shard.red, shard.green, shard.blue, alpha)
                    val rad = Math.toRadians(shard.angleDeg.toDouble())
                    val c = cos(rad).toFloat()
                    val s = sin(rad).toFloat()
                    val local = shard.local
                    var i = 0
                    while (i < local.size) {
                        val lx = local[i]
                        val ly = local[i + 1]
                        GL11.glVertex2f(shard.x + lx * c - ly * s, shard.y + lx * s + ly * c)
                        i += 2
                    }
                }
                GL11.glEnd()
            } finally {
                GL11.glPopAttrib()
            }
        }
    }

    companion object {
        /** 碎片在树内的次级绘制序：与锥面根同档。 */
        const val RENDER_ORDER = 100

        /** 等边三角形 60° 的正弦（偏斜边端点高度系数）。 */
        private const val EQUILATERAL_SIN = 0.866f

        /** 边长 = clamp(length×本值, MIN, MAX) × 随机(0.7~1.3)。 */
        private const val SHARD_SIZE_MUL = 0.03f
        private const val SHARD_SIZE_MIN = 6f
        private const val SHARD_SIZE_MAX = 16f
        private const val SHARD_SIZE_JITTER_LO = 0.7f
        private const val SHARD_SIZE_JITTER_HI = 1.3f

        /** 两边比随机区间（破完美等边的机械感）。 */
        private const val SHARD_SKEW_LO = 0.7f
        private const val SHARD_SKEW_HI = 1.3f

        /** 自旋角速度幅度区间（度/秒，方向 ± 随机）。 */
        private const val SHARD_SPIN_MIN = 180f
        private const val SHARD_SPIN_MAX = 540f

        /** coreColor 提亮碎片的占比（其余用 fringeColor）。 */
        private const val SHARD_CORE_RATIO = 0.25f

        private const val SHARD_ALPHA_LO = 140
        private const val SHARD_ALPHA_HI = 200

        /** 寿命随机区间（秒），随寿命线性淡出。 */
        private const val SHARD_LIFE_LO = 0.45f
        private const val SHARD_LIFE_HI = 0.65f
    }
}
