package cn.kasuminova.astd.renderer.effect.explosion

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.impl.render.BeamSprites
import cn.kasuminova.astd.impl.render.OneShotVfxPlugin
import cn.kasuminova.astd.impl.render.PointHost
import cn.kasuminova.astd.impl.render.RenderEntityImpl
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.graphics.SpriteAPI
import com.fs.starfarer.api.util.Misc
import org.boxutil.units.standard.entity.TrailEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 十字闪光爆炸的调色板（规格 07 §3.3：参数化主色族，辉星按 60% 缩放复用时换紫色族）。
 *
 * @property armCore 十字臂芯色（additive 矩形主体）。
 * @property armFringe 十字臂缘色（发光端与端部衰减）。
 * @property flare 中心亮闪色。
 * @property nebula 星云烟雾色（低 alpha 底尘，点缀语义）。
 */
data class CrossFlashPalette(
    val armCore: Color,
    val armFringe: Color,
    val flare: Color,
    val nebula: Color,
) {
    companion object {
        /**
         * ARC 冷蓝白主色族（规格 §3.3：与 aod7 hero 弹头壳色族对齐，0x478FEB / 0xF0F8FF 系）。
         * 星云烟雾低 alpha，防 bloom 提取遍溢出（对照 aod7 hero alpha(0.7f) 注记口径）。
         */
        @JvmField
        val ARC: CrossFlashPalette = CrossFlashPalette(
            armCore = Color(0xF0, 0xF8, 0xFF),
            armFringe = Color(0x6F, 0xB4, 0xFF),
            flare = Color(0xDC, 0xEE, 0xFF),
            nebula = Color(0x47, 0x8F, 0xEB, 70),
        )
    }
}

/**
 * 十字闪光爆炸渲染原语（规格 07 §3.3，首批计划 §11 定「七星首发、辉星 60% 缩放复用」，
 * 故落在武器无关位置；辉星组实装时直接调 [crossFlashExplosion] 传 scale = 0.6f 与紫色族调色板）。
 *
 * 构成（RenderEntity 管线）：十字星双正交臂（BoxUtil additive 渐变拖尾 × 4 向，端部衰减）
 * + 中心亮闪（vanilla 粒子，一亮即走）+ 星云烟雾（低 alpha 底尘）。
 * 生命周期 ≈0.5s：亮闪 0.08s → 臂展开 → 烟雾 0.4s 淡出；臂 emissive 克制（防连跳高频触发下
 * bloom 提取叠加过曝，规格 §3.3 目检项）。
 *
 * 用法：调用方在结算回调里一发即走——内部建一棵一次性 RenderEntity 树交给
 * [OneShotVfxPlugin] 逐帧推进，到期自动收尾，调用方无需持有任何句柄。
 */
object CrossFlashVfx {
    private val log = Global.getLogger(CrossFlashVfx::class.java)

    /** 十字臂基准臂长（su，scale=1 时）。 */
    internal const val BASE_ARM_LENGTH = 72f

    /** 十字臂基准臂宽（su，scale=1 时）。 */
    internal const val BASE_ARM_WIDTH = 15f

    /** 特效总存续（秒）：亮闪 → 臂展开 → 烟雾淡出。 */
    internal const val DURATION = 0.5f

    /**
     * 生成一发十字闪光爆炸。
     *
     * @param at 爆心（世界坐标，su）。
     * @param scale 整体尺寸倍率（七星连跳 1.0→1.6、终结 1.2、辉星复用 0.6）；
     *   非正/NaN 属配置错误，记 WARN 且本次不生成特效（不产出半成品）。
     * @param palette 主色族（ARC 冷蓝白见 [CrossFlashPalette.ARC]）。
     * @return 驱动插件（调用方通常不感知；返回供测试与调试定位）。
     */
    fun crossFlashExplosion(
        engine: CombatEngineAPI,
        at: Vector2f,
        scale: Float,
        palette: CrossFlashPalette,
    ): OneShotVfxPlugin? {
        if (scale.isNaN() || scale <= 0f) {
            log.warn("十字闪光爆炸 scale 非法（$scale），属配置错误，本次不生成特效")
            return null
        }
        val tree: RenderEntity = CrossFlashComponent(
            id = "cross_flash_vfx@" + System.identityHashCode(at),
            origin = Vector2f(at),
            armLength = BASE_ARM_LENGTH * scale,
            armWidth = BASE_ARM_WIDTH * scale,
            palette = palette,
            duration = DURATION,
            expandSeconds = EXPAND_SECONDS,
            fadeOutSeconds = FADE_OUT_SECONDS,
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
        )
        val host = PointHost(
            hostId = "cross_flash@" + System.identityHashCode(tree),
            origin = Vector2f(at),
            facingDeg = 0f,
        )
        // 驱动寿命 = 视觉总长 + 收尾余量（BoxUtil 全局定时器在 duration 时刻已删完句柄）。
        val plugin = OneShotVfxPlugin(engine, host, tree, DURATION + 0.15f)
        engine.addPlugin(plugin)
        return plugin
    }

    /** 臂展开时长（秒）：亮闪后双臂推到全长。 */
    private const val EXPAND_SECONDS = 0.12f

    /** 收尾淡出时长（秒）。 */
    private const val FADE_OUT_SECONDS = 0.2f
}

/**
 * 十字闪光爆炸组件（一次性 RenderEntity 树的根节点，由 [OneShotVfxPlugin] 逐帧推进）：
 * - onAttach：中心亮闪两口粒子 + 星云烟雾底尘 + 四向 BoxUtil 渐变拖尾臂；
 * - advance：expand 阶段（smoothstep）逐帧把臂长从 0 推到全长，心跳保活；过
 *   duration - fadeOut 后停止心跳交给 BoxUtil 全局定时器淡出；
 * - onDetach/beginFadeOut：触发淡出/删除全部臂句柄（幂等）。
 *
 * 机制同 ConeImpactVfxComponent（同族一次性特效范式），几何常量创建期定死，
 * 每帧只读 [RenderContext.frame] 的 elapsed 做包络。
 */
private class CrossFlashComponent(
    id: String,
    private val origin: Vector2f,
    private val armLength: Float,
    private val armWidth: Float,
    private val palette: CrossFlashPalette,
    private val duration: Float,
    private val expandSeconds: Float,
    private val fadeOutSeconds: Float,
    layer: CombatEngineLayers,
) : RenderEntityImpl(id, layer, RENDER_ORDER) {

    private val log = Global.getLogger(CrossFlashComponent::class.java)

    /** 一条十字臂：BoxUtil 渐变拖尾句柄 + 固定朝向。 */
    private class Arm(val entity: TrailEntity, val facingDeg: Float)

    private var arms: List<Arm> = emptyList()
    private var sprites: Pair<SpriteAPI, SpriteAPI>? = null
    private var fadeTriggered = false

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        spawnCenterFlash(engine)
        spawnNebulaSmoke(engine)
        spawnArms(engine)
        return arms.isNotEmpty()
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val t = ctx.frame.elapsed
        val expand = smoothstep((t / expandSeconds).coerceIn(0f, 1f))
        val curLen = (armLength * expand).coerceAtLeast(1f)

        val holdUntil = duration - fadeOutSeconds
        val alive = t < holdUntil
        for (arm in arms) {
            val entity = arm.entity
            if (entity.hasDelete()) continue
            if (alive) {
                updateArm(arm, curLen)
                entity.setGlobalTimer(0f, HEARTBEAT, fadeOutSeconds)
            } else if (!fadeTriggered) {
                entity.setGlobalTimer(0f, 0f, fadeOutSeconds)
            }
        }
        if (!alive) fadeTriggered = true
    }

    override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
        fadeTriggered = true
        arms.forEach { if (!it.entity.hasDelete()) it.entity.setGlobalTimer(0f, 0f, seconds.coerceAtLeast(0.05f)) }
    }

    override fun onDetachSelf() {
        arms.forEach { it.entity.delete() }
        arms = emptyList()
    }

    /** 中心亮闪：一口外扩亮闪 + 一口内亮核（vanilla 粒子，0.08s 亮闪语义，点缀）。 */
    private fun spawnCenterFlash(engine: CombatEngineAPI) {
        val flashSize = (armLength * 1.1f).coerceIn(30f, 200f)
        engine.addSmoothParticle(origin, ZERO_VEL, flashSize, 1.5f, 0.16f, palette.flare)
        engine.addSmoothParticle(origin, ZERO_VEL, flashSize * 0.5f, 2.2f, 0.10f, palette.armCore)
    }

    /** 星云烟雾底尘：爆心周匝 5 口低 alpha 冷蓝烟雾，0.4s 淡出（点缀语义，非面状主体）。 */
    private fun spawnNebulaSmoke(engine: CombatEngineAPI) {
        for (i in 0 until NEBULA_COUNT) {
            val angle = Misc.random.nextFloat() * 360f
            val dist = armLength * (0.15f + Misc.random.nextFloat() * 0.35f)
            val rad = Math.toRadians(angle.toDouble())
            val loc = Vector2f(
                origin.x + (kotlin.math.cos(rad) * dist).toFloat(),
                origin.y + (kotlin.math.sin(rad) * dist).toFloat(),
            )
            val size = armWidth * (1.1f + Misc.random.nextFloat() * 0.8f)
            engine.addNebulaParticle(loc, ZERO_VEL, size, 1.6f, 0.05f, 0.1f, 0.4f, palette.nebula)
        }
    }

    /** 四向十字臂：0/90/180/270° BoxUtil additive 渐变拖尾，端部衰减（fillEndAlpha=0）。 */
    private fun spawnArms(engine: CombatEngineAPI) {
        BoxUtilCombatVfx.ensureReady(engine)
        val spritePair = sprites ?: BeamSprites.load()?.also { sprites = it } ?: run {
            log.warn("十字闪光爆炸贴图加载失败（id=$id），本次只保留亮闪与烟雾")
            return
        }
        val tipWidth = (armWidth * 0.15f).coerceIn(0.5f, 4f)
        val built = ArrayList<Arm>(ARM_FACINGS.size)
        for (facing in ARM_FACINGS) {
            val entity = BoxUtilCombatVfx.createAndAddTaperedBeamTrail(
                engine = engine,
                location = origin,
                facing = facing,
                // 初始长度 1：首帧即由 advance 按 expand 包络推到应有长度，避免满长臂闪一帧。
                length = 1f,
                tailWidth = tipWidth,
                headWidth = armWidth,
                coreColor = palette.armCore,
                fringeColor = palette.armFringe,
                coreSprite = spritePair.first,
                fringeSprite = spritePair.second,
                layer = layer,
                full = HEARTBEAT,
                tailAlphaMul = 0.15f,
                headAlphaMul = 0.85f,
                tailEmissiveAlphaMul = 0.5f,
                // emissive 克制（< 锥面 1.8）：连跳 0.33s 高频触发下 bloom 提取叠加不过曝（规格 §3.3）。
                headEmissiveAlphaMul = 1.2f,
                mixPower = 3.0f,
            )
            if (entity == null) {
                log.warn("十字闪光爆炸臂建实体失败（id=$id facing=$facing），降级为较少臂")
                continue
            }
            entity.setFillStartAlpha(0f)
            entity.setFillStartFactor(0.35f)
            entity.setFillEndAlpha(0f)
            entity.setFillEndFactor(0.85f)
            entity.setGlobalTimer(0f, HEARTBEAT, fadeOutSeconds)
            built += Arm(entity, facing)
        }
        arms = built
    }

    /** 逐帧刷新一条臂：节点长度按 expand 包络推进，锚点/朝向钉死。 */
    private fun updateArm(arm: Arm, curLen: Float) {
        val entity = arm.entity
        val nodes = entity.nodes
        if (nodes != null && nodes.size >= 2) {
            nodes[0].set(0f, 0f)
            nodes[1].set(curLen, 0f)
            entity.setNodeRefreshIndex(0)
            entity.setNodeRefreshAllFromCurrentIndex()
            entity.submitNodes()
        }
        val tipW = (armWidth * 0.15f).coerceIn(0.5f, 4f)
        entity.setStartWidth(armWidth.coerceAtLeast(1f))
        entity.setEndWidth(tipW)
        entity.setStateVanilla(origin, arm.facingDeg)
    }

    private fun smoothstep(x: Float): Float = x * x * (3f - 2f * x)

    companion object {
        /** 十字在树内的次级绘制序：与锥面同档。 */
        const val RENDER_ORDER = 100

        /** 保活心跳（秒）：active 期间每帧刷新令臂常驻；停刷新即按 fadeOut 淡出。 */
        private const val HEARTBEAT = 0.3f

        /** 十字四臂朝向（度）。 */
        private val ARM_FACINGS = floatArrayOf(0f, 90f, 180f, 270f)

        /** 星云烟雾口数。 */
        private const val NEBULA_COUNT = 5

        /** 静止粒子速度（避免逐次分配）。 */
        private val ZERO_VEL = Vector2f(0f, 0f)
    }
}
