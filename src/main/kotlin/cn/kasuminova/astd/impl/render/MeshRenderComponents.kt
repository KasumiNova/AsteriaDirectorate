package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.FrameState
import cn.kasuminova.astd.api.render.RenderContext
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers

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

/**
 * bloom 网格组件：与 [MeshComponent] 同形态，但网格在 CPU 侧烘成世界系顶点流后写入
 * [TexTrailRenderer] 句柄——弹头借此并入贴图拖尾的 bloom 管线（同一离屏提取 + 模糊 + 合成），
 * 弹头与拖尾能量同源，接缝处光晕连续（走 BodyRenderManager 直绘的弹头不进 bloom，能量天然低于
 * 「双带叠加 + bloom」的带体，接缝色差无法靠调色抹平）。
 */
class BloomMeshComponent(
    id: String,
    renderOrder: Int,
    private val produce: (ASTDProjectileVfxRenderContext, Float) -> List<ASTDProjectileVfxBodyRenderer.Mesh>,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES, renderOrder) {

    private val fade = ASTDProjectileVfxLayerFadeState()
    private val handles = ArrayList<TexTrailRenderer.Handle>()
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
        while (handles.size < meshes.size) handles += TexTrailRenderer.createHandle(engine) ?: return
        while (handles.size > meshes.size) handles.removeAt(handles.lastIndex).delete()
        handles.zip(meshes).forEach { (handle, mesh) ->
            handle.update(mesh.renderOrder, null, texTrailMeshTriangles(mesh, context.location, context.renderFacing), triangles = true)
        }
    }
}

/** 弹头（bloom 管线版）：网格数学与 [headComponent] 完全一致，仅绘制后端改为 [TexTrailRenderer]。 */
fun headBloomComponent(
    id: String,
    trail: ASTDTrailEntitySpec,
    layers: List<ASTDProjectileVfxHeadLayerSpec>,
    headSizeScale: Float,
): BloomMeshComponent =
    BloomMeshComponent(id, ASTDProjectileVfxBodyRenderer.RENDER_ORDER_HEAD) { ctx, alpha ->
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
