package cn.kasuminova.astd.campaign.dialog.story

import cn.kasuminova.astd.campaign.dialog.core.DialogContext
import cn.kasuminova.astd.campaign.dialog.core.DialogDsl
import cn.kasuminova.astd.campaign.dialog.core.DialogGraph
import cn.kasuminova.astd.campaign.dialog.core.DialogNode
import cn.kasuminova.astd.campaign.dialog.core.dialogGraph
import cn.kasuminova.astd.internal.i18n.I18n

/**
 * 序章酒馆代办对话图（docs/story/04「序章对话-代办」定稿的 Dialog DSL 实装）。
 *
 * 九节点结构：
 * ```
 * start ──(timed/自动)──► opening ──(timed/自动)──► attitude（态度分支×3）
 *     ──► verify ──(timed/自动)──► question（追问分支×3）──► offer（追问应答并入队首）
 *     ──(timed/自动)──► detail（细节追问×3，可循环重读）──► sign ──(timed/自动)──► end ──► close
 * ```
 *
 * 约束（doc 04）：
 * - 全图无 `Close` 动作、不设 Escape 关闭；唯一出口是 `end` 节点播报完毕后的 `ctx.close()`；
 * - 分支只改文本风味，全部收束到 `sign`；
 * - `detail` 三项追问用 [DialogContext.sessionState] 记已读（已读项加「再问一次」后缀），签字恒可点；
 * - 中断恢复：[StoryDialogBackend.isPrologueAgentMet] 为真时 `start` 加播差异开场白；
 * - 全部文本走 i18n，键前缀 `story.prologue.agent.*`（本文档代码内不含定稿文案）。
 *
 * 节点对象为无状态工厂产物；可变状态全部在 sessionState / [StoryDialogBackend]。
 */
object PrologueAgentDialog {

    const val NODE_START: String = "start"
    const val NODE_OPENING: String = "opening"
    const val NODE_ATTITUDE: String = "attitude"
    const val NODE_VERIFY: String = "verify"
    const val NODE_QUESTION: String = "question"
    const val NODE_OFFER: String = "offer"
    const val NODE_DETAIL: String = "detail"
    const val NODE_SIGN: String = "sign"
    const val NODE_END: String = "end"

    /** 全部节点 id（结构断言用，共九个）。 */
    val NODE_IDS: List<String> = listOf(
        NODE_START, NODE_OPENING, NODE_ATTITUDE, NODE_VERIFY, NODE_QUESTION,
        NODE_OFFER, NODE_DETAIL, NODE_SIGN, NODE_END,
    )

    private const val KEY_PREFIX = "story.prologue.agent"

    /** sessionState：态度分支选择（cautious/pragmatic/joking）。 */
    private const val STATE_ATTITUDE = "prologue.attitude"

    /** sessionState：追问分支选择（format/collapse/skip）。 */
    private const val STATE_QUESTION = "prologue.question"

    /** sessionState：detail 已读追问项集合（MutableSet<String>）。 */
    private const val STATE_DETAIL_READ = "prologue.detail.read"

    /** sessionState：自动跳转节点的「已触发」标记（每节点 onEnter 时复位）。 */
    private const val STATE_AUTO_FIRED = "prologue.autoFired"

    /** sessionState：sign 节点本次签署是否成功（决定播完进 end 还是回 detail 重试）。 */
    private const val STATE_SIGN_OK = "prologue.signOk"

    private val CAT = I18n.Categories.MOD

    /** 构建序章对话图（每次对话新建，图内节点不持有可变状态）。 */
    fun createGraph(): DialogGraph = dialogGraph(start = NODE_START) {
        node(NODE_START, startNode())
        node(NODE_OPENING, autoNode(NODE_OPENING, NODE_ATTITUDE) { ctx ->
            speech(ctx, "opening.0", 0.9f)
        })
        node(NODE_ATTITUDE, attitudeNode())
        node(NODE_VERIFY, verifyNode())
        node(NODE_QUESTION, questionNode())
        node(NODE_OFFER, offerNode())
        node(NODE_DETAIL, detailNode())
        node(NODE_SIGN, signNode())
        node(NODE_END, endNode())
    }

    // ─── start：环境描写 + 代办开场（中断恢复时加播差异开场白），播完自动进 opening ───

    private fun startNode(): DialogNode = autoNode(NODE_START, NODE_OPENING) { ctx ->
        if (StoryDialogBackends.get().isPrologueAgentMet()) {
            speech(ctx, "start.resume", 0.4f)
        }
        narration(ctx, "start.0", 0.4f)
        narration(ctx, "start.1", 1.0f)
        speech(ctx, "start.2", 0.9f)
    }

    // ─── attitude：态度分支×3（只改回显风味，统一进 verify） ───

    private fun attitudeNode(): DialogNode = DialogDsl.node { ctx ->
        listOf(
            attitudeOption(ctx, "cautious"),
            attitudeOption(ctx, "pragmatic"),
            attitudeOption(ctx, "joking"),
        )
    }

    private fun attitudeOption(ctx: DialogContext, choice: String) = DialogDsl.option(
        id = "attitude_$choice",
        text = I18n[CAT, "$KEY_PREFIX.attitude.option.$choice"],
        action = DialogDsl.run(then = DialogDsl.goto(NODE_VERIFY)) { c ->
            c.sessionState[STATE_ATTITUDE] = choice
        },
    )

    // ─── verify：档案核验（旧制编号，第一处「不对劲」），播完自动进 question ───

    private fun verifyNode(): DialogNode = autoNode(NODE_VERIFY, NODE_QUESTION) { ctx ->
        narration(ctx, "verify.0", 0.4f)
        speech(ctx, "verify.1", 1.0f, "playerName" to StoryDialogBackends.get().playerName())
        narration(ctx, "verify.2", 1.0f)
        speech(ctx, "verify.3", 0.9f)
    }

    // ─── question：追问分支×3（1/2 的应答并入 offer 队首；3 直接进 offer） ───

    private fun questionNode(): DialogNode = DialogDsl.node {
        listOf(
            questionOption("format"),
            questionOption("collapse"),
            questionOption("skip"),
        )
    }

    private fun questionOption(choice: String) = DialogDsl.option(
        id = "question_$choice",
        text = I18n[CAT, "$KEY_PREFIX.question.option.$choice"],
        action = DialogDsl.run(then = DialogDsl.goto(NODE_OFFER)) { c ->
            c.sessionState[STATE_QUESTION] = choice
        },
    )

    // ─── offer：出示文书（选过追问 1/2 时先播应答 + 追加句），播完自动进 detail ───

    private fun offerNode(): DialogNode = autoNode(NODE_OFFER, NODE_DETAIL) { ctx ->
        when (ctx.sessionState[STATE_QUESTION] as? String) {
            "format", "collapse" -> {
                speech(ctx, "answer.common.0", 0.4f)
                narration(ctx, "answer.common.1", 0.9f)
                speech(ctx, "answer.common.2", 0.9f)
                speech(ctx, "answer.${ctx.sessionState[STATE_QUESTION]}.extra", 0.9f)
            }
        }
        narration(ctx, "offer.0", 0.6f)
        speech(ctx, "offer.1", 1.0f)
        speech(ctx, "offer.2", 0.9f)
    }

    // ─── detail：细节追问×3（可循环重读，已读加「再问一次」后缀）+ 恒在末位的签字 ───

    /** 追问应答行表：topic → 逐行（键后缀, 是否环境叙述）。行表与 i18n 文案表一一对应。 */
    private val DETAIL_ANSWERS: Map<String, List<Pair<String, Boolean>>> = mapOf(
        "why_me" to listOf("0" to false, "1" to true),
        "payment" to listOf("0" to false, "1" to false),
        "decline" to listOf("0" to false, "1" to true, "2" to false),
    )

    private fun detailNode(): DialogNode = DialogDsl.node { ctx ->
        val read = detailRead(ctx)
        val options = DETAIL_ANSWERS.keys.map { topic ->
            val keySuffix = if (topic in read) ".reread" else ""
            DialogDsl.option(
                id = "detail_$topic",
                text = I18n[CAT, "$KEY_PREFIX.detail.option.$topic$keySuffix"],
                action = DialogDsl.run { c ->
                    detailRead(c).add(topic)
                    for ((lineKey, isNarration) in DETAIL_ANSWERS.getValue(topic)) {
                        if (isNarration) narration(c, "detail.answer.$topic.$lineKey", 0.9f)
                        else speech(c, "detail.answer.$topic.$lineKey", 0.5f)
                    }
                },
            )
        }
        options + DialogDsl.option(
            id = "detail_sign",
            text = I18n[CAT, "$KEY_PREFIX.detail.option.sign"],
            action = DialogDsl.goto(NODE_SIGN),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun detailRead(ctx: DialogContext): MutableSet<String> =
        ctx.sessionState.getOrPut(STATE_DETAIL_READ) { LinkedHashSet<String>() } as MutableSet<String>

    // ─── sign：签字（onEnter 执行生涯侧签署副作用）。成功播完自动进 end；失败播差异提示并回 detail 重试 ───

    /**
     * 签署副作用失败（返回 false）时：播差异错误提示（HUD + 对话内文本），播完回到 detail
     * 节点——签字选项恒在末位，可再次进入本节点重试。「谈话已开始」标记（met）由
     * BarEvent 在进入对话时写入，与签署成败无关，故重进对话仍走续谈开场白。
     * 副作用链自身幂等（见 campaign 侧 StoryDialogInstall）：重试不重复发文书/标记星图。
     */
    private fun signNode(): DialogNode = DialogDsl.timedNode(
        onEnter = { ctx ->
            ctx.sessionState[STATE_AUTO_FIRED] = false
            val backend = StoryDialogBackends.get()
            if (backend.onPrologueSigned()) {
                ctx.sessionState[STATE_SIGN_OK] = true
                ctx.hudMessageI18n(CAT, "story.guide.prologue.signed")
                ctx.hudMessageI18n(CAT, "story.guide.prologue.station")
                narration(ctx, "sign.0", 0.4f)
                speech(ctx, "sign.1", 1.0f)
                narration(ctx, "sign.2", 0.9f)
                speech(ctx, "sign.3", 0.9f)
            } else {
                ctx.sessionState[STATE_SIGN_OK] = false
                ctx.hudMessageI18n(CAT, "story.guide.prologue.sign_failed")
                narration(ctx, "sign.failed.0", 0.4f)
                speech(ctx, "sign.failed.1", 0.9f)
            }
        },
        onAdvance = { ctx, _ ->
            if (!ctx.textQueue.hasPending && ctx.sessionState[STATE_AUTO_FIRED] != true) {
                ctx.sessionState[STATE_AUTO_FIRED] = true
                ctx.goto(if (ctx.sessionState[STATE_SIGN_OK] == true) NODE_END else NODE_DETAIL)
            }
        },
    )

    // ─── end：告别（酒保视角回收第三处「不对劲」），播完自动关闭对话 ───

    private fun endNode(): DialogNode {
        return DialogDsl.timedNode(
            onEnter = { ctx ->
                ctx.sessionState[STATE_AUTO_FIRED] = false
                narration(ctx, "end.0", 0.4f)
                speech(ctx, "end.1", 1.0f)
                narration(ctx, "end.2", 0.9f)
            },
            onAdvance = { ctx, _ ->
                if (!ctx.textQueue.hasPending && ctx.sessionState[STATE_AUTO_FIRED] != true) {
                    ctx.sessionState[STATE_AUTO_FIRED] = true
                    ctx.close()
                }
            },
        )
    }

    // ─── 构造工具 ───

    /**
     * 「逐条播报 + 播完自动跳转」节点（timedNode：队列未播完时锁定选项，仅可跳过）。
     * [nodeId] 仅用于区分 sessionState 的自动跳转标记。
     */
    private fun autoNode(
        nodeId: String,
        next: String,
        enqueueLines: (DialogContext) -> Unit,
    ): DialogNode = DialogDsl.timedNode(
        onEnter = { ctx ->
            ctx.sessionState[STATE_AUTO_FIRED] = false
            enqueueLines(ctx)
        },
        onAdvance = { ctx, _ ->
            if (!ctx.textQueue.hasPending && ctx.sessionState[STATE_AUTO_FIRED] != true) {
                ctx.sessionState[STATE_AUTO_FIRED] = true
                ctx.goto(next)
            }
        },
    )

    /** 代办台词：直接输出（公文规程体，无淡入）。 */
    private fun speech(ctx: DialogContext, keySuffix: String, delay: Float, vararg vars: Pair<String, Any?>) {
        ctx.enqueueI18n(CAT, "$KEY_PREFIX.$keySuffix", delay, null, *vars)
    }

    /** 环境/动作叙述：淡入输出，与角色台词区分观感（doc 04 实现映射）。 */
    private fun narration(ctx: DialogContext, keySuffix: String, delay: Float) {
        ctx.enqueueI18nFading(CAT, "$KEY_PREFIX.$keySuffix", delay, fadeIn = 0.3f)
    }
}
