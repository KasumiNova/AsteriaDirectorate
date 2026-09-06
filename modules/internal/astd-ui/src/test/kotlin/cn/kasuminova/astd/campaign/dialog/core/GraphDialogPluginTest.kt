package cn.kasuminova.astd.campaign.dialog.core

import cn.kasuminova.astd.campaign.dialog.DialogTestRig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GraphDialogPlugin.advance 的选项面板刷新回归测试：
 * 队列「自然播完」（玩家不点跳过）后面板必须从锁定态的跳过项切回节点正常选项，
 * 且整个锁定期间面板不被逐帧重建。
 */
class GraphDialogPluginTest {

    /** 单 timed 节点：两条延迟文本 + 两个正常选项，无自动跳转。 */
    private fun singleTimedNodeGraph(): DialogGraph = dialogGraph(start = "play") {
        node("play", DialogDsl.timedNode(
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
        ))
    }

    @Test
    fun `panel switches from locked skip to node options when queue finishes naturally`() {
        val rig = DialogTestRig()
        val plugin = GraphDialogPlugin(singleTimedNodeGraph(), closeOnEscapeOptionId = null)
        plugin.init(rig.dialog)

        // 进入即锁定：面板仅有跳过项，不含节点正常选项
        assertEquals(1, rig.shown.size, "播报期间面板应仅含跳过项，实际=${rig.shownIds()}")
        assertFalse(rig.shownIds().any { it == "opt_a" || it == "opt_b" })

        // 逐帧推进（全程不点跳过）：第一条文本输出后队列仍未播完，面板保持锁定跳过项不重建
        var frames = 0
        while (rig.paras.size < 1 && frames++ < 4000) plugin.advance(0.05f)
        assertEquals(1, rig.paras.size, "0.3s 后第一条文本应已输出")
        assertEquals(1, rig.shown.size, "第一条输出后队列未播完，面板应保持锁定跳过项，实际=${rig.shownIds()}")

        // 继续推进到队列自然播完：面板必须在 advance 内切回正常选项（无 optionSelected 介入）
        val switched = rig.runUntil(plugin) { rig.shownIds() == listOf("opt_a", "opt_b") }
        assertTrue(switched, "队列自然播完后面板应切回节点正常选项，实际=${rig.shownIds()}")

        // 播完后的非 emit 帧：面板稳定停留在正常选项，不回退为锁定态
        repeat(10) { plugin.advance(0.05f) }
        assertEquals(listOf("opt_a", "opt_b"), rig.shownIds(), "播完后面板应稳定为正常选项")
    }

    /**
     * 自动跳转链：timed 节点 A 播完自动进 timed 节点 B。
     * 跳转帧面板不得被刷成空/旧选项（B 仍在播报，应继续显示跳过项）；
     * B 播完后再切回正常选项。
     */
    @Test
    fun `auto goto between timed nodes keeps skip panel until the next queue finishes`() {
        val graph = dialogGraph(start = "a") {
            node("a", DialogDsl.timedNode(
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
            ))
            node("b", DialogDsl.timedNode(
                onEnter = { ctx -> ctx.enqueue("b-line", 0.2f) },
                options = { listOf(DialogDsl.option("opt_end", "END", DialogDsl.close())) },
            ))
        }
        val rig = DialogTestRig()
        val plugin = GraphDialogPlugin(graph, closeOnEscapeOptionId = null)
        plugin.init(rig.dialog)

        // A 播完 → 自动跳转 B 的同一帧（a-line 刚输出、b-line 尚未到点）：B 队列非空，
        // 面板应保持锁定跳过项（不得清空/展示 B 选项）
        val jumped = rig.runUntil(plugin) { rig.paras.contains("a-line") }
        assertTrue(jumped, "A 队列应播完并输出 a-line")
        assertEquals(1, rig.shown.size, "跳转后 B 仍在播报，面板应保持跳过项，实际=${rig.shownIds()}")
        assertFalse(rig.shownIds().any { it == "opt_end" }, "B 播报期间不得提前展示正常选项")

        // B 队列自然播完：切回 B 的正常选项
        assertTrue(
            rig.runUntil(plugin) { rig.shownIds() == listOf("opt_end") },
            "B 播完后面板应切回正常选项，实际=${rig.shownIds()}",
        )
    }

    /**
     * 回归：节点 onAdvance 播完自动 close 时，onClose 宿主钩子会同步重建宿主选项
     * （BarEventDialogPlugin.endEvent → BarCMD.showOptions 加「继续/离开酒吧」）。
     * 本插件在关闭后的同一帧及后续帧不得再 clearOptions——否则宿主选项被抹空、玩家卡死。
     */
    @Test
    fun `host rebuilt options survive graph close during advance`() {
        val graph = dialogGraph(start = "end") {
            node("end", DialogDsl.timedNode(
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
            ))
        }
        val rig = DialogTestRig()
        // 宿主钩子：模拟 BarCMD.showOptions 同步重建选项面板
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

    /** 回归：选项动作触发 close（DialogAction.Close）同样不得抹掉宿主重建的选项。 */
    @Test
    fun `host rebuilt options survive close triggered by option selection`() {
        val graph = dialogGraph(start = "menu") {
            node("menu", DialogDsl.node(
                options = { listOf(DialogDsl.option("opt_leave", "离开", DialogDsl.close())) },
            ))
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

        // 关闭后再有点选/推进也不得生效
        rig.select(plugin, "barContinue")
        repeat(5) { plugin.advance(0.05f) }
        assertEquals(listOf("barContinue"), rig.shownIds())
    }
}
