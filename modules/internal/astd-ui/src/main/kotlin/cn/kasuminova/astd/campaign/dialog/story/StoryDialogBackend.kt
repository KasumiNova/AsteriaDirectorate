package cn.kasuminova.astd.campaign.dialog.story

/**
 * 剧情对话访问生涯层（astd-campaign）能力的桥接口。
 *
 * 动机：模块依赖方向为 ui ← campaign，astd-ui 内的剧情对话（酒馆代办 BarEvent 等）
 * 不得直接引用 campaign 层类（BountyState / MainBountyBridge / StoryWorldIds）。
 * 本接口收敛序章对话所需的全部生涯侧副作用，由 campaign 侧实现并经
 * [StoryDialogBackends] 注入（先例：BuffBackends + BuffInstall）。
 *
 * 实现方必须为无状态对象：BarEvent 会被 XStream 存档序列化，
 * 事件类只持有本 holder 的引用路径，不缓存实现实例字段以外的可变状态。
 */
interface StoryDialogBackend {

    /**
     * 序章工单是否已接取（签署完成）。
     * 判定口径：玩家舰队 memory 标记或生涯状态（已挂出/已击毁/已核销/承包商已注册）。
     */
    fun isPrologueAccepted(): Boolean

    /**
     * 代办谈话是否已开始过（中断恢复标记，doc 04「中断恢复」）。
     * 已谈过但未签署时，下次触发改用差异开场白。
     */
    fun isPrologueAgentMet(): Boolean

    /** 写入「谈话已开始」标记（玩家 memory，持久化）。 */
    fun markPrologueAgentMet()

    /** 玩家指挥官姓名（verify 节点档案核验台词的填空；实现方直接读 sector.playerPerson）。 */
    fun playerName(): String

    /**
     * 签署动作的全部生涯侧副作用（doc 04 sign 节点动作）：
     * 写入已接取标记、挂出序章工单（MainBountyBridge.acceptPrologueWorkOrder）、
     * 发放委托文书物品、在星图上标记分局空间站。
     *
     * 实现方必须幂等：签署成功后重复调用不重复发文书/不重复标记星图；
     * 失败时不得写入已接取标记（对话侧据此保留重试路径）。
     *
     * @return 是否成功（工单挂出失败等异常已由实现方记日志，调用方据此选择提示文案）
     */
    fun onPrologueSigned(): Boolean
}

/** [StoryDialogBackend] 持有者。未注入时访问抛错（装配顺序问题，不静默兜底）。 */
object StoryDialogBackends {

    @Volatile
    private var current: StoryDialogBackend? = null

    fun install(backend: StoryDialogBackend) {
        current = backend
    }

    fun get(): StoryDialogBackend =
        current ?: throw IllegalStateException(
            "StoryDialogBackend 未注入：mod 插件 onApplicationLoad 应调用 campaign 侧的 StoryDialogInstall.install()",
        )
}
