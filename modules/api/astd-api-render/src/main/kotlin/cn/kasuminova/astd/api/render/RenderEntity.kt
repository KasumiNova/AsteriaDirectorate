package cn.kasuminova.astd.api.render

/**
 * 一个可渲染节点：持有子节点、自带绘制层、具备完整的保留模式生命周期（设计 D1/D4）。
 *
 * 动机：弹体/光束特效由一棵 RenderEntity 树描述，节点可任意嵌套并在运行时增删（root component 动态管理）。
 * 生命周期与后端（BoxUtil 实体 / 自定义 GL 网格）的“创建一次、逐帧更新、结束删除”一一对应，避免句柄泄漏。
 */
interface RenderEntity {

    /** 节点标识；同层内用于定位/去重，不参与绘制排序（绘制序由 [layer] 决定，设计 D3）。 */
    val id: String

    /** 本节点的绘制层（复用原版 CombatEngineLayers）。 */
    val layer: RenderLayer

    /**
     * 同 [layer] 内的次级绘制序（设计 D3）：小者先绘制、在下（如 glow=100 < body=200）。
     * 默认 0；不同 layer 之间仍以 [layer] 为准。
     */
    val renderOrder: Int
        get() = 0

    /** 子节点，先按 [layer] 升序、同层再按 [renderOrder] 升序，均稳定保持插入序（设计 D3）；运行时可增删（设计 D4）。 */
    val children: List<RenderEntity>

    /**
     * 运行时挂子节点：加入子集，若已附着则由下一次 [advance] 惰性 onAttach 并纳入遍历
     * （root component 在自身 advance 内按宿主状态动态添加，设计 D4/§11）。同 id 覆盖时释放被顶替者。
     */
    fun addChild(child: RenderEntity)

    /** 运行时按 id 摘子节点：立即 [onDetach] 并移除；无此 id 则忽略。 */
    fun removeChild(id: String)

    /**
     * 首帧：创建后端句柄并递归 onAttach 子节点。幂等——重复调用直接返回首次结果。
     * @return 是否创建成功；engine 为 null（无引擎测试）时后端节点应返回 false（设计 D1）。
     */
    fun onAttach(ctx: RenderContext): Boolean

    /** 每帧：用 [RenderContext.frame] 更新后端句柄；root component 可在此按宿主状态增删 children。 */
    fun advance(ctx: RenderContext, amount: Float)

    /** 立即模式绘制：仅自定义 GL 节点/LWJGL 预览有意义，BoxUtil 节点为空操作（设计 D1）。 */
    fun render(ctx: RenderContext)

    /** 触发一次性淡出，递归传播到 children。 */
    fun beginFadeOut(reason: FadeReason, seconds: Float)

    /** 释放后端句柄，递归 onDetach 全部 children。幂等——未附着时为空操作。 */
    fun onDetach()
}
