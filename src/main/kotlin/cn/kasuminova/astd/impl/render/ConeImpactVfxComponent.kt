package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.graphics.SpriteAPI
import org.boxutil.units.standard.entity.TrailEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 共用锥面冲击特效组件（规格 00-共享基建 §2.2-5）：顶点闪光 + 沿中轴扩散的冲击锥射线面。
 *
 * 作为一次性 RenderEntity 树的根节点，由 [OneShotVfxPlugin] 逐帧推进：
 * - onAttach：顶点两口闪光粒子（vanilla，一闪即走）+ 按偏角序列建 BoxUtil 渐变拖尾射线；
 * - advance：expand 阶段（smoothstep）逐帧把射线长度从 0 推到全长、宽度从 60% 推到全宽，
 *   并以心跳定时器保活；过 duration - fadeOut 后停止心跳、触发 BoxUtil 全局定时器淡出；
 * - onDetach/beginFadeOut：触发淡出/删除全部射线句柄（幂等）。
 *
 * 几何常量（锥角/锥长/调色）创建期定死，每帧只读 [RenderContext.frame] 的 elapsed 做包络。
 */
class ConeImpactVfxComponent(
    id: String,
    private val origin: Vector2f,
    private val facingDeg: Float,
    private val length: Float,
    private val duration: Float,
    private val expandSeconds: Float,
    private val fadeOutSeconds: Float,
    private val rayOffsetsDeg: List<Float>,
    private val rayBaseWidth: Float,
    private val coreColor: Color,
    private val fringeColor: Color,
    private val flashColor: Color,
    layer: CombatEngineLayers,
) : RenderEntityImpl(id, layer, RENDER_ORDER) {

    private val log = Global.getLogger(ConeImpactVfxComponent::class.java)

    /** 一条射线：BoxUtil 渐变拖尾句柄 + 相对中轴偏角。 */
    private class Ray(val entity: TrailEntity, val offsetDeg: Float)

    private var rays: List<Ray> = emptyList()
    private var sprites: Pair<SpriteAPI, SpriteAPI>? = null
    private var fadeTriggered = false

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        spawnVertexFlash(engine)
        spawnRays(engine)
        return rays.isNotEmpty()
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val t = ctx.frame.elapsed
        val expand = smoothstep((t / expandSeconds).coerceIn(0f, 1f))
        val curLen = (length * expand).coerceAtLeast(1f)
        val widthRamp = 0.6f + 0.4f * expand

        val holdUntil = duration - fadeOutSeconds
        val alive = t < holdUntil
        for (ray in rays) {
            val entity = ray.entity
            if (entity.hasDelete()) continue
            if (alive) {
                updateRay(entity, ray.offsetDeg, curLen, widthRamp)
                entity.setGlobalTimer(0f, HEARTBEAT, fadeOutSeconds)
            } else if (!fadeTriggered) {
                // 停止心跳，交给 BoxUtil 全局定时器淡出并自删。
                entity.setGlobalTimer(0f, 0f, fadeOutSeconds)
            }
        }
        if (!alive) fadeTriggered = true
    }

    override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
        fadeTriggered = true
        rays.forEach { if (!it.entity.hasDelete()) it.entity.setGlobalTimer(0f, 0f, seconds.coerceAtLeast(0.05f)) }
    }

    override fun onDetachSelf() {
        rays.forEach { it.entity.delete() }
        rays = emptyList()
    }

    /** 顶点闪光：一口内亮核 + 一口外扩后尘（vanilla 粒子，点缀语义）。 */
    private fun spawnVertexFlash(engine: CombatEngineAPI) {
        val flashSize = (length * 0.30f).coerceIn(30f, 220f)
        engine.addSmoothParticle(origin, ZERO_VEL, flashSize, 1.6f, 0.18f, flashColor)
        engine.addSmoothParticle(origin, ZERO_VEL, flashSize * 0.55f, 2.2f, 0.12f, coreColor)
    }

    private fun spawnRays(engine: CombatEngineAPI) {
        BoxUtilCombatVfx.ensureReady(engine)
        val spritePair = sprites ?: BeamSprites.load()?.also { sprites = it } ?: run {
            log.warn("锥面冲击特效贴图加载失败（id=$id），本次只保留顶点闪光")
            return
        }
        val tipWidth = (rayBaseWidth * 0.15f).coerceIn(0.5f, 4f)
        val built = ArrayList<Ray>(rayOffsetsDeg.size)
        for (offset in rayOffsetsDeg) {
            val entity = BoxUtilCombatVfx.createAndAddTaperedBeamTrail(
                engine = engine,
                location = origin,
                facing = facingDeg + offset,
                // 初始长度 1：首帧即由 advance 按 expand 包络推到应有长度，避免满长射线闪一帧。
                length = 1f,
                tailWidth = tipWidth,
                headWidth = rayBaseWidth,
                coreColor = coreColor,
                fringeColor = fringeColor,
                coreSprite = spritePair.first,
                fringeSprite = spritePair.second,
                layer = layer,
                full = HEARTBEAT,
                tailAlphaMul = 0.15f,
                headAlphaMul = 0.9f,
                tailEmissiveAlphaMul = 0.6f,
                headEmissiveAlphaMul = 1.8f,
                mixPower = 3.0f,
            )
            if (entity == null) {
                log.warn("锥面冲击特效射线建实体失败（id=$id offset=$offset），降级为较少射线")
                continue
            }
            entity.setFillStartAlpha(0f)
            entity.setFillStartFactor(0.3f)
            entity.setFillEndAlpha(0f)
            entity.setFillEndFactor(0.85f)
            entity.setGlobalTimer(0f, HEARTBEAT, fadeOutSeconds)
            built += Ray(entity, offset)
        }
        rays = built
    }

    /** 逐帧刷新一条射线：节点长度按 expand 包络推进，宽度随包络 ramp，锚点/朝向钉死。 */
    private fun updateRay(entity: TrailEntity, offsetDeg: Float, curLen: Float, widthRamp: Float) {
        val nodes = entity.nodes
        if (nodes != null && nodes.size >= 2) {
            nodes[0].set(0f, 0f)
            nodes[1].set(curLen, 0f)
            entity.setNodeRefreshIndex(0)
            entity.setNodeRefreshAllFromCurrentIndex()
            entity.submitNodes()
        }
        val baseW = (rayBaseWidth * widthRamp).coerceAtLeast(1f)
        val tipW = (baseW * 0.15f).coerceIn(0.5f, 4f)
        entity.setStartWidth(baseW)
        entity.setEndWidth(tipW)
        entity.setStateVanilla(origin, facingDeg + offsetDeg)
    }

    private fun smoothstep(x: Float): Float = x * x * (3f - 2f * x)

    companion object {
        /** 锥面在树内的次级绘制序：与束体同档（detail 之下）。 */
        const val RENDER_ORDER = 100

        /** 保活心跳（秒）：active 期间每帧刷新令射线常驻；停刷新即按 fadeOut 淡出。 */
        private const val HEARTBEAT = 0.35f

        /** 顶点闪光粒子速度（静止）。 */
        private val ZERO_VEL = Vector2f(0f, 0f)
    }
}
