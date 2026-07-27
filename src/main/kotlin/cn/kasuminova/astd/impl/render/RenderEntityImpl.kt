package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderLayer
import com.fs.starfarer.api.combat.CombatEngineLayers

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
}

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
