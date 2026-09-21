package cn.kasuminova.astd.campaign.dialog.story

import cn.kasuminova.astd.campaign.dialog.DialogTestRig
import cn.kasuminova.astd.campaign.dialog.core.DialogAction
import cn.kasuminova.astd.campaign.dialog.core.DialogContext
import cn.kasuminova.astd.campaign.dialog.core.DialogGraph
import cn.kasuminova.astd.campaign.dialog.core.GraphDialogPlugin
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrologueAgentDialogTest {

    private class StubBackend(
        var accepted: Boolean = false,
        var met: Boolean = false,
    ) : StoryDialogBackend {
        var signedCount = 0

        /** 为真时下一次签署失败（用于失败提示/重试路径测试）。 */
        var failNextSign = false

        override fun isPrologueAccepted(): Boolean = accepted
        override fun isPrologueAgentMet(): Boolean = met
        override fun markPrologueAgentMet() {
            met = true
        }

        override fun playerName(): String = "测试指挥官"
        override fun onPrologueSigned(): Boolean {
            signedCount++
            if (failNextSign) {
                failNextSign = false
                return false
            }
            accepted = true
            return true
        }
    }

    private fun install(backend: StoryDialogBackend = StubBackend()): StoryDialogBackend {
        StoryDialogBackends.install(backend)
        return backend
    }

    // ─── 图结构校验（真实 DialogGraph 对象驱动，非源码检查） ───

    /** 执行动作树（探针用）：Run 执行 block 与 then，Goto/Close 经 ctx 请求捕获。 */
    private fun exec(action: DialogAction, ctx: DialogContext) {
        when (action) {
            is DialogAction.Goto -> ctx.goto(action.nodeId)
            is DialogAction.Close -> ctx.close(action.asCancel)
            is DialogAction.Run -> {
                action.block(ctx)
                action.then?.let { exec(it, ctx) }
            }
        }
    }

    private fun collectTargets(action: DialogAction, into: MutableList<String>) {
        when (action) {
            is DialogAction.Goto -> into += action.nodeId
            is DialogAction.Close -> {}
            is DialogAction.Run -> action.then?.let { collectTargets(it, into) }
        }
    }

    private fun containsClose(action: DialogAction): Boolean = when (action) {
        is DialogAction.Close -> true
        is DialogAction.Goto -> false
        is DialogAction.Run -> action.then?.let { containsClose(it) } ?: false
    }

    /** 驱动「自动播报」节点：onEnter 后推进队列与 onAdvance，直到捕获 goto/close。 */
    private fun driveAuto(graph: DialogGraph, nodeId: String, rig: DialogTestRig): DialogTestRig.Probe {
        val (ctx, probe) = rig.newProbeContext()
        val node = graph.requireNode(nodeId)
        node.onEnter(ctx)
        var guard = 0
        while (probe.goto == null && probe.closed == null && guard++ < 4000) {
            ctx.textQueue.advance(0.05f)
            node.onAdvance(ctx, 0.05f)
        }
        return probe
    }

    @Test
    fun `graph declares exactly the nine documented nodes`() {
        install()
        val graph = PrologueAgentDialog.createGraph()
        assertEquals(PrologueAgentDialog.NODE_IDS, graph.nodes.keys.toList())
        assertEquals(PrologueAgentDialog.NODE_START, graph.startNodeId)
    }

    @Test
    fun `all nodes reachable from start and the only exit is end node close`() {
        install()
        val graph = PrologueAgentDialog.createGraph()
        val rig = DialogTestRig()

        // 自动播报边（probe 驱动）
        val autoEdges = mapOf(
            PrologueAgentDialog.NODE_START to PrologueAgentDialog.NODE_OPENING,
            PrologueAgentDialog.NODE_OPENING to PrologueAgentDialog.NODE_ATTITUDE,
            PrologueAgentDialog.NODE_VERIFY to PrologueAgentDialog.NODE_QUESTION,
            PrologueAgentDialog.NODE_OFFER to PrologueAgentDialog.NODE_DETAIL,
            PrologueAgentDialog.NODE_SIGN to PrologueAgentDialog.NODE_END,
        )
        for ((from, expected) in autoEdges) {
            val probe = driveAuto(graph, from, rig)
            assertEquals(expected, probe.goto, "节点 $from 播完后应自动跳到 $expected")
            assertNull(probe.closed, "节点 $from 不应触发关闭")
        }

        // end：播完自动 close(asCancel=false)
        val endProbe = driveAuto(graph, PrologueAgentDialog.NODE_END, rig)
        assertNull(endProbe.goto, "end 节点不得跳转到其他节点")
        assertEquals(false, endProbe.closed, "end 节点播完应以非取消方式关闭对话")

        // 选项边
        val optionEdges = mutableListOf<Pair<String, String>>()
        for (menuNode in listOf(
            PrologueAgentDialog.NODE_ATTITUDE,
            PrologueAgentDialog.NODE_QUESTION,
            PrologueAgentDialog.NODE_DETAIL,
        )) {
            val (ctx, _) = rig.newProbeContext()
            val specs = graph.requireNode(menuNode).buildOptions(ctx)
            assertTrue(specs.isNotEmpty(), "节点 $menuNode 应提供选项")
            for (spec in specs) {
                val targets = mutableListOf<String>()
                collectTargets(spec.action, targets)
                targets.forEach { optionEdges += menuNode to it }
            }
        }

        // BFS 全可达
        val edges = autoEdges.entries.map { it.key to it.value } + optionEdges
        val reached = linkedSetOf(graph.startNodeId)
        val queue = ArrayDeque(listOf(graph.startNodeId))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for ((from, to) in edges) {
                if (from == cur && reached.add(to)) queue.addLast(to)
            }
        }
        assertEquals(PrologueAgentDialog.NODE_IDS.toSet(), reached, "九个节点应全部从 start 可达")

        // 全图无任何 Close 动作（doc 04：唯一出口是 end 播完自动关闭）
        for ((id, node) in graph.nodes) {
            val (ctx, _) = rig.newProbeContext()
            for (spec in node.buildOptions(ctx)) {
                assertFalse(containsClose(spec.action), "序章图节点 $id 的选项 ${spec.id} 不得包含 Close 动作")
            }
        }
    }

    @Test
    fun `detail options mark re-read entries with reread key suffix`() {
        install()
        val graph = PrologueAgentDialog.createGraph()
        val rig = DialogTestRig()
        val (ctx, _) = rig.newProbeContext()
        val node = graph.requireNode(PrologueAgentDialog.NODE_DETAIL)

        val firstPass = node.buildOptions(ctx)
        assertEquals(
            listOf("detail_why_me", "detail_payment", "detail_decline", "detail_sign"),
            firstPass.map { it.id },
        )
        assertFalse(firstPass[0].text.contains(".reread"))

        exec(firstPass[0].action, ctx)

        val secondPass = node.buildOptions(ctx)
        assertTrue(
            secondPass[0].text.contains(".reread"),
            "已读追问项应使用 .reread 键后缀文本，实际：${secondPass[0].text}",
        )
        assertFalse(secondPass[1].text.contains(".reread"), "未读追问项不得加 .reread 后缀")

        // 签字恒在末位且指向 sign
        val targets = mutableListOf<String>()
        collectTargets(secondPass.last().action, targets)
        assertEquals(listOf(PrologueAgentDialog.NODE_SIGN), targets)
    }

    // ─── GraphDialogPlugin 全流程驱动 ───

    private fun newPlugin(rig: DialogTestRig, onClose: (Boolean) -> Unit): GraphDialogPlugin =
        GraphDialogPlugin(
            graph = PrologueAgentDialog.createGraph(),
            closeOnEscapeOptionId = null,
            onClose = onClose,
        ).also { it.init(rig.dialog) }

    /**
     * 完整跑通一局：态度×追问组合。[useSkip] 为真时每段播报都点「跳过」，否则等队列自然播完。
     * 返回签署的 backend 与总输出段落数。
     */
    private fun playthrough(
        attitude: String,
        question: String,
        useSkip: Boolean,
    ): Triple<StubBackend, DialogTestRig, Boolean> {
        val backend = StubBackend()
        install(backend)
        val rig = DialogTestRig()
        var closed: Boolean? = null
        val plugin = newPlugin(rig) { closed = it }

        fun passStage(optionPrefix: String) {
            if (useSkip && rig.shown.size == 1) {
                rig.select(plugin, rig.shown.single().id!!)
            }
            assertTrue(
                rig.awaitOption(plugin, optionPrefix),
                "应出现 $optionPrefix* 选项（attitude=$attitude question=$question useSkip=$useSkip）；当前=${rig.shownIds()}",
            )
        }

        passStage("attitude_")
        rig.select(plugin, "attitude_$attitude")
        passStage("question_")
        rig.select(plugin, "question_$question")
        passStage("detail_")
        rig.select(plugin, "detail_sign")
        assertTrue(rig.runUntil(plugin) { closed != null }, "end 播完应触发关闭")
        return Triple(backend, rig, closed!!)
    }

    @Test
    fun `full playthrough across attitude and question combinations ends with sign and close`() {
        for (attitude in listOf("cautious", "pragmatic", "joking")) {
            for (question in listOf("format", "collapse", "skip")) {
                val (backend, rig, closedAsCancel) = playthrough(attitude, question, useSkip = false)
                assertEquals(1, backend.signedCount, "签署副作用应恰好执行一次（$attitude/$question）")
                assertTrue(backend.accepted)
                assertFalse(closedAsCancel, "关闭不得以取消形式（$attitude/$question）")
                // 全图不设 Escape 关闭
                verify(rig.dialog, never()).setOptionOnEscape(anyString(), any())
            }
        }
    }

    @Test
    fun `options stay locked to skip while text queue is playing`() {
        val backend = StubBackend()
        install(backend)
        val rig = DialogTestRig()
        var closed: Boolean? = null
        val plugin = newPlugin(rig) { closed = it }

        // start 播报期间：面板只有「跳过」一项
        assertEquals(1, rig.shown.size, "播报期间选项应被锁定为仅跳过")

        // 锁定期间点非面板选项无效：不执行任何动作、不重建面板
        rig.select(plugin, "attitude_cautious")
        assertEquals(0, backend.signedCount)
        assertEquals(1, rig.shown.size)
        assertNull(closed)

        // 跳过：队列立刻清空，进入后续播报；最终自然放行到 attitude 选项
        val skipId = rig.shown.single().id!!
        rig.select(plugin, skipId)
        assertTrue(rig.awaitOption(plugin, "attitude_"), "跳过后队列播完应放行出态度选项")

        // skip 路径全程通关
        rig.select(plugin, "attitude_pragmatic")
        if (rig.shown.size == 1) rig.select(plugin, rig.shown.single().id!!)
        assertTrue(rig.awaitOption(plugin, "question_"))
        rig.select(plugin, "question_format")
        if (rig.shown.size == 1) rig.select(plugin, rig.shown.single().id!!)
        assertTrue(rig.awaitOption(plugin, "detail_"))
        rig.select(plugin, "detail_sign")
        assertTrue(rig.runUntil(plugin) { closed != null })
        assertEquals(1, backend.signedCount)
        assertEquals(false, closed)
    }

    @Test
    fun `asking a question before offer prints more lines than skipping it`() {
        val formatParas = playthrough("pragmatic", "format", useSkip = true).second.paras.size
        val skipParas = playthrough("pragmatic", "skip", useSkip = true).second.paras.size
        assertTrue(
            formatParas > skipParas,
            "选过追问 1/2 时 offer 应先播应答（段落更多）：format=$formatParas skip=$skipParas",
        )
    }

    @Test
    fun `sign failure prints error lines and returns to detail for retry`() {
        val backend = StubBackend().apply { failNextSign = true }
        install(backend)
        val rig = DialogTestRig()
        var closed: Boolean? = null
        val plugin = newPlugin(rig) { closed = it }

        fun passAndSelect(optionPrefix: String, id: String) {
            if (rig.shown.size == 1) rig.select(plugin, rig.shown.single().id!!)
            assertTrue(rig.awaitOption(plugin, optionPrefix), "应出现 $optionPrefix* 选项；当前=${rig.shownIds()}")
            rig.select(plugin, id)
        }

        passAndSelect("attitude_", "attitude_pragmatic")
        passAndSelect("question_", "question_skip")
        passAndSelect("detail_", "detail_sign")

        // 首次签署失败：播差异错误提示，不进入 end、不关闭对话
        assertEquals(1, backend.signedCount)
        assertFalse(backend.accepted, "签署失败不得写入已接取状态")
        assertTrue(
            rig.runUntil(plugin) { rig.paras.any { it.contains("sign.failed.1") } },
            "签署失败应播差异错误提示；已输出=${rig.paras}",
        )
        assertTrue(
            rig.awaitOption(plugin, "detail_"),
            "失败提示播完应回到 detail 节点（签字选项恒在，可重试）；当前=${rig.shownIds()}",
        )
        assertNull(closed, "签署失败不得关闭对话")

        // 重试成功：副作用再次执行，走完 end 并正常关闭
        rig.select(plugin, "detail_sign")
        assertTrue(rig.runUntil(plugin) { closed != null }, "重试签署成功后应收束到 end 并关闭")
        assertEquals(2, backend.signedCount, "重试应再次调用签署副作用")
        assertTrue(backend.accepted)
        assertEquals(false, closed)
    }

    @Test
    fun `interrupted conversation resumes with alternate opening`() {
        val backend = StubBackend(met = true)
        install(backend)
        val rig = DialogTestRig()
        val plugin = newPlugin(rig) {}

        assertTrue(rig.runUntil(plugin) { rig.paras.isNotEmpty() }, "start 节点应输出开场文本")
        assertTrue(
            rig.paras.first().contains("start.resume"),
            "已谈过未签署时应先播差异开场白，实际首段：${rig.paras.first()}",
        )
    }

    @Test
    fun `first meeting does not play the resume opening`() {
        install(StubBackend(met = false))
        val rig = DialogTestRig()
        val plugin = newPlugin(rig) {}

        assertTrue(rig.runUntil(plugin) { rig.paras.isNotEmpty() })
        assertFalse(rig.paras.first().contains("start.resume"), "首次谈话不得播差异开场白")
        assertTrue(rig.paras.first().contains("start.0"))
    }
}
