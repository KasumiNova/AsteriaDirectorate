package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.FrameState
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.base.api.InstanceDataAPI
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.attribute.Instance2Data
import org.boxutil.units.standard.entity.SpriteEntity
import java.awt.Color
import kotlin.math.max

/**
 * 网格类组件共用的桥接：把宿主中立的 [FrameState] 还原成旧网格渲染器所需的 [ASTDProjectileVfxRenderContext]，
 * 从而**原样复用**既有网格数学（meshesForTests/centerline/softmesh），不手抄。
 * presetId/projectileSpecId 仅供网格数学上下文填位（旧管线日志字段的残留），用节点 id 占位即可。
 */
internal fun FrameState.toRenderContext(specId: String): ASTDProjectileVfxRenderContext =
    ASTDProjectileVfxRenderContext(
        location = origin,
        velocityFacing = facing,
        projectileFacing = facing,
        renderFacing = facing,
        elapsed = elapsed,
        logicElapsed = logicElapsed,
        flightProgress = flightProgress,
        dissolve = dissolve,
        visibleLength = length,
        beamAlpha = intensity,
        historyNodes = historyNodes,
        presetId = specId,
        projectileSpecId = specId,
        worldUnitsPerPixel = worldUnitsPerPixel,
    )

/**
 * 网格类节点通用实现：辉光/弹体/侧翼/弹头/飘带都是同一形态——每帧由 [produce] 生成一批网格
 * （复用旧渲染器的 *ForTests 纯数学），每片网格对应一个 [ASTDProjectileVfxBodyRenderManager.Handle]，
 * 逐帧刷新位置/朝向/网格。绘制先后由 mesh.renderOrder 在 BodyRenderManager 内全局排序决定，故本类只管句柄生命周期。
 *
 * [produce] 收到当前帧的旧式渲染上下文与本层淡出系数（0..1），返回该帧应绘制的网格列表；
 * 各后端如何把淡出系数施加到网格（alphaScale 参数或 beamAlpha 相乘）由 [produce] 自行决定。
 */
class MeshComponent(
    id: String,
    renderOrder: Int,
    private val produce: (ASTDProjectileVfxRenderContext, Float) -> List<ASTDProjectileVfxBodyRenderer.Mesh>,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES, renderOrder) {

    private val fade = ASTDProjectileVfxLayerFadeState()
    private val handles = ArrayList<ASTDProjectileVfxBodyRenderManager.Handle>()
    private var meshes: List<ASTDProjectileVfxBodyRenderer.Mesh> = emptyList()

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        sync(engine, ctx.frame.toRenderContext(id))
        return handles.size == meshes.size
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        fade.advance(amount)
        val engine = ctx.engine ?: return
        sync(engine, ctx.frame.toRenderContext(id))
    }

    override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
        fade.begin(seconds)
    }

    override fun onDetachSelf() {
        handles.forEach { it.delete() }
        handles.clear()
        meshes = emptyList()
    }

    private fun sync(engine: CombatEngineAPI, context: ASTDProjectileVfxRenderContext) {
        meshes = produce(context, fade.alpha())
        while (handles.size < meshes.size) handles += ASTDProjectileVfxBodyRenderManager.createHandle(engine)
        while (handles.size > meshes.size) handles.removeAt(handles.lastIndex).delete()
        handles.zip(meshes).forEach { (handle, mesh) -> handle.update(context.location, context.renderFacing, mesh) }
    }
}

/** 辉光：主拖尾外围柔光晕。复用 [ASTDProjectileVfxGlowRenderer.meshesForTests]。 */
fun glowComponent(id: String, trail: ASTDTrailEntitySpec, layers: List<ASTDProjectileVfxGlowLayerSpec>): MeshComponent =
    MeshComponent(id, ASTDProjectileVfxBodyRenderer.RENDER_ORDER_GLOW) { ctx, alpha ->
        ASTDProjectileVfxGlowRenderer.meshesForTests(trail, layers, ctx, alpha)
    }

/**
 * 弹体：沿中线的实心核心 + 柔边阴影。shadow（renderOrder 180）与 body（200）合成一份列表交给句柄池，
 * 实际绘制先后由 mesh.renderOrder 在 BodyRenderManager 内决定。
 */
fun bodyComponent(id: String, trail: ASTDTrailEntitySpec): MeshComponent =
    MeshComponent(id, ASTDProjectileVfxBodyRenderer.RENDER_ORDER_BODY) { ctx, alpha ->
        listOf(
            ASTDProjectileVfxBodyRenderer.shadowMeshForTests(trail, ctx, alpha),
            ASTDProjectileVfxBodyRenderer.meshForTests(trail, ctx, alpha),
        )
    }

/** 侧翼流苏：拖尾两侧的丝状飘光。复用 [ASTDProjectileVfxSideWispRenderer.meshesForTests]。 */
fun sideWispComponent(id: String, trail: ASTDTrailEntitySpec, layers: List<ASTDProjectileVfxSideWispLayerSpec>): MeshComponent =
    MeshComponent(id, ASTDProjectileVfxBodyRenderer.RENDER_ORDER_SIDE_WISP) { ctx, alpha ->
        ASTDProjectileVfxSideWispRenderer.meshesForTests(trail, layers, ctx, alpha)
    }

/** 弹头：收拢的亮头 + 阴影。headSizeScale 来自 preset 生命周期。shadow（280）在 head（300）下方。 */
fun headComponent(
    id: String,
    trail: ASTDTrailEntitySpec,
    layers: List<ASTDProjectileVfxHeadLayerSpec>,
    headSizeScale: Float,
): MeshComponent =
    MeshComponent(id, ASTDProjectileVfxBodyRenderer.RENDER_ORDER_HEAD) { ctx, alpha ->
        ASTDProjectileVfxHeadRenderer.shadowMeshesForTests(trail, layers, ctx, headSizeScale, alpha) +
            ASTDProjectileVfxHeadRenderer.meshForTests(trail, layers, ctx, headSizeScale, alpha)
    }

/** 飘带：拖尾旁的烟带装饰。每条 ribbon 一份网格；淡出经 beamAlpha 相乘施加（与旧层一致）。 */
fun ribbonComponent(id: String, trail: ASTDTrailEntitySpec, ribbons: List<ASTDTrailRibbonDecorationSpec>): MeshComponent =
    MeshComponent(id, ASTDProjectileVfxBodyRenderer.RENDER_ORDER_RIBBON) { ctx, alpha ->
        val baseTrailStartWidth = (trail.layers.firstOrNull() ?: trail.layerSpec).startWidth
        ribbons.filter { it.enabled }.map { ribbon ->
            val sampleCount = ASTDProjectileVfxRibbonRenderer.sampleCountForTests(ribbon, ctx, trail.nodes.size)
            ASTDProjectileVfxRibbonRenderer.meshForTests(ribbon, ctx.copy(beamAlpha = ctx.beamAlpha * alpha), sampleCount, baseTrailStartWidth)
        }
    }

/**
 * 薄雾：拖尾外的散射雾团。后端不是网格而是 BoxUtil 实例化 [SpriteEntity]（每个 layer 一枚，blobCount 个粒子实例），
 * 与网格类节点分属两条 GPU 路径。粒子采样复用 [ASTDProjectileVfxMistRenderer.samplesForTests]，本组件负责 BoxUtil
 * 实体的建立/逐帧刷新/释放。
 */
class MistComponent(
    id: String,
    private val trail: ASTDTrailEntitySpec,
    private val layers: List<ASTDProjectileVfxMistLayerSpec>,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES, RENDER_ORDER_MIST) {

    private data class Handle(val layer: ASTDProjectileVfxMistLayerSpec, val entity: SpriteEntity, val instances: MutableList<Instance2Data>)

    private val handles = ArrayList<Handle>()
    private val fade = ASTDProjectileVfxLayerFadeState()

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        return create(engine, ctx.frame.toRenderContext(id))
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        fade.advance(amount)
        val engine = ctx.engine ?: return
        val context = ctx.frame.toRenderContext(id)
        if (handles.isEmpty()) create(engine, context)
        handles.forEach { handle ->
            if (!handle.entity.hasDelete()) {
                handle.entity.setStateVanilla(context.location, context.renderFacing)
                applySamples(handle.layer, context.copy(beamAlpha = context.beamAlpha * fade.alpha()), handle.entity, handle.instances)
            }
        }
    }

    override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
        fade.begin(seconds)
    }

    override fun onDetachSelf() {
        handles.forEach { it.entity.delete() }
        handles.clear()
    }

    private fun create(engine: CombatEngineAPI, context: ASTDProjectileVfxRenderContext): Boolean {
        if (handles.isNotEmpty()) return true
        BoxUtilCombatVfx.ensureReady(engine)
        val combatLayer = (trail.layers.firstOrNull() ?: trail.layerSpec).combatLayer
        layers.filter { it.enabled }.forEach { layer ->
            val entity = SpriteEntity()
            entity.setLayer(combatLayer)
            entity.setAdditiveBlend()
            entity.setBaseSizePerTiles(1f, 1f)
            entity.materialData.setColor(Color(0, 0, 0, 255))
            entity.materialData.setEmissiveColor(layer.colorEnd.toAwtColor(1f))
            entity.materialData.setEmissiveState(0f, 0f, 1f)
            entity.materialData.setAdditionEmissive(true)
            entity.materialData.setIgnoreIllumination(true)
            entity.setStateVanilla(context.location, context.renderFacing)
            val instances = MutableList(layer.blobCount.coerceAtLeast(0)) { Instance2Data() }
            applySamples(layer, context, entity, instances)
            @Suppress("UNCHECKED_CAST")
            entity.setInstanceData(instances as MutableList<InstanceDataAPI>, 0f, 3600f, 0f)
            entity.setInstanceDataRefreshIndex(0)
            entity.setInstanceDataRefreshAllFromCurrentIndex()
            entity.submitInstance()
            entity.setRenderingCount(instances.size)
            entity.setAlwaysRefreshInstanceData(true)
            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_SPRITE, entity)
            if (state == 0) {
                handles += Handle(layer, entity, instances)
            } else {
                entity.delete()
                onDetachSelf()
                throw IllegalStateException(
                    "ASTD projectile VFX mist BoxUtil SpriteEntity addEntity failed: " +
                        "state=$state layer=${layer.id} preset=${context.presetId} projectile=${context.projectileSpecId}",
                )
            }
        }
        return handles.size == layers.count { it.enabled }
    }

    private fun applySamples(layer: ASTDProjectileVfxMistLayerSpec, context: ASTDProjectileVfxRenderContext, entity: SpriteEntity, instances: MutableList<Instance2Data>) {
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        val samples = ASTDProjectileVfxMistRenderer.samplesForTests(layer, context, context.visibleLength, widthBase)
        val scale = context.worldUnitsPerPixel.coerceAtLeast(0.0001f)
        samples.forEachIndexed { index, sample ->
            val data = instances[index]
            data.setLocation(ASTDProjectileVfxLayout.scalePoint(sample.position, scale))
            data.setScale(max(0.1f, sample.rx * scale), max(0.1f, sample.ry * scale))
            data.setFacing(0f)
            data.setColor(layer.colorStart.red, layer.colorStart.green, layer.colorStart.blue, 0f)
            data.setEmissiveColor(layer.colorEnd.red, layer.colorEnd.green, layer.colorEnd.blue, sample.alpha)
            data.setTimer(0f, 3600f, 0f)
        }
        entity.setInstanceDataRefreshIndex(0)
        entity.setInstanceDataRefreshAllFromCurrentIndex()
        entity.submitInstance()
        entity.setRenderingCount(samples.size)
    }

    private companion object {
        // 薄雾走 BoxUtil 路径，不参与 BodyRenderManager 的 mesh.renderOrder 排序；此值仅用于树内兄弟节点的推进/绘制次序。
        // 置于辉光（100）之下，作为最底层背景雾。
        const val RENDER_ORDER_MIST = 50
    }
}
