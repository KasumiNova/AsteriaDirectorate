package cn.kasuminova.astd.campaign.dialog.story

import cn.kasuminova.astd.campaign.dialog.DialogTestRig
import cn.kasuminova.astd.campaign.ui.terminal.TerminalTab
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BranchStationDialogTest {

    private class StubBackend : BranchStationDialog.BranchStationBackend {
        var phaseValue = BranchStationDialog.BranchStationPhase.OPEN
        var hasCore = false
        var specValue: cn.kasuminova.astd.campaign.ui.terminal.ExecutorSpec? = null
        override fun phase(): BranchStationDialog.BranchStationPhase = phaseValue
        override fun hasExecutorCore(): Boolean = hasCore
        override fun executorSpec(): cn.kasuminova.astd.campaign.ui.terminal.ExecutorSpec? = specValue
    }

    private class RecordingOpener : BranchStationDialog.TerminalOpener {
        val calls = mutableListOf<Pair<InteractionDialogAPI, TerminalTab>>()
        override fun open(dialog: InteractionDialogAPI, tab: TerminalTab) {
            calls += dialog to tab
        }
    }

    /** 进 menu：entry 播报自然播完。 */
    private fun toMenu(rig: DialogTestRig, plugin: com.fs.starfarer.api.campaign.InteractionDialogPlugin) {
        assertTrue(rig.awaitOption(plugin, "leave"), "entry 播完应进入 menu；当前=${rig.shownIds()}")
    }

    @Test
    fun `locked phase shows only leave option`() {
        val backend = StubBackend()
        backend.phaseValue = BranchStationDialog.BranchStationPhase.LOCKED
        val rig = DialogTestRig()
        val plugin = BranchStationDialog.createPlugin(backend, RecordingOpener())
        plugin.init(rig.dialog)

        toMenu(rig, plugin)
        assertEquals(listOf("leave"), rig.shownIds())
        assertTrue(rig.paras.any { it.contains("story.branch.intro.locked") })
    }

    @Test
    fun `pending phase shows terminal entry only`() {
        val backend = StubBackend()
        backend.phaseValue = BranchStationDialog.BranchStationPhase.PENDING
        val rig = DialogTestRig()
        val plugin = BranchStationDialog.createPlugin(backend, RecordingOpener())
        plugin.init(rig.dialog)

        toMenu(rig, plugin)
        assertEquals(listOf("terminal", "leave"), rig.shownIds())
        assertTrue(rig.paras.any { it.contains("story.branch.intro.pending") })
    }

    @Test
    fun `open phase shows all three entries plus dock`() {
        val backend = StubBackend()
        val rig = DialogTestRig()
        val plugin = BranchStationDialog.createPlugin(backend, RecordingOpener())
        plugin.init(rig.dialog)

        toMenu(rig, plugin)
        assertEquals(listOf("terminal", "archives", "account", "dock", "leave"), rig.shownIds())
        assertTrue(rig.paras.any { it.contains("story.branch.intro.open") })
    }

    @Test
    fun `terminal entries open fullscreen ui on their tabs`() {
        val backend = StubBackend()
        val opener = RecordingOpener()
        val rig = DialogTestRig()
        val plugin = BranchStationDialog.createPlugin(backend, opener)
        plugin.init(rig.dialog)

        toMenu(rig, plugin)
        rig.select(plugin, "terminal")
        assertEquals(listOf(TerminalTab.ORDERS), opener.calls.map { it.second })

        rig.select(plugin, "archives")
        assertEquals(listOf(TerminalTab.ORDERS, TerminalTab.ARCHIVES), opener.calls.map { it.second })

        rig.select(plugin, "account")
        assertEquals(
            listOf(TerminalTab.ORDERS, TerminalTab.ARCHIVES, TerminalTab.ACCOUNT),
            opener.calls.map { it.second },
        )
        assertTrue(opener.calls.all { it.first === rig.dialog }, "打开终端应使用当前对话实例")
    }

    @Test
    fun `pending phase terminal entry opens orders tab`() {
        val backend = StubBackend()
        backend.phaseValue = BranchStationDialog.BranchStationPhase.PENDING
        val opener = RecordingOpener()
        val rig = DialogTestRig()
        val plugin = BranchStationDialog.createPlugin(backend, opener)
        plugin.init(rig.dialog)

        toMenu(rig, plugin)
        rig.select(plugin, "terminal")
        assertEquals(listOf(TerminalTab.ORDERS), opener.calls.map { it.second })
    }

    @Test
    fun `open phase with executor core shows link entry`() {
        val backend = StubBackend()
        backend.hasCore = true
        backend.specValue = cn.kasuminova.astd.campaign.ui.terminal.ExecutorSpec.COMBAT
        val rig = DialogTestRig()
        val plugin = BranchStationDialog.createPlugin(backend, RecordingOpener())
        plugin.init(rig.dialog)

        toMenu(rig, plugin)
        assertEquals(listOf("terminal", "archives", "account", "executor", "dock", "leave"), rig.shownIds())
    }

    @Test
    fun `entry intro text stays fully visible after auto-goto to menu`() {
        val backend = StubBackend()
        val rig = DialogTestRig()
        val plugin = BranchStationDialog.createPlugin(backend, RecordingOpener())
        plugin.init(rig.dialog)

        toMenu(rig, plugin)
        // 回归：entry 文本发出同帧即 goto(menu)，goto 清空文本队列时不得把
        // 仍在淡入的标签遗弃在 opacity=0（实机曾表现为文本面板整段空白）
        val intro = rig.opacityHistory.values.single()
        assertEquals(1f, intro.last(), 1e-4f)
    }

    @Test
    fun `escape leaves the dialog from entry node`() {
        val backend = StubBackend()
        val rig = DialogTestRig()
        val plugin = BranchStationDialog.createPlugin(backend, RecordingOpener())
        plugin.init(rig.dialog)

        rig.select(plugin, "leave")
        assertTrue(rig.dismissed, "entry 播报中 Escape 应可直接离开")
    }
}
