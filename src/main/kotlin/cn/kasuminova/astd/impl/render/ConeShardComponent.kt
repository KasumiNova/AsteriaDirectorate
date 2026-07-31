package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.base.api.InstanceDataAPI
import org.boxutil.base.api.InstanceRenderAPI
import org.boxutil.define.BoxEnum
import org.boxutil.define.InstanceType
import org.boxutil.units.standard.attribute.Instance2Data
import org.boxutil.units.standard.entity.SpriteEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 锥面冲击特效的「三角碎片」组件（计划 00-锥面冲击特效重做计划 §10.9 v4.2）：一簇随机旋转的
 * 三角形碎片，渲染后端自手绘 GL_TRIANGLES（ShardPlugin）迁移为 **SpriteEntity 实例化渲染**——
 * 用户需求「三角碎片需要接入 BoxUtil 实现原生 bloom 特效」。贴图 `graphics/fx/astd_shard_tri.png`
 * （64×64 硬边白三角、形在 alpha、无预模糊）+ additive（对齐 v2.2 GL 的 additive）+
 * emissive 同色降权接原生泛光。
 *
 * 结构：三枚 SpriteEntity 批（t=0 / +0.05 / +0.10，实例 6/8/4）。散布模型（轴向角向随机）、
 * 批次数、速度模型由根组件 [ConeImpactVfxComponent] 保留不变，本组件只换「生成什么」：
 * - [addShard]：逐颗错参（尺寸两边比/自旋/颜色提亮/alpha/寿命）存为实例参数（逐值平移 v2.2）；
 * - advanceSelf：批内有实例且未激活即灌批建 SpriteEntity（实例位置/速度/自转/定时器均由
 *   BoxUtil 自管理，本组件不再逐帧积分——v2.2 的 advance 积分与 ShardPlugin 快照全退役）；
 * - 实体寿命由实例 timer（fadeIn 0.02 / full 0.38~0.55 / fadeOut 0.10 ≈ v2.2 寿命 0.45~0.65s
 *   的淡出读感）自管理；
 * - **emissive 降权**（v3 光斑化教训）：Sprite frag 合成 `diffuse + emissive×emissive.w` 且
 *   fragEmissive 全强度进 bloom 缓冲——全 alpha 的 emissive 会让三角区域双倍亮过曝、并把
 *   6~20su 小三角经高斯扩散糊成圆光斑；alpha × [EMISSIVE_ALPHA_MUL] 后 bloom 只留一圈淡辉，
 *   形状主体由硬边 diffuse 保住（「接原生泛光」语义保留：降权不是删除）。
 *
 * 失败语义：贴图加载/addEntity/实例数据提交失败记 WARN，该批视觉缺席（对齐扭曲层先例，无兜底）。
 */
class ConeShardComponent(
    id: String,
    private val length: Float,
    private val coreColor: Color,
    private val fringeColor: Color,
) : RenderEntityImpl(id) {

    private val log = Global.getLogger(ConeShardComponent::class.java)

    /** 三批实例参数与后端实体（internal 供单测断言批次错峰/实例总数/参数域）。 */
    internal val batches = listOf(Batch(), Batch(), Batch())

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        for (batch in batches) {
            // 根组件按错峰阈值灌批（与本组件同帧 advance）：批内有实例且未激活即建实体。
            if (!batch.activated && batch.instances.isNotEmpty()) {
                activateBatch(engine, batch)
            }
        }
    }

    /**
     * 加一颗碎片到 [batchIndex] 批：位置/速度由调用方（根组件的批次散布模型）给定；
     * 尺寸（边长 clamp(length×0.03, 6, 16)×随机(0.7~1.3)，实例 scale 为半尺寸、两边比 0.7~1.3
     * 非均匀）、自旋（初始角 0~360°、角速度 ±180~540°/s）、颜色（1/4 概率 coreColor 提亮、
     * 其余 fringeColor；alpha 140~200 逐颗随机）、寿命（full 0.38~0.55s 随机）逐颗错参（逐值平移 v2.2）。
     */
    fun addShard(batchIndex: Int, pos: Vector2f, vel: Vector2f) {
        val side = (length * SHARD_SIZE_MUL).coerceIn(SHARD_SIZE_MIN, SHARD_SIZE_MAX) *
            MathUtils.getRandomNumberInRange(SHARD_SIZE_JITTER_LO, SHARD_SIZE_JITTER_HI)
        val sideRatio = MathUtils.getRandomNumberInRange(SHARD_SKEW_LO, SHARD_SKEW_HI)
        val brighten = MathUtils.getRandomNumberInRange(0f, 1f) < SHARD_CORE_RATIO
        val base = if (brighten) coreColor else fringeColor
        val spinMag = MathUtils.getRandomNumberInRange(SHARD_SPIN_MIN, SHARD_SPIN_MAX)
        val spin = if (MathUtils.getRandomNumberInRange(0f, 1f) < 0.5f) -spinMag else spinMag
        val alpha = MathUtils.getRandomNumberInRange(SHARD_ALPHA_LO, SHARD_ALPHA_HI).coerceIn(0, 255)
        batches[batchIndex].instances += ShardInstance(
            pos = Vector2f(pos),
            vel = Vector2f(vel),
            facingDeg = MathUtils.getRandomNumberInRange(0f, 360f),
            turnRateDegPerSec = spin,
            // Instance2Data 的 scale 是半尺寸（边长的一半）。
            scaleX = side * 0.5f,
            scaleY = side * 0.5f * sideRatio,
            color = Color(base.red, base.green, base.blue, alpha),
            // emissive 降权（alpha × [EMISSIVE_ALPHA_MUL]）：淡辉接原生泛光但不糊形状。
            emissiveAlpha = (alpha * EMISSIVE_ALPHA_MUL).toInt().coerceIn(0, 255),
            timerFull = MathUtils.getRandomNumberInRange(TIMER_FULL_LO, TIMER_FULL_HI),
        )
    }

    /** 灌一批：建 SpriteEntity 并灌入全部实例参数（失败记 WARN，本批视觉缺席）。 */
    private fun activateBatch(engine: CombatEngineAPI, batch: Batch) {
        batch.activated = true
        try {
            BoxUtilCombatVfx.ensureReady(engine)
            // 须先 loadTexture 进缓存，否则裸 getSprite 拿到 textureID=0 的壳（采样默认纹理
            // alpha=0 → frag discard → 整批零渲染；v4.2 实机"碎片完全消失"+诊断 texID=0 实锤）。
            // loadTexture 全局幂等只跑一次（对齐 AttachedBeamSpriteRingRenderer 先例）。
            val sprite = Global.getSettings().apply { loadTexture(SHARD_SPRITE_PATH) }.getSprite(SHARD_SPRITE_PATH)
            val entity = SpriteEntity()
            entity.setAdditiveBlend()
            entity.materialData.setDiffuse(sprite)
            entity.materialData.setEmissive(sprite)
            // 实例坐标即世界坐标：实体锚原点、零朝向。
            entity.setStateVanilla(ZERO, 0f)
            entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)

            val dataList = ArrayList<InstanceDataAPI>(batch.instances.size)
            var maxFull = 0f
            for (inst in batch.instances) {
                val data = Instance2Data()
                data.setLocation(inst.pos.x, inst.pos.y)
                data.setVelocity(inst.vel.x, inst.vel.y)
                data.setFacing(inst.facingDeg)
                data.setTurnRate(inst.turnRateDegPerSec)
                data.setScale(inst.scaleX, inst.scaleY)
                data.setTimer(TIMER_FADE_IN, inst.timerFull, TIMER_FADE_OUT)
                data.setColor(inst.color)
                data.setEmissiveColor(inst.color.red, inst.color.green, inst.color.blue, inst.emissiveAlpha)
                dataList.add(data)
                maxFull = maxOf(maxFull, inst.timerFull)
            }

            entity.setInstanceData(dataList, TIMER_FADE_IN, maxFull, TIMER_FADE_OUT)
            entity.setInstanceDataRefreshAllFromCurrentIndex()
            if (!submitDynamicInstanceData(entity, dataList.size)) return
            entity.setRenderingCount(batch.instances.size)
            entity.setAlwaysRefreshInstanceData(true)

            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_SPRITE, entity)
            if (state != 0) {
                log.warn("锥面碎片批注册失败（addEntity 返回 $state，id=$id），本批视觉缺席")
                entity.delete()
                return
            }
            batch.entity = entity
        } catch (t: Throwable) {
            log.warn("锥面碎片批生成异常（id=$id），本批视觉缺席", t)
        }
    }

    /**
     * 实例数据提交（动态实例内存未分配则先 malloc，再 submit）：
     * 任何一步失败记 WARN 返回 false（本批视觉缺席，禁兜底）。
     */
    private fun submitDynamicInstanceData(entity: InstanceRenderAPI, instanceCount: Int): Boolean {
        if (instanceCount < 1) return false
        return try {
            val memory = entity.instanceDataMemory
            if (memory == null || memory.is_type_fixed()) {
                entity.mallocInstance(InstanceType.DYNAMIC_2D, instanceCount)
                entity.setInstanceDataRefreshIndex(0)
                entity.setInstanceDataRefreshOffset(0)
                entity.setInstanceDataRefreshAllFromCurrentIndex()
            }
            val after = entity.instanceDataMemory
            if (after == null || after.is_type_fixed()) {
                log.warn("锥面碎片实例内存分配失败（id=$id），本批视觉缺席")
                return false
            }
            entity.submitInstance()
            true
        } catch (t: Throwable) {
            log.warn("锥面碎片实例数据提交异常（id=$id），本批视觉缺席", t)
            false
        }
    }

    /** 一批碎片：实例参数表（世界系）、激活标记与后端 SpriteEntity 句柄。 */
    internal class Batch {
        val instances = ArrayList<ShardInstance>()
        var activated = false
        var entity: SpriteEntity? = null
    }

    /** 一颗碎片实例：世界系位置/速度、自旋角与角速度、半尺寸两边比、颜色与 emissive 降权 alpha、满亮相时长。 */
    internal class ShardInstance(
        val pos: Vector2f,
        val vel: Vector2f,
        val facingDeg: Float,
        val turnRateDegPerSec: Float,
        val scaleX: Float,
        val scaleY: Float,
        val color: Color,
        val emissiveAlpha: Int,
        val timerFull: Float,
    )

    companion object {
        /** 碎片贴图（64×64 硬边白三角、形在 alpha、无预模糊；着色由实例 color/emissiveColor 承担）。 */
        const val SHARD_SPRITE_PATH = "graphics/fx/astd_shard_tri.png"

        /** 批次数（顶点 6 @t=0 / 锥内 8 @+0.05 / 锥缘 4 @+0.10，由根组件错峰灌批）。 */
        const val BATCH_COUNT = 3

        /** 实例定时器（秒）：fadeIn / fadeOut 定值，full 随机域——总寿命 0.50~0.67s ≈ v2.2 的 0.45~0.65s。 */
        const val TIMER_FADE_IN = 0.02f
        const val TIMER_FADE_OUT = 0.10f
        const val TIMER_FULL_LO = 0.38f
        const val TIMER_FULL_HI = 0.55f

        /** 边长 = clamp(length×本值, MIN, MAX) × 随机(0.7~1.3)（实例 scale 为半尺寸）。 */
        private const val SHARD_SIZE_MUL = 0.03f
        private const val SHARD_SIZE_MIN = 6f
        private const val SHARD_SIZE_MAX = 16f
        private const val SHARD_SIZE_JITTER_LO = 0.7f
        private const val SHARD_SIZE_JITTER_HI = 1.3f

        /** 两边比随机区间（非均匀 scale，破完美等边的机械感）。 */
        private const val SHARD_SKEW_LO = 0.7f
        private const val SHARD_SKEW_HI = 1.3f

        /** 自旋角速度幅度区间（度/秒，方向 ± 随机）。 */
        private const val SHARD_SPIN_MIN = 180f
        private const val SHARD_SPIN_MAX = 540f

        /** coreColor 提亮碎片的占比（其余用 fringeColor）。 */
        private const val SHARD_CORE_RATIO = 0.25f

        private const val SHARD_ALPHA_LO = 140
        private const val SHARD_ALPHA_HI = 200

        /**
         * emissive alpha 降权系数（v3 光斑化教训）：Sprite frag 合成 `diffuse + emissive×emissive.w`
         * 且 fragEmissive 全强度进 bloom 缓冲——全 alpha 的 emissive 会让三角区域双倍亮过曝、
         * 并把 6~20su 小三角经高斯扩散糊成圆光斑；×0.4 后 bloom 只留一圈淡辉，形状主体由硬边
         * diffuse 保住（「接原生泛光」语义保留：降权不是删除）。
         */
        const val EMISSIVE_ALPHA_MUL = 0.4f

        /** 实体锚点（实例坐标即世界坐标）。 */
        private val ZERO = Vector2f(0f, 0f)
    }
}
