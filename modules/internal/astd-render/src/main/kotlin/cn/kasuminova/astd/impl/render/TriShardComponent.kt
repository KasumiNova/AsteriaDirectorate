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
 * 「三角碎片」渲染参数集：批次数/贴图/尺寸域/两边比/自旋域/核心色占比/alpha 域/
 * emissive 降权/寿命域/渲染层。默认值即锥面冲击特效的既有观感（§10.9 v4.2.2），
 * 引擎碎片喷散等新场景按需覆盖（如单批、更小尺寸、更低 alpha、更短寿命、BELOW_SHIPS 层）。
 */
data class TriShardSpec(
    /** 批次数：锥面冲击为 3（顶点/锥内/锥缘错峰灌批）；持续发射器一般用 1。 */
    val batchCount: Int = 3,
    /** 碎片贴图（默认 64×64 硬边白三角、形在 alpha、无预模糊；着色由实例 color/emissiveColor 承担）。 */
    val spritePath: String = "graphics/fx/astd_shard_tri.png",
    /** 边长 = clamp(length×sizeMul, sizeMin, sizeMax) × 随机(sizeJitterLo~sizeJitterHi)（实例 scale 为半尺寸）。 */
    val sizeMul: Float = 0.03f,
    val sizeMin: Float = 6f,
    val sizeMax: Float = 16f,
    val sizeJitterLo: Float = 0.7f,
    val sizeJitterHi: Float = 1.3f,
    /** 两边比随机区间（非均匀 scale，破完美等边的机械感）。 */
    val skewLo: Float = 0.7f,
    val skewHi: Float = 1.3f,
    /** 自旋角速度幅度区间（度/秒，方向 ± 随机）。 */
    val spinMin: Float = 180f,
    val spinMax: Float = 540f,
    /** coreColor 提亮碎片的占比（其余用 fringeColor）。 */
    val coreRatio: Float = 0.25f,
    val alphaLo: Int = 140,
    val alphaHi: Int = 200,
    /**
     * emissive alpha 降权系数（v3 光斑化教训）：Sprite frag 合成 `diffuse + emissive×emissive.w`
     * 且 fragEmissive 全强度进 bloom 缓冲——全 alpha 的 emissive 会让三角区域双倍亮过曝、
     * 并把小三角经高斯扩散糊成圆光斑；降权后 bloom 只留一圈淡辉，形状主体由硬边 diffuse 保住。
     */
    val emissiveAlphaMul: Float = 0.4f,
    /** 实例定时器（秒）：fadeIn/fadeOut 定值，full 随机域。默认总寿命 0.52~0.67s。 */
    val timerFadeIn: Float = 0.02f,
    val timerFadeOut: Float = 0.32f,
    val timerFullLo: Float = 0.18f,
    val timerFullHi: Float = 0.32f,
    /** 渲染层：锥面冲击在 ABOVE_SHIPS；引擎喷散等“船尾”特效用 BELOW_SHIPS。 */
    val layer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
)

/**
 * 「三角碎片」渲染组件（原 ConeShardComponent，通用化改名）：一簇随机旋转的三角形碎片，
 * 渲染后端为 **SpriteEntity 实例化渲染**——贴图 [TriShardSpec.spritePath] + additive +
 * emissive 同色降权（[TriShardSpec.emissiveAlphaMul]）接原生泛光。
 *
 * 两类用法：
 * - 树内一次性爆发（锥面冲击特效 [ConeImpactVfxComponent]）：根组件按错峰阈值灌批，
 *   advanceSelf 自动把有实例的批激活成实体；
 * - 树外持续发射（引擎碎片喷散 ASTDEngineShardSprayEffect）：持有方直接 [addShard] 累积，
 *   按节拍调 [activatePendingBatches] 灌批。
 *
 * 激活即消费：批内实例灌入**新建**的 SpriteEntity 后清空，可反复灌批；实体全局定时器
 * （fadeIn + 批内最大 full + fadeOut）走完由 BoxUtil 自删，无需持有方回收。
 * 实例位置/速度/自转/定时器均由 BoxUtil 自管理，本组件不逐帧积分。
 *
 * 失败语义：贴图加载/addEntity/实例数据提交失败记 WARN，该批视觉缺席（对齐扭曲层先例，无兜底）。
 */
class TriShardComponent(
    id: String,
    private val length: Float,
    private val coreColor: Color,
    private val fringeColor: Color,
    private val spec: TriShardSpec = TriShardSpec(),
) : RenderEntityImpl(id) {

    private val log = Global.getLogger(TriShardComponent::class.java)

    /** 各批待发射实例参数（internal 供单测断言批次错峰/实例总数/参数域）。 */
    internal val batches = List(spec.batchCount) { Batch() }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        activatePendingBatches(engine)
    }

    /** 立即把所有含待发射实例的批灌成实体（树内由 advanceSelf 调；树外持续发射器按自有节拍调）。 */
    fun activatePendingBatches(engine: CombatEngineAPI) {
        for (batch in batches) {
            if (batch.instances.isNotEmpty()) activateBatch(engine, batch)
        }
    }

    /**
     * 加一颗碎片到 [batchIndex] 批：位置/速度由调用方给定；[sizeScale] 在 spec 尺寸域基础上
     * 再乘算（持续发射器按出力动态调尺寸用）。尺寸/自旋/颜色提亮/alpha/寿命逐颗错参，域见 [spec]。
     */
    fun addShard(batchIndex: Int, pos: Vector2f, vel: Vector2f, sizeScale: Float = 1f) {
        val side = (length * spec.sizeMul).coerceIn(spec.sizeMin, spec.sizeMax) *
            MathUtils.getRandomNumberInRange(spec.sizeJitterLo, spec.sizeJitterHi) * sizeScale
        val sideRatio = MathUtils.getRandomNumberInRange(spec.skewLo, spec.skewHi)
        val brighten = MathUtils.getRandomNumberInRange(0f, 1f) < spec.coreRatio
        val base = if (brighten) coreColor else fringeColor
        val spinMag = MathUtils.getRandomNumberInRange(spec.spinMin, spec.spinMax)
        val spin = if (MathUtils.getRandomNumberInRange(0f, 1f) < 0.5f) -spinMag else spinMag
        val alpha = MathUtils.getRandomNumberInRange(spec.alphaLo, spec.alphaHi).coerceIn(0, 255)
        batches[batchIndex].instances += ShardInstance(
            pos = Vector2f(pos),
            vel = Vector2f(vel),
            facingDeg = MathUtils.getRandomNumberInRange(0f, 360f),
            turnRateDegPerSec = spin,
            // Instance2Data 的 scale 是半尺寸（边长的一半）。
            scaleX = side * 0.5f,
            scaleY = side * 0.5f * sideRatio,
            color = Color(base.red, base.green, base.blue, alpha),
            // emissive 降权（alpha × emissiveAlphaMul）：淡辉接原生泛光但不糊形状。
            emissiveAlpha = (alpha * spec.emissiveAlphaMul).toInt().coerceIn(0, 255),
            timerFull = MathUtils.getRandomNumberInRange(spec.timerFullLo, spec.timerFullHi),
        )
    }

    /** 灌一批：建 SpriteEntity 并灌入全部待发射实例参数（失败记 WARN，本批视觉缺席；无论成败批即消费清空）。 */
    private fun activateBatch(engine: CombatEngineAPI, batch: Batch) {
        val pending = ArrayList(batch.instances)
        batch.instances.clear()
        try {
            BoxUtilCombatVfx.ensureReady(engine)
            // 须先 loadTexture 进缓存，否则裸 getSprite 拿到 textureID=0 的壳（采样默认纹理
            // alpha=0 → frag discard → 整批零渲染；v4.2 实机"碎片完全消失"+诊断 texID=0 实锤）。
            // loadTexture 全局幂等只跑一次（对齐 AttachedBeamSpriteRingRenderer 先例）。
            val sprite = Global.getSettings().apply { loadTexture(spec.spritePath) }.getSprite(spec.spritePath)
            val entity = SpriteEntity()
            entity.setAdditiveBlend()
            entity.materialData.setDiffuse(sprite)
            entity.materialData.setEmissive(sprite)
            // 实例坐标即世界坐标：实体锚原点、零朝向。
            entity.setStateVanilla(ZERO, 0f)
            entity.setLayer(spec.layer)

            val dataList = ArrayList<InstanceDataAPI>(pending.size)
            var maxFull = 0f
            for (inst in pending) {
                val data = Instance2Data()
                data.setLocation(inst.pos.x, inst.pos.y)
                data.setVelocity(inst.vel.x, inst.vel.y)
                data.setFacing(inst.facingDeg)
                data.setTurnRate(inst.turnRateDegPerSec)
                data.setScale(inst.scaleX, inst.scaleY)
                data.setTimer(spec.timerFadeIn, inst.timerFull, spec.timerFadeOut)
                data.setColor(inst.color)
                data.setEmissiveColor(inst.color.red, inst.color.green, inst.color.blue, inst.emissiveAlpha)
                dataList.add(data)
                maxFull = maxOf(maxFull, inst.timerFull)
            }

            entity.setInstanceData(dataList, spec.timerFadeIn, maxFull, spec.timerFadeOut)
            entity.setInstanceDataRefreshAllFromCurrentIndex()
            if (!submitDynamicInstanceData(entity, dataList.size)) {
                // 实体未注册进渲染队列，但实例内存可能已 malloc——delete 释放资源防泄漏
                // （持续发射器按节拍反复灌批，失败路径不得累积泄漏）。
                entity.delete()
                return
            }
            entity.setRenderingCount(pending.size)
            entity.setAlwaysRefreshInstanceData(true)

            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_SPRITE, entity)
            if (state != 0) {
                log.warn("三角碎片批注册失败（addEntity 返回 $state，id=$id），本批视觉缺席")
                entity.delete()
            }
        } catch (t: Throwable) {
            log.warn("三角碎片批生成异常（id=$id），本批视觉缺席", t)
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
                log.warn("三角碎片实例内存分配失败（id=$id），本批视觉缺席")
                return false
            }
            entity.submitInstance()
            true
        } catch (t: Throwable) {
            log.warn("三角碎片实例数据提交异常（id=$id），本批视觉缺席", t)
            false
        }
    }

    /** 一批碎片：待发射实例参数表（世界系）。激活即清空，可反复灌批。 */
    internal class Batch {
        val instances = ArrayList<ShardInstance>()
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
        /** 实体锚点（实例坐标即世界坐标）。 */
        private val ZERO = Vector2f(0f, 0f)
    }
}
