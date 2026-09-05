package cn.kasuminova.astd.campaign.dialog.story

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 故事脚本结构校验与序章/分局对话的定稿约束测试（纯数据逻辑，不触碰游戏 API）。
 */
class StoryScriptTest {

    private val prologue = PrologueAgentDialog.script()
    private val station = DirectorateStationDialog.script()

    @Test
    fun `prologue script passes validation`() {
        assertTrue(prologue.validate().isEmpty(), "序章脚本应通过校验：${prologue.validate()}")
    }

    @Test
    fun `prologue graph matches documented structure`() {
        assertEquals("start", prologue.startNodeId)
        assertEquals(
            setOf("start", "opening", "verify", "question", "offer", "detail", "sign", "end"),
            prologue.nodes.map { it.id }.toSet(),
        )

        val byId = prologue.nodes.associateBy { it.id }
        // timed 节点与自动出口链：start→opening、verify→question、offer→detail、sign→end、end 自动关闭。
        assertEquals("opening", byId.getValue("start").autoNext)
        assertEquals("question", byId.getValue("verify").autoNext)
        assertEquals("detail", byId.getValue("offer").autoNext)
        assertEquals("end", byId.getValue("sign").autoNext)
        assertTrue(byId.getValue("end").autoClose)
        for (id in listOf("start", "verify", "offer", "sign", "end")) {
            assertTrue(byId.getValue(id).timed, "节点 $id 应为 timed 节点（锁定选项）")
        }
        assertFalse(byId.getValue("opening").timed)
        assertFalse(byId.getValue("question").timed)
        assertFalse(byId.getValue("detail").timed)
    }

    @Test
    fun `prologue is not escapable and has no close option`() {
        assertFalse(prologue.allowEscape, "代办对话不允许 Escape 退出")
        assertNull(prologue.escapeOptionId)
        for (node in prologue.nodes) {
            for (option in node.options) {
                assertFalse(
                    option.action.containsClose(),
                    "节点 '${node.id}' 的选项 '${option.id}' 不得包含 Close（唯一出口是 end 自动关闭）",
                )
            }
        }
        // 全图唯一出口：end 节点 autoClose。
        assertTrue(prologue.hasClosePath())
        assertEquals(listOf("end"), prologue.nodes.filter { it.autoClose }.map { it.id })
    }

    @Test
    fun `prologue attitude branches converge to verify`() {
        val opening = prologue.nodes.first { it.id == "opening" }
        assertEquals(3, opening.options.size)
        for (option in opening.options) {
            assertEquals(StoryOptionAction.Goto("verify"), option.action,
                "态度分支 '${option.id}' 应收束到 verify")
            assertNotNull(option.sessionFlag, "态度分支 '${option.id}' 应记录 sessionState 风味标记")
        }
    }

    @Test
    fun `prologue question branches converge to offer`() {
        val question = prologue.nodes.first { it.id == "question" }
        assertEquals(3, question.options.size)

        val business = question.options.first { it.id == "question_business" }
        assertEquals(StoryOptionAction.Goto("offer"), business.action)

        for (id in listOf("question_format", "question_domain")) {
            val action = question.options.first { it.id == id }.action
            val reply = action as? StoryOptionAction.Reply ?: error("$id 应为 Reply 应答")
            assertEquals(StoryOptionAction.Goto("offer"), reply.then, "$id 应答后应进入 offer")
            // 公共揭晓三句 + 各自追加一句。
            assertEquals(4, reply.lines.size, "$id 应答应为 3 句公共揭晓 + 1 句追加")
        }
    }

    @Test
    fun `prologue detail loops with sign always last`() {
        val detail = prologue.nodes.first { it.id == "detail" }
        assertEquals(4, detail.options.size)

        val sign = detail.options.last()
        assertEquals(StoryOptionAction.Goto("sign"), sign.action, "「我签」恒在末位")
        assertFalse(sign.rereadable)

        for (option in detail.options.dropLast(1)) {
            assertTrue(option.rereadable, "细节追问 '${option.id}' 应可重读")
            val reply = option.action as? StoryOptionAction.Reply
                ?: error("细节追问 '${option.id}' 应为 Reply 应答")
            assertNull(reply.then, "细节追问 '${option.id}' 应答后应停留本节点（可循环）")
        }
        assertNotNull(detail.rereadSuffixKey, "detail 节点应配置已读后缀")
    }

    @Test
    fun `prologue memory flags follow the story doc`() {
        val byId = prologue.nodes.associateBy { it.id }
        val start = byId.getValue("start")
        assertEquals(PrologueAgentDialog.MEM_MET, start.resumeFlagKey)
        assertTrue(start.resumeLines.isNotEmpty(), "中断恢复应有差分开场白")
        assertTrue(PrologueAgentDialog.MEM_MET in start.setMemoryFlags, "首次进入应写入已见面标记")

        val sign = byId.getValue("sign")
        assertTrue(PrologueAgentDialog.MEM_ACCEPTED in sign.setMemoryFlags, "签字节点应写入 astd_prologue_accepted")
        assertEquals(PrologueAgentDialog.CALLBACK_ACCEPT, sign.enterCallback,
            "签字节点应触发 accept 回调供 campaign 侧发放文书/生成目标")
    }

    @Test
    fun `station script passes validation and is escapable`() {
        assertTrue(station.validate().isEmpty(), "分局服务脚本应通过校验：${station.validate()}")
        assertTrue(station.allowEscape)
        assertEquals(DirectorateStationDialog.OPT_LEAVE, station.escapeOptionId)

        val root = station.nodes.single()
        val actions = root.options.map { it.action }
        // 三选功能入口 + 离开。
        assertEquals(4, root.options.size)
        assertEquals(StoryOptionAction.Close, root.options.last().action)
        assertEquals(
            setOf(
                DirectorateStationDialog.CALLBACK_WORK_ORDERS,
                DirectorateStationDialog.CALLBACK_ARCHIVES,
                DirectorateStationDialog.CALLBACK_FILING,
            ),
            actions.filterIsInstance<StoryOptionAction.Callback>().map { it.id }.toSet(),
        )
        assertTrue(DirectorateStationDialog.defaultCallbacks().keys.containsAll(
            setOf(
                DirectorateStationDialog.CALLBACK_WORK_ORDERS,
                DirectorateStationDialog.CALLBACK_ARCHIVES,
                DirectorateStationDialog.CALLBACK_FILING,
            ),
        ), "默认回调表应覆盖全部三选功能入口")
    }

    @Test
    fun `prologue compiles to a complete dialog graph`() {
        val graph = StoryDialogs.compile(prologue)
        assertEquals(prologue.startNodeId, graph.startNodeId)
        assertEquals(prologue.nodes.map { it.id }.toSet(), graph.nodes.keys)
        // 每个节点均可实例化取用（缺失会抛异常）。
        for (id in graph.nodes.keys) graph.requireNode(id)

        val stationGraph = StoryDialogs.compile(station)
        assertEquals(station.startNodeId, stationGraph.startNodeId)
        assertEquals(setOf("root"), stationGraph.nodes.keys)
    }

    @Test
    fun `validation catches broken scripts`() {
        val broken = StoryScript(
            id = "broken",
            category = "asteria_directorate",
            keyPrefix = "test.",
            startNodeId = "missing",
            allowEscape = true,
            escapeOptionId = "leave",
            nodes = listOf(
                StoryNode(
                    id = "a",
                    options = listOf(StoryOption("go", "t", StoryOptionAction.Goto("nowhere"))),
                ),
                StoryNode(id = "b"),
            ),
        )
        val problems = broken.validate()
        assertTrue(problems.any { "起始节点" in it }, "应检出起始节点缺失")
        assertTrue(problems.any { "nowhere" in it }, "应检出跳转目标缺失")
        assertTrue(problems.any { "卡死" in it }, "应检出无出口节点")
        assertTrue(problems.any { "不可达" in it && "'a'" in it }, "应检出不可达节点")
        assertTrue(problems.any { "escapeOptionId" in it }, "应检出 Escape 选项不收敛到 Close")

        assertFailsWith<IllegalArgumentException> {
            StoryDialogs.compile(broken)
        }
    }

    @Test
    fun `validation enforces auto-exit node rules`() {
        val bad = StoryScript(
            id = "bad_auto",
            category = "asteria_directorate",
            keyPrefix = "test.",
            startNodeId = "a",
            nodes = listOf(
                // 非 timed 节点配置自动出口：非法。
                StoryNode(id = "a", autoNext = "b", options = listOf(StoryOption("x", "t", StoryOptionAction.Close))),
                // 自动节点没有台词：非法。
                StoryNode(id = "b", timed = true, autoClose = true),
            ),
        )
        val problems = bad.validate()
        assertTrue(problems.any { "不是 timed 节点" in it })
        assertTrue(problems.any { "没有任何台词" in it })
    }
}
