package cn.kasuminova.astd.api.buff

/**
 * 舰船/武器级战斗状态的最小承载单位。
 *
 * 动机：统一首批叠层机制（电荷针刺淤积、穷距持续演算等）的存取与生命周期语义，
 * 替代各自手搓 customData 读写的散件实现；与既有 `StackingShipBuffs`（到期一次性清零语义）并存，
 * 本接口族面向「连续流失/窗口流失」等 `StackingShipBuffs` 不覆盖的语义。
 *
 * Buff 本身不渲染、不分正负中性；任何特殊数值机制必须配玩家可见表现（HUD/特效/浮字），
 * 由具体 Buff 实现在 [advance] 内调用 `maintainStatusForPlayerShip` / 浮字 / 粒子通道表达，
 * 禁止有机制无反馈。
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
