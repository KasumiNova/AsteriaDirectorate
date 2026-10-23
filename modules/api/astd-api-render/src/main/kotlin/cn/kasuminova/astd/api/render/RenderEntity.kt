package cn.kasuminova.astd.api.render

/**
 * 渲染树节点。节点持有子节点，并管理后端资源的创建、更新、绘制和释放。
 *
 * 生命周期方法仅在战斗主线程调用；[onAttach] 与 [onDetach] 必须幂等。
 */
interface RenderEntity {

    /** 同一父节点下用于查找和替换子节点的标识。 */
    val id: String

    /** 绘制层。 */
    val layer: RenderLayer

    /** 同一 [layer] 内的绘制顺序，较小值先绘制。 */
    val renderOrder: Int
        get() = 0

    /** 按 [layer]、[renderOrder] 和插入顺序排列的子节点。 */
    val children: List<RenderEntity>

    /**
     * 添加子节点。已附着的节点在下一次 [advance] 时附着子节点；
     * 同 id 的旧子节点会被释放并替换。
     */
    fun addChild(child: RenderEntity)

    /** 按 id 移除子节点并调用其 [onDetach]；不存在时不执行操作。 */
    fun removeChild(id: String)

    /**
     * 创建后端资源并附着子节点。重复调用返回首次结果。
     * @return 是否附着成功；需要战斗引擎的节点在 [RenderContext.engine] 为 null 时必须返回 false。
     */
    fun onAttach(ctx: RenderContext): Boolean

    /** 按 [RenderContext.frame] 更新后端资源，可在遍历前增删子节点。 */
    fun advance(ctx: RenderContext, amount: Float)

    /** 绘制自定义 GL 内容；完全由 BoxUtil 绘制的节点无需执行操作。 */
    fun render(ctx: RenderContext)

    /** 开始一次淡出并传播到全部子节点。 */
    fun beginFadeOut(reason: FadeReason, seconds: Float)

    /** 释放后端资源并分离全部子节点；未附着时不执行操作。 */
    fun onDetach()
}
