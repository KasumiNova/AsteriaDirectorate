package cn.kasuminova.astd.campaign.dialog.story

import cn.kasuminova.astd.campaign.dialog.core.DialogContext
import cn.kasuminova.astd.campaign.dialog.core.GraphDialogPlugin
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorEntityToken

/**
 * 序章「代办」酒馆遭遇对话（docs/story/04-序章对话-代办.md 定稿落地）。
 *
 * - 不可退出：脚本不声明任何 Close 选项、不注册 Escape；唯一出口是走完 `end` 节点自动关闭；
 * - 分支收束：态度/追问分支只改变文本风味，全部收束到「接下文书 XW-c206-0447／核销-03」；
 * - MemoryAPI 标记：首次进入写 [MEM_MET]（中断恢复差分开场），签字节点写 [MEM_ACCEPTED]；
 * - campaign 侧业务（发放文书物品、生成目标舰队与坐标）经 [CALLBACK_ACCEPT] 命名回调挂载。
 */
object PrologueAgentDialog {

    /** playerMemory：已与代办开始谈话（用于强退后重进酒馆的差分开场白）。 */
    const val MEM_MET: String = "\$astd_prologue_agent_met"

    /** playerMemory：已接下文书（剧情主标记，见 04 文档 sign 节点动作）。 */
    const val MEM_ACCEPTED: String = "\$astd_prologue_accepted"

    /**
     * 命名回调：签字完成（进入 sign 节点）时触发。
     * campaign 侧在此发放文书物品、生成目标舰队与坐标。
     */
    const val CALLBACK_ACCEPT: String = "prologue.accept"

    private const val PREFIX: String = "story.prologue.agent."

    /** 构建脚本（纯数据，可在单元测试中做结构校验）。 */
    fun script(): StoryScript = StoryScript(
        id = "astd_prologue_agent",
        category = I18n.Categories.MOD.id,
        keyPrefix = PREFIX,
        startNodeId = "start",
        allowEscape = false,
        nodes = listOf(
            StoryNode(
                id = "start",
                timed = true,
                lines = listOf(
                    StoryLine("start.0", delay = 0f, style = StoryLineStyle.NARRATION),
                    StoryLine("start.1", delay = 1.2f, style = StoryLineStyle.NARRATION),
                    StoryLine("start.2", delay = 1.6f, style = StoryLineStyle.NARRATION),
                    StoryLine("start.3", delay = 1.0f),
                ),
                resumeFlagKey = MEM_MET,
                resumeLines = listOf(StoryLine("start.resume.0", delay = 0f)),
                setMemoryFlags = listOf(MEM_MET),
                autoNext = "opening",
            ),
            StoryNode(
                id = "opening",
                lines = listOf(StoryLine("opening.0")),
                options = listOf(
                    StoryOption("attitude_cautious", "option.attitude.cautious",
                        StoryOptionAction.Goto("verify"), sessionFlag = "attitude_cautious"),
                    StoryOption("attitude_pragmatic", "option.attitude.pragmatic",
                        StoryOptionAction.Goto("verify"), sessionFlag = "attitude_pragmatic"),
                    StoryOption("attitude_teasing", "option.attitude.teasing",
                        StoryOptionAction.Goto("verify"), sessionFlag = "attitude_teasing"),
                ),
            ),
            StoryNode(
                id = "verify",
                timed = true,
                lines = listOf(
                    StoryLine("verify.0", delay = 0f, style = StoryLineStyle.NARRATION),
                    StoryLine("verify.1", delay = 1.2f),
                    StoryLine("verify.2", delay = 1.8f, style = StoryLineStyle.NARRATION),
                    StoryLine("verify.3", delay = 1.0f),
                ),
                autoNext = "question",
            ),
            StoryNode(
                id = "question",
                options = listOf(
                    StoryOption(
                        "question_format", "option.question.format",
                        StoryOptionAction.Reply(
                            lines = revealLines + StoryLine("question.extra.format"),
                            then = StoryOptionAction.Goto("offer"),
                        ),
                        sessionFlag = "question_format",
                    ),
                    StoryOption(
                        "question_domain", "option.question.domain",
                        StoryOptionAction.Reply(
                            lines = revealLines + StoryLine("question.extra.domain"),
                            then = StoryOptionAction.Goto("offer"),
                        ),
                        sessionFlag = "question_domain",
                    ),
                    StoryOption("question_business", "option.question.business",
                        StoryOptionAction.Goto("offer"), sessionFlag = "question_business"),
                ),
            ),
            StoryNode(
                id = "offer",
                timed = true,
                lines = listOf(
                    StoryLine("offer.0", delay = 0f, style = StoryLineStyle.NARRATION),
                    StoryLine("offer.1", delay = 1.6f),
                    StoryLine("offer.2", delay = 1.4f),
                ),
                autoNext = "detail",
            ),
            StoryNode(
                id = "detail",
                rereadSuffixKey = "detail.reread_suffix",
                options = listOf(
                    StoryOption(
                        "detail_why_me", "detail.option.why_me",
                        StoryOptionAction.Reply(
                            lines = listOf(
                                StoryLine("detail.why_me.0"),
                                StoryLine("detail.why_me.1"),
                            ),
                        ),
                        rereadable = true,
                    ),
                    StoryOption(
                        "detail_pay", "detail.option.pay",
                        StoryOptionAction.Reply(
                            lines = listOf(
                                StoryLine("detail.pay.0"),
                                StoryLine("detail.pay.1"),
                            ),
                        ),
                        rereadable = true,
                    ),
                    StoryOption(
                        "detail_refuse", "detail.option.refuse",
                        StoryOptionAction.Reply(
                            lines = listOf(
                                StoryLine("detail.refuse.0"),
                                StoryLine("detail.refuse.1", style = StoryLineStyle.NARRATION),
                                StoryLine("detail.refuse.2"),
                            ),
                        ),
                        rereadable = true,
                    ),
                    // 签字恒在末位，不受细节追问阅读进度影响。
                    StoryOption("detail_sign", "detail.option.sign", StoryOptionAction.Goto("sign")),
                ),
            ),
            StoryNode(
                id = "sign",
                timed = true,
                lines = listOf(
                    StoryLine("sign.0", delay = 0f, style = StoryLineStyle.NARRATION),
                    StoryLine("sign.1", delay = 1.2f),
                    StoryLine("sign.2", delay = 1.6f, style = StoryLineStyle.NARRATION),
                    StoryLine("sign.3", delay = 0.8f),
                ),
                setMemoryFlags = listOf(MEM_ACCEPTED),
                enterCallback = CALLBACK_ACCEPT,
                autoNext = "end",
            ),
            StoryNode(
                id = "end",
                timed = true,
                lines = listOf(
                    StoryLine("end.0", delay = 0f, style = StoryLineStyle.NARRATION),
                    StoryLine("end.1", delay = 1.0f),
                    StoryLine("end.2", delay = 1.4f, style = StoryLineStyle.NARRATION),
                ),
                autoClose = true,
            ),
        ),
    )

    /** 追问分支的公共应答（两处「不对劲」的正式揭晓），选 1 / 选 2 各自追加一句。 */
    private val revealLines: List<StoryLine>
        get() = listOf(
            StoryLine("question.reveal.0"),
            StoryLine("question.reveal.1", style = StoryLineStyle.NARRATION),
            StoryLine("question.reveal.2"),
        )

    /**
     * 创建对话插件（不可 Escape 退出）。
     *
     * @param callbacks 命名回调表；至少应挂载 [CALLBACK_ACCEPT]（发放文书/生成目标）。
     */
    fun createPlugin(callbacks: Map<String, (DialogContext) -> Unit> = emptyMap()): GraphDialogPlugin =
        StoryDialogs.createPlugin(script(), vars = { playerVars() }, callbacks = callbacks)

    /** 对交互目标弹出代办对话。 */
    fun open(
        target: SectorEntityToken,
        callbacks: Map<String, (DialogContext) -> Unit> = emptyMap(),
    ): Boolean = StoryDialogs.open(target, script(), vars = { playerVars() }, callbacks = callbacks)

    private fun playerVars(): List<Pair<String, Any?>> =
        listOf("playerName" to Global.getSector().playerPerson.nameString)
}
