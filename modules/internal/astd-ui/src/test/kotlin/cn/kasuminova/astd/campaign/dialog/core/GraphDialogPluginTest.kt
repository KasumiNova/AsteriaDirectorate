package cn.kasuminova.astd.campaign.dialog.core

import cn.kasuminova.astd.campaign.dialog.DialogTestRig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphDialogPluginTest {

    private fun singleTimedNodeGraph(): DialogGraph = dialogGraph(start = "play") {
        node(
            "play", DialogDsl.timedNode(
                onEnter = { ctx ->
                    ctx.enqueue("line-1", 0.3f)
                    ctx.enqueue("line-2", 0.5f)
                },
                options = {
                    listOf(
                        DialogDsl.option("opt_a", "A", DialogDsl.close()),
                        DialogDsl.option("opt_b", "B", DialogDsl.close()),
                    )
                },
            )
        )
    }

    @Test
    fun `panel switches from locked skip to node options when queue finishes naturally`() {
        val rig = DialogTestRig()
        val plugin = GraphDialogPlugin(singleTimedNodeGraph(), closeOnEscapeOptionId = null)
        plugin.init(rig.dialog)

        assertEquals(1, rig.shown.size, "播报期间面板应仅含跳过项，实际=${rig.shownIds()}")
        assertFalse(rig.shownIds().any { it == "opt_a" || it == "opt_b" })

        var frames = 0
        while (rig.paras.size < 1 && frames++ < 4000) plugin.advance(0.05f)
        assertEquals(1, rig.paras.size, "0.3s 后第一条文本应已输出")
        assertEquals(1, rig.shown.size, "第一条输出后队列未播完，面板应保持锁定跳过项，实际=${rig.shownIds()}")

        val switched = rig.runUntil(plugin) { rig.shownIds() == listOf("opt_a", "opt_b") }
        assertTrue(switched, "队列自然播完后面板应切回节点正常选项，实际=${rig.shownIds()}")

        repeat(10) { plugin.advance(0.05f) }
        assertEquals(listOf("opt_a", "opt_b"), rig.shownIds(), "播完后面板应稳定为正常选项")
    }

    @Test
    fun `auto goto between timed nodes keeps skip panel until the next queue finishes`() {
        val graph = dialogGraph(start = "a") {
            node(
                "a", DialogDsl.timedNode(
                    onEnter = { ctx ->
                        ctx.sessionState["fired"] = false
                        ctx.enqueue("a-line", 0.2f)
                    },
                    onAdvance = { ctx, _ ->
                        if (!ctx.textQueue.hasPending && ctx.sessionState["fired"] != true) {
                            ctx.sessionState["fired"] = true
                            ctx.goto("b")
                        }
                    },
                )
            )
            node(
                "b", DialogDsl.timedNode(
                    onEnter = { ctx -> ctx.enqueue("b-line", 0.2f) },
                    options = { listOf(DialogDsl.option("opt_end", "END", DialogDsl.close())) },
                )
            )
        }
        val rig = DialogTestRig()
        val plugin = GraphDialogPlugin(graph, closeOnEscapeOptionId = null)
        plugin.init(rig.dialog)

        val jumped = rig.runUntil(plugin) { rig.paras.contains("a-line") }
        assertTrue(jumped, "A 队列应播完并输出 a-line")
        assertEquals(1, rig.shown.size, "跳转后 B 仍在播报，面板应保持跳过项，实际=${rig.shownIds()}")
        assertFalse(rig.shownIds().any { it == "opt_end" }, "B 播报期间不得提前展示正常选项")

        assertTrue(
            rig.runUntil(plugin) { rig.shownIds() == listOf("opt_end") },
            "B 播完后面板应切回正常选项，实际=${rig.shownIds()}",
        )
    }

    /** 关闭后本插件不得再 clearOptions，否则 onClose 钩子同步重建的宿主选项会被抹掉。 */
    @Test
    fun `host rebuilt options survive graph close during advance`() {
        val graph = dialogGraph(start = "end") {
            node(
                "end", DialogDsl.timedNode(
                    onEnter = { ctx ->
                        ctx.sessionState["fired"] = false
                        ctx.enqueue("bye", 0.2f)
                    },
                    onAdvance = { ctx, _ ->
                        if (!ctx.textQueue.hasPending && ctx.sessionState["fired"] != true) {
                            ctx.sessionState["fired"] = true
                            ctx.close()
                        }
                    },
                )
            )
        }
        val rig = DialogTestRig()
        // 模拟宿主钩子同步重建选项面板
        val plugin = GraphDialogPlugin(graph, closeOnEscapeOptionId = null, onClose = {
            rig.options.clearOptions()
            rig.options.addOption("继续", "barContinue", null)
        })
        plugin.init(rig.dialog)

        val closed = rig.runUntil(plugin) { rig.shownIds() == listOf("barContinue") }
        assertTrue(closed, "播完自动关闭后宿主重建的选项应保留，实际=${rig.shownIds()}")
        assertTrue(rig.paras.contains("bye"), "告别文本应已输出")

        repeat(10) { plugin.advance(0.05f) }
        assertEquals(listOf("barContinue"), rig.shownIds(), "关闭后本插件不得再触碰选项面板")
    }

    @Test
    fun `host rebuilt options survive close triggered by option selection`() {
        val graph = dialogGraph(start = "menu") {
            node(
                "menu", DialogDsl.node(
                    options = { listOf(DialogDsl.option("opt_leave", "离开", DialogDsl.close())) },
                )
            )
        }
        val rig = DialogTestRig()
        val plugin = GraphDialogPlugin(graph, closeOnEscapeOptionId = null, onClose = {
            rig.options.clearOptions()
            rig.options.addOption("继续", "barContinue", null)
        })
        plugin.init(rig.dialog)

        assertEquals(listOf("opt_leave"), rig.shownIds())
        rig.select(plugin, "opt_leave")
        assertEquals(listOf("barContinue"), rig.shownIds(), "选项关闭后宿主面板不得被抹空，实际=${rig.shownIds()}")

        rig.select(plugin, "barContinue")
        repeat(5) { plugin.advance(0.05f) }
        assertEquals(listOf("barContinue"), rig.shownIds())
    }
}
