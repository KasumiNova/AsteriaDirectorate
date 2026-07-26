package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.impl.render.RenderEntityImpl.HeadComponent
import cn.kasuminova.astd.impl.render.RenderEntityImpl.TrailComponent
import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderLayer
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.entity.TrailEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * composite 场景树基类：提供子节点遍历与保留模式生命周期骨架（设计 D1/D3/D4）。
 *
 * 生命周期方法（onAttach/advance/render/beginFadeOut/onDetach）为 final：基类保证“先处理自身、再递归子节点”
 * 的遍历与顺序，子类只覆写自己那段后端逻辑（`*Self` 钩子），杜绝忘调 super 导致的漏递归/句柄泄漏。
 */
open class RenderEntityImpl(
    override val id: String,
    override val layer: RenderLayer = CombatEngineLayers.ABOVE_PARTICLES,
    override val renderOrder: Int = 0,
) : RenderEntity {

    /** 保持插入序的子节点表，id 为键。[children] 在其上做按 (层, renderOrder) 稳定排序。 */
    private val childMap = LinkedHashMap<String, RenderEntity>()

    private var attached = false
    private var attachOk = false

    override val children: List<RenderEntity>
        get() = childMap.values.sortedWith(compareBy({ it.layer.ordinal }, { it.renderOrder }))

    final override fun addChild(child: RenderEntity) {
        val displaced = childMap.put(child.id, child)
        if (displaced != null && displaced !== child) displaced.onDetach()
    }

    final override fun removeChild(id: String) {
        childMap.remove(id)?.onDetach()
    }

    final override fun onAttach(ctx: RenderContext): Boolean {
        if (attached) return attachOk
        attached = true
        attachOk = onAttachSelf(ctx)
        for (child in children) {
            attachOk = child.onAttach(ctx) && attachOk
        }
        return attachOk
    }

    final override fun advance(ctx: RenderContext, amount: Float) {
        advanceSelf(ctx, amount)
        // 快照遍历：advanceSelf 内 root component 可能增删 children，此处重新取排序快照，
        // 新子节点惰性 onAttach 后当帧即 advance（设计 §11）。
        for (child in children) {
            child.onAttach(ctx)
            child.advance(ctx, amount)
        }
    }

    final override fun render(ctx: RenderContext) {
        ctx.stack.push(this)
        try {
            renderSelf(ctx)
            for (child in children) child.render(ctx)
        } finally {
            ctx.stack.pop()
        }
    }

    final override fun beginFadeOut(reason: FadeReason, seconds: Float) {
        beginFadeOutSelf(reason, seconds)
        for (child in children) child.beginFadeOut(reason, seconds)
    }

    final override fun onDetach() {
        if (!attached) return
        for (child in children) child.onDetach()
        onDetachSelf()
        attached = false
    }

    /** 创建自身后端句柄。基类无后端返回 true；后端子类覆写，engine 为 null 时返回 false。 */
    protected open fun onAttachSelf(ctx: RenderContext): Boolean = true

    /** 逐帧更新自身后端句柄；root component 在此按 [RenderContext.frame] 增删子节点。 */
    protected open fun advanceSelf(ctx: RenderContext, amount: Float) {}

    /** 立即模式绘制自身（自定义 GL 节点覆写）。 */
    protected open fun renderSelf(ctx: RenderContext) {}

    /** 对自身后端句柄触发淡出。 */
    protected open fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {}

    /** 释放自身后端句柄。 */
    protected open fun onDetachSelf() {}

    /**
     * 头部组件：弹体/光束前端的实心网格（后端桥接见 P1）。此处只承载作者面参数。
     */
    class HeadComponent(id: String = "head") : RenderEntityImpl(id) {
        var color: Color = Color.WHITE
        var width: Float = 24f
        var length: Float = 120f
    }

    /**
     * 拖尾组件：主曳光（后端桥接 BoxUtil TrailEntity）。作者面参数为 color/width/length，
     * 其余低层旋钮（纹理/emissive/fill/闪烁）走 [ASTDTrailLayerSpec] 默认（P3/P4 按需下探）。
     * 后端 BoxUtil 属性写入复用既有 [ASTDProjectileVfxTrailRenderer.applyLayer]，不手抄。
     */
    class TrailComponent(id: String = "trail") : RenderEntityImpl(id) {
        var color: Color = Color.WHITE
        var width: Float = 12f
        var length: Float = 300f

        private val log = Global.getLogger(TrailComponent::class.java)
        private val fade = ASTDProjectileVfxLayerFadeState()
        private var layerSpec: ASTDTrailLayerSpec? = null
        private var entity: TrailEntity? = null

        override fun onAttachSelf(ctx: RenderContext): Boolean {
            val engine = ctx.engine ?: return false
            if (entity != null) return true
            val frame = ctx.frame
            val spec = ASTDTrailLayerSpec(width = width, color = color.toAstdColor()).also { layerSpec = it }
            BoxUtilCombatVfx.ensureReady(engine)
            val created = TrailEntity()
            ASTDProjectileVfxLayout
                .scalePoints(ASTDProjectileVfxLayout.trailLocalNodes(frame.length), frame.worldUnitsPerPixel)
                .forEach { created.addNode(Vector2f(it)) }
            created.submitNodes()
            ASTDProjectileVfxTrailRenderer.applyLayer(created, spec, frame.intensity, worldUnitsPerPixel = frame.worldUnitsPerPixel)
            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_TRAIL, created)
            if (state != 0) {
                created.delete()
                log.warn("ASTD projectile VFX RenderEntity TrailComponent addEntity failed: state=$state id=$id")
                return false
            }
            created.setStateVanilla(frame.origin, frame.facing)
            entity = created
            return true
        }

        override fun advanceSelf(ctx: RenderContext, amount: Float) {
            fade.advance(amount)
            val current = entity ?: return
            if (current.hasDelete()) {
                entity = null
                return
            }
            val spec = layerSpec ?: return
            val frame = ctx.frame
            current.setNodes(
                ASTDProjectileVfxLayout.mutableScaledNodeList(
                    ASTDProjectileVfxLayout.trailLocalNodes(frame.length),
                    frame.worldUnitsPerPixel,
                ),
            )
            current.setNodeRefreshIndex(0)
            current.setNodeRefreshAllFromCurrentIndex()
            current.submitNodes()
            ASTDProjectileVfxTrailRenderer.applyLayer(
                current,
                spec,
                frame.intensity * fade.alpha(),
                worldUnitsPerPixel = frame.worldUnitsPerPixel,
            )
            current.setStateVanilla(frame.origin, frame.facing)
        }

        override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
            fade.begin(seconds)
        }

        override fun onDetachSelf() {
            entity?.delete()
            entity = null
        }
    }
}

private fun Color.toAstdColor(): ASTDColor = ASTDColor(red / 255f, green / 255f, blue / 255f, alpha / 255f)

// ---- DSL ----

/** DSL 作用域标记，避免嵌套构建块误用外层接收者。 */
@DslMarker
annotation class RenderEntityDsl

/** 构建一棵特效树。 */
@RenderEntityDsl
fun renderEntity(id: String, block: RenderEntityImpl.() -> Unit): RenderEntity =
    RenderEntityImpl(id).apply(block)

/** 挂一个由 [supplier] 产出的子节点（root component 分组用，设计 D4）。 */
@RenderEntityDsl
fun RenderEntity.child(supplier: () -> RenderEntity): RenderEntity = apply { addChild(supplier()) }

/** 头部组件构建器；构建后挂到父节点。 */
@RenderEntityDsl
fun RenderEntity.head(id: String = "head", block: HeadComponent.() -> Unit = {}): HeadComponent =
    HeadComponent(id).apply(block).also { addChild(it) }

/** 拖尾组件构建器；构建后挂到父节点（修复草稿漏挂父节点的问题）。 */
@RenderEntityDsl
fun RenderEntity.trail(id: String = "trail", block: TrailComponent.() -> Unit = {}): TrailComponent =
    TrailComponent(id).apply(block).also { addChild(it) }

// ---- setter（命名旋钮，省略即默认）----

fun HeadComponent.color(color: Color): HeadComponent = apply { this.color = color }
fun HeadComponent.color(rgb: Int): HeadComponent = apply { this.color = Color(rgb) }
fun HeadComponent.width(width: Float): HeadComponent = apply { this.width = width }
fun HeadComponent.length(length: Float): HeadComponent = apply { this.length = length }

fun TrailComponent.color(color: Color): TrailComponent = apply { this.color = color }
fun TrailComponent.color(rgb: Int): TrailComponent = apply { this.color = Color(rgb) }
fun TrailComponent.width(width: Float): TrailComponent = apply { this.width = width }
fun TrailComponent.length(length: Float): TrailComponent = apply { this.length = length }
