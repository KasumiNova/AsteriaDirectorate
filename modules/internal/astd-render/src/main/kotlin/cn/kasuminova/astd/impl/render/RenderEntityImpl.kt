package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderLayer
import com.fs.starfarer.api.combat.CombatEngineLayers

open class RenderEntityImpl(
    override val id: String,
    override val layer: RenderLayer = CombatEngineLayers.ABOVE_PARTICLES,
    override val renderOrder: Int = 0,
) : RenderEntity {

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
        // advanceSelf 可能增删子节点，遍历必须重新读取当前快照。
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

    protected open fun onAttachSelf(ctx: RenderContext): Boolean = true

    protected open fun advanceSelf(ctx: RenderContext, amount: Float) {}

    protected open fun renderSelf(ctx: RenderContext) {}

    protected open fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {}

    protected open fun onDetachSelf() {}
}

@DslMarker
annotation class RenderEntityDsl

@RenderEntityDsl
fun renderEntity(id: String, block: RenderEntityImpl.() -> Unit): RenderEntity =
    RenderEntityImpl(id).apply(block)

@RenderEntityDsl
fun RenderEntity.child(supplier: () -> RenderEntity): RenderEntity = apply { addChild(supplier()) }
