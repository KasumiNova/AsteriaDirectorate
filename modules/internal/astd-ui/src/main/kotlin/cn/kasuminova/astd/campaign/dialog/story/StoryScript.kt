package cn.kasuminova.astd.campaign.dialog.story

/**
 * 故事对话脚本：剧情对话的纯数据定义。
 *
 * 动机：剧情对话需要“可校验、可复用”的工厂入口——脚本只引用 i18n key 与节点 id，
 * 不持有任何游戏对象，因此可以在单元测试里做完整的结构校验（分支收束、不可退出等），
 * 再由 [StoryDialogs] 编译为 `DialogGraph` 运行。
 *
 * key 解析规则：所有台词/选项文本的实际 i18n key = [StoryScript.keyPrefix] + 各行/选项的相对 key。
 */
data class StoryScript(
    /** 脚本 id（日志与 sessionState 命名空间用）。 */
    val id: String,
    /** i18n category id（如 asteria_directorate）。 */
    val category: String,
    /** 台词/选项文本 key 的统一前缀（如 "story.prologue.agent."）。 */
    val keyPrefix: String,
    /** 起始节点 id。 */
    val startNodeId: String,
    val nodes: List<StoryNode>,
    /** 是否允许 Escape 关闭对话；为 true 时 [escapeOptionId] 必填且该选项必须收束到 Close。 */
    val allowEscape: Boolean = false,
    /** Escape 触发的选项 id（同时应作为普通选项出现在某个节点中）。 */
    val escapeOptionId: String? = null,
    /** Escape 选项文本的相对 i18n key；为空时使用 dialog.core.leave。 */
    val escapeTextKey: String? = null,
) {

    /**
     * 结构校验；返回全部问题描述（空列表 = 合法）。
     * 校验为纯逻辑，不触碰游戏 API，供单元测试与编译前置检查共用。
     */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        val byId = LinkedHashMap<String, StoryNode>(nodes.size)
        for (n in nodes) {
            if (byId.put(n.id, n) != null) {
                problems += "节点 id 重复：'${n.id}'"
            }
        }
        if (startNodeId !in byId) {
            problems += "起始节点不存在：'$startNodeId'"
        }

        fun checkAction(action: StoryOptionAction?, owner: String) {
            when (action) {
                null -> {}
                StoryOptionAction.Close -> {}
                is StoryOptionAction.Goto ->
                    if (action.nodeId !in byId) problems += "$owner 跳转到不存在的节点 '${action.nodeId}'"
                is StoryOptionAction.Reply -> checkAction(action.then, owner)
                is StoryOptionAction.Callback -> checkAction(action.then, owner)
            }
        }

        for (n in byId.values) {
            if (n.autoNext != null && n.autoNext !in byId) {
                problems += "节点 '${n.id}' 自动跳转到不存在的节点 '${n.autoNext}'"
            }
            if (n.autoNext != null && n.autoClose) {
                problems += "节点 '${n.id}' 同时配置 autoNext 与 autoClose"
            }
            if ((n.autoNext != null || n.autoClose) && !n.timed) {
                problems += "节点 '${n.id}' 配置了自动出口但不是 timed 节点"
            }
            if ((n.autoNext != null || n.autoClose) && n.lines.isEmpty() && n.resumeLines.isEmpty()) {
                problems += "节点 '${n.id}' 是自动节点但没有任何台词，玩家将看不到任何内容"
            }
            if (n.options.isEmpty() && n.autoNext == null && !n.autoClose) {
                problems += "节点 '${n.id}' 无选项且无自动出口，对话将卡死"
            }
            if ((n.resumeFlagKey != null) != n.resumeLines.isNotEmpty()) {
                problems += "节点 '${n.id}' 的 resumeFlagKey 与 resumeLines 需成对配置"
            }
            for (o in n.options) {
                checkAction(o.action, "节点 '${n.id}' 的选项 '${o.id}'")
                if (o.rereadable && n.rereadSuffixKey == null) {
                    problems += "节点 '${n.id}' 的选项 '${o.id}' 可重读但节点未配置 rereadSuffixKey"
                }
            }
        }

        // 可达性：从起始节点沿 Goto / autoNext 遍历。
        val reachable = LinkedHashSet<String>()
        fun walk(id: String) {
            if (!reachable.add(id)) return
            val n = byId[id] ?: return
            n.autoNext?.let(::walk)
            for (o in n.options) {
                fun walkAction(a: StoryOptionAction?) {
                    when (a) {
                        null -> {}
                        StoryOptionAction.Close -> {}
                        is StoryOptionAction.Goto -> walk(a.nodeId)
                        is StoryOptionAction.Reply -> walkAction(a.then)
                        is StoryOptionAction.Callback -> walkAction(a.then)
                    }
                }
                walkAction(o.action)
            }
        }
        walk(startNodeId)
        for (n in byId.values) {
            if (n.id !in reachable) problems += "节点不可达：'${n.id}'"
        }

        if (allowEscape) {
            val escape = escapeOptionId
            if (escape == null) {
                problems += "allowEscape=true 时必须指定 escapeOptionId"
            } else {
                val found = byId.values.any { n ->
                    n.options.any { o -> o.id == escape && o.action.containsClose() }
                }
                if (!found) problems += "escapeOptionId '$escape' 未对应任何收束到 Close 的选项"
            }
        } else if (escapeOptionId != null) {
            problems += "allowEscape=false 时不应设置 escapeOptionId"
        }

        return problems
    }

    /** 该脚本是否在任何可达路径上允许关闭对话（含 autoClose 与 Close 选项）。 */
    fun hasClosePath(): Boolean =
        nodes.any { n -> n.autoClose || n.options.any { it.action.containsClose() } }
}

/** 判断动作链中是否包含 Close（供校验与测试使用）。 */
fun StoryOptionAction.containsClose(): Boolean = when (this) {
    StoryOptionAction.Close -> true
    is StoryOptionAction.Goto -> false
    is StoryOptionAction.Reply -> then?.containsClose() == true
    is StoryOptionAction.Callback -> then?.containsClose() == true
}

/**
 * 台词行样式：决定输出观感。
 * 环境/旁白用淡入灰显与角色台词区分（见 story 文档基调守则）。
 */
enum class StoryLineStyle { NARRATION, SPEECH }

/**
 * 一条台词。
 *
 * @param key 相对 i18n key（实际 key = 脚本 keyPrefix + key）
 * @param delay 相对上一条输出的延迟（秒），仅 timed 节点生效
 * @param style 输出观感
 */
data class StoryLine(
    val key: String,
    val delay: Float = 0f,
    val style: StoryLineStyle = StoryLineStyle.SPEECH,
)

/** 选项动作（纯数据，编译时映射为 `DialogAction`）。 */
sealed interface StoryOptionAction {

    /** 跳转到指定节点。 */
    data class Goto(val nodeId: String) : StoryOptionAction

    /**
     * 分支应答：立即输出若干台词，之后可继续跳转；
     * [then] 为空时停留当前节点（用于可循环的细节追问）。
     */
    data class Reply(val lines: List<StoryLine>, val then: StoryOptionAction? = null) : StoryOptionAction

    /**
     * 命名回调：campaign 侧按 id 挂载业务逻辑（发放文书、打开终端 UI 等），
     * 之后可继续跳转；[then] 为空时停留当前节点。
     */
    data class Callback(val id: String, val then: StoryOptionAction? = null) : StoryOptionAction

    /** 关闭对话（正常关闭，非取消）。 */
    data object Close : StoryOptionAction
}

/**
 * 对话选项。
 *
 * @param id 选项 id（脚本内唯一，作为 OptionPanel 的 optionData）
 * @param textKey 选项文本的相对 i18n key
 * @param rereadable 可重读：点击后保留在选项列表，文本追加节点配置的“已读”后缀
 * @param sessionFlag 选中后写入 `DialogContext.sessionState` 的标记名（分支风味记录，不进 MemoryAPI）
 */
data class StoryOption(
    val id: String,
    val textKey: String,
    val action: StoryOptionAction,
    val rereadable: Boolean = false,
    val sessionFlag: String? = null,
)

/**
 * 对话节点。
 *
 * @param timed true：台词经延迟队列逐条输出，期间锁定选项（只保留“跳过”）
 * @param lines 进入节点时输出的台词
 * @param resumeFlagKey 中断恢复标记（playerMemory 布尔值）：已存在时改用 [resumeLines]
 * @param resumeLines 中断恢复开场的差分台词
 * @param setMemoryFlags 进入节点时写入 playerMemory 的布尔标记（值恒为 true，在判定 resume 之后写入）
 * @param enterCallback 进入节点时触发的命名回调
 * @param rereadSuffixKey 可重读选项“已读”后缀的相对 i18n key
 * @param autoNext timed 节点台词播完后自动跳转的节点 id
 * @param autoClose timed 节点台词播完后自动关闭对话
 */
data class StoryNode(
    val id: String,
    val timed: Boolean = false,
    val lines: List<StoryLine> = emptyList(),
    val resumeFlagKey: String? = null,
    val resumeLines: List<StoryLine> = emptyList(),
    val setMemoryFlags: List<String> = emptyList(),
    val enterCallback: String? = null,
    val options: List<StoryOption> = emptyList(),
    val rereadSuffixKey: String? = null,
    val autoNext: String? = null,
    val autoClose: Boolean = false,
)
