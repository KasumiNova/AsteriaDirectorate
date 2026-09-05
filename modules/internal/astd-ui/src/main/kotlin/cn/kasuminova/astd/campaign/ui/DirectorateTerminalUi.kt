package cn.kasuminova.astd.campaign.ui

import com.fs.starfarer.api.ui.CustomPanelAPI

/**
 * 已创建终端的语义交互入口，供键盘导航、剧情编排及集成检查复用。
 * 所有操作经过当前布局的原生按钮注册表与同一个面板事件处理器，不直接调用业务数据源。
 */
interface DirectorateTerminalUi {
    /** 读取已实际构建的布局与原生按钮状态；不会刷新数据源或创建面板。 */
    fun view(): TerminalView

    /**
     * 将语义控件投递至原生面板的 buttonPressed 路径。
     * 返回值仅表示事件是否被当前可用控件受理；业务结果应读取刷新后的视图和业务状态。
     * 未创建、已关闭、未注册或禁用的控件均不能执行业务动作。
     */
    fun press(control: TerminalControl): TerminalPress

    /**
     * 经游戏 CustomDialogCallback.dismissCustomDialog(1) 取消终端，恢复承载的交互对话。
     * 未打开或已经请求关闭时返回 false；实际关闭以 [TerminalView.closed] 的游戏回调为准。
     */
    fun close(): Boolean
}

/** 与原生按钮数据相同的语义标识；不同列表使用独立类型，旧页签事件不能操作新页签。 */
sealed interface TerminalControl {
    /** 顶部页签按钮；[tab] 为要显示的页面。 */
    data class Tab(val tab: TerminalTab) : TerminalControl
    /** 工单列表行；[id] 为业务工单标识。 */
    data class Order(val id: String) : TerminalControl
    /** 已解锁档案行；[id] 为档案标识。 */
    data class Archive(val id: String) : TerminalControl
    /** 账户结局文书行；[id] 为结局标识。 */
    data class Ending(val id: String) : TerminalControl

    /** 当前文书的操作按钮，不跨页签或工单状态保留。 */
    enum class Action : TerminalControl {
        /** 接取当前待接取工单。 */
        ACCEPT,
        /** 追踪当前执行中工单。 */
        TRACK,
        /** 核销当前已回收工单。 */
        DELIVER,
        /** 签署当前审阅文书。 */
        SIGN_ENDING,
        /** 搁置文书并返回账户。 */
        ENDING_BACK,
    }
}

/** 事件分派结果，不等同于赏金接取或核销成功。 */
enum class TerminalPress {
    /** 已经过当前原生按钮的启用检查，并执行统一事件处理。 */
    DISPATCHED,
    /** 终端尚未创建或已经关闭。 */
    NOT_OPEN,
    /** 当前布局没有该按钮，例如旧页签事件或重复接取/交付。 */
    NOT_PRESENT,
    /** 当前原生按钮被禁用。 */
    DISABLED,
}

/** 从原生 ButtonAPI 即时读取的控件状态，不向调用方暴露可修改的按钮。 */
data class TerminalButtonView(
    /** 原生按钮的启用状态。 */
    val enabled: Boolean,
    /** 原生列表复选按钮的勾选状态。 */
    val checked: Boolean,
    /** 原生按钮布局宽度。 */
    val width: Float,
    /** 原生按钮布局高度。 */
    val height: Float,
)

/** 已构建视图的观察值；构造 delegate 或拉取业务快照本身不会产生 opened 证据。 */
data class TerminalView(
    /** 游戏 createCustomDialog 传入的真实根面板；未创建时为 null。 */
    val panel: CustomPanelAPI?,
    /** 已创建且未收到原生取消/确认关闭回调。 */
    val open: Boolean,
    /** 已收到游戏原生对话关闭回调。 */
    val closed: Boolean,
    /** 每次完整构建并挂载布局后递增。 */
    val layoutRevision: Long,
    /** 根面板实际 render 回调完成次数，用于区分创建与显示。 */
    val renderedFrames: Long,
    /** 本次布局已挂到根面板的顶层组件数量。 */
    val componentCount: Int,
    /** 当前已构建页签。 */
    val tab: TerminalTab,
    /** 已构建工单详情的选中标识，跨页签保留。 */
    val selectedOrderId: String?,
    /** 当前快照中选中工单的状态。 */
    val selectedOrderStatus: WorkOrderStatus?,
    /** 已构建档案阅读器的选中标识，跨页签保留。 */
    val selectedArchiveId: String?,
    /** 正在审阅的结局文书，取消或切页签会清除。 */
    val reviewingEndingId: String?,
    /** 当前布局注册的原生按钮，只包含实际创建的控件。 */
    val buttons: Map<TerminalControl, TerminalButtonView>,
)
