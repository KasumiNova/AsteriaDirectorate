package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.FrameState
import cn.kasuminova.astd.api.render.RenderContext
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers

/**
 * 网格类组件共用的桥接：把宿主中立的 [FrameState] 还原成网格数学所需的 [ASTDProjectileVfxRenderContext]，
 * 从而**原样复用** [ASTDProjectileVfxHeadRenderer] 的弹头网格数学，不手抄。
 */
internal fun FrameState.toRenderContext(): ASTDProjectileVfxRenderContext =
    ASTDProjectileVfxRenderContext(
        location = origin,
        renderFacing = facing,
        beamAlpha = intensity,
        worldUnitsPerPixel = worldUnitsPerPixel,
    )

/**
 * bloom 网格组件：每帧由 [produce] 生成一批网格（复用弹头渲染器的 *ForTests 纯数学），在 CPU 侧
 * 烘成世界系顶点流后写入 [TexTrailRenderer] 句柄——弹头借此并入贴图拖尾的 bloom 管线（同一离屏
 * 提取 + 模糊 + 合成），弹头与拖尾能量同源，接缝处光晕连续（直绘弹头不进 bloom，能量天然低于
 * 「双带叠加 + bloom」的带体，接缝色差无法靠调色抹平）。
 *
 * [produce] 收到当前帧的渲染上下文与本层淡出系数（0..1），返回该帧应绘制的网格列表。
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
        sync(engine, ctx.frame.toRenderContext())
        return handles.size == meshes.size
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        fade.advance(amount)
        val engine = ctx.engine ?: return
        sync(engine, ctx.frame.toRenderContext())
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

/** 弹头（bloom 管线版）：网格数学复用 [ASTDProjectileVfxHeadRenderer]，绘制后端为 [TexTrailRenderer]。 */
fun headBloomComponent(
    id: String,
    baseLayer: ASTDTrailLayerSpec,
    layers: List<ASTDProjectileVfxHeadLayerSpec>,
    headSizeScale: Float,
): BloomMeshComponent =
    BloomMeshComponent(id, ASTDProjectileVfxBodyRenderer.RENDER_ORDER_HEAD) { ctx, alpha ->
        ASTDProjectileVfxHeadRenderer.shadowMeshesForTests(baseLayer, layers, ctx, headSizeScale, alpha) +
            ASTDProjectileVfxHeadRenderer.meshForTests(baseLayer, layers, ctx, headSizeScale, alpha)
    }
