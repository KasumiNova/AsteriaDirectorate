package cn.kasuminova.astd.api.buff

/**
 * 舰船或武器的战斗状态。
 *
 * 所有方法仅在战斗主线程调用。实现负责在 [advance] 中更新状态与玩家反馈，在 [onRemove] 中撤销属性修改。
 */
interface Buff {
    /**
     * 全局唯一 id：同时充当 customData 键段与 stat modifierId 前缀，须以 `astd_` 开头。
     */
    val id: String

    /**
     * 生命周期策略：决定 BuffTickPlugin 何时回收本 Buff。
     */
    val lifetime: BuffLifetime

    /**
     * 宿主状态是否仍有效（宿主死亡/引用失效时返回 false，插件据此回收）。
     * 实现必须轻量、无副作用。
     */
    fun isHostValid(): Boolean

    /**
     * 每帧心跳钩子。[amount] 为经时间膨胀修正前的真实秒数；
     * 叠层衰减、状态表刷新、玩家可见反馈（HUD/浮字）在此发生。
     * 默认空实现（非 StackableBuff 的纯标记可不动）。
     */
    fun advance(amount: Float) {}

    /**
     * 回收钩子：插件移除本 Buff 前调用一次，用于 unapply stat 修改。默认空实现。
     */
    fun onRemove() {}
}

/**
 * Buff 的生命周期策略：决定 BuffTickPlugin 的回收时机。
 */
enum class BuffLifetime {
    /**
     * 随宿主实体存在（宿主 hulk/失效即由插件回收）。电荷针刺淤积、穷距叠层用此档。
     */
    HOST_BOUND,

    /**
     * 自管理生命周期：advance 内自行判定终结并调用 `BuffHost.remove`。湮灭涡旋吞噬池类状态用此档。
     */
    SELF_MANAGED,
}
