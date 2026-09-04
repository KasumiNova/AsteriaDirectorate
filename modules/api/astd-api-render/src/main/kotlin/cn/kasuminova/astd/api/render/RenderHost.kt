package cn.kasuminova.astd.api.render

/**
 * 渲染宿主：一棵 [RenderEntity] 树所依附的对象（弹体或光束），使核心模型宿主中立（设计 D6）。
 *
 * 动机：树 95% 的节点只读 [RenderContext.frame]，与宿主类型无关；只有少数 root component 需要宿主特有
 * 查询（如光束的命中目标、弹体的 cutTrails）。这些查询由具体宿主实现（`ProjectileHost` / `BeamHost`）
 * 暴露，届时向下转型获取——此处只约定所有宿主共有的最小契约。
 *
 * 注：`ProjectileHost` 随驱动接线（P2）落地，`BeamHost` 随光束迁移（P7）落地；本接口不预先声明它们，
 * 避免出现无实现的接口。
 */
interface RenderHost {

    /** 宿主唯一标识，用于日志定位与驱动侧去重。 */
    val hostId: String
}
