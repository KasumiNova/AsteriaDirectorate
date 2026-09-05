package cn.kasuminova.astd.campaign.ui

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.SettingsAPI
import com.fs.starfarer.api.campaign.CustomDialogDelegate
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin
import com.fs.starfarer.api.campaign.FactionAPI
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.CutStyle
import com.fs.starfarer.api.ui.PositionAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.ui.UIComponentAPI
import org.json.JSONObject
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import java.awt.Color
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DirectorateTerminalUiTest {
    private var originalSettings: SettingsAPI? = null
    private var originalSector: SectorAPI? = null

    @BeforeTest
    fun setup() {
        originalSettings = Global.getSettings()
        originalSector = Global.getSector()
        val settings = Mockito.mock(SettingsAPI::class.java)
        Mockito.`when`(settings.screenWidth).thenReturn(1920f)
        Mockito.`when`(settings.screenHeight).thenReturn(1080f)
        Mockito.`when`(settings.getColor(anyString())).thenReturn(Color.WHITE)
        Mockito.`when`(settings.getString(anyString(), anyString())).thenAnswer { it.getArgument<String>(1) }
        Mockito.`when`(settings.loadJSON(anyString(), anyString())).thenReturn(JSONObject())
        val faction = Mockito.mock(FactionAPI::class.java)
        Mockito.`when`(faction.baseUIColor).thenReturn(Color.CYAN)
        Mockito.`when`(faction.darkUIColor).thenReturn(Color.DARK_GRAY)
        Mockito.`when`(faction.brightUIColor).thenReturn(Color.WHITE)
        val sector = Mockito.mock(SectorAPI::class.java)
        Mockito.`when`(sector.playerFaction).thenReturn(faction)
        Global.setSettings(settings)
        Global.setSector(sector)
    }

    @AfterTest
    fun restoreGlobals() {
        Global.setSettings(originalSettings)
        Global.setSector(originalSector)
    }

    @Test
    fun `delegate construction alone is not an open panel and cannot execute actions`() {
        val source = Source()
        val ui = DirectorateTerminalDelegate(TerminalTab.WORK_ORDERS, source)
        assertFalse(ui.view().open)
        assertNull(ui.view().panel)
        assertEquals(0, ui.view().componentCount)
        assertEquals(0L, ui.view().layoutRevision)
        assertEquals(TerminalPress.NOT_OPEN, ui.press(TerminalControl.Action.ACCEPT))
        assertFalse(ui.close())
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `showOn builds layout and native forwarded events share semantic selection and action dispatch`() {
        val source = Source()
        val host = Host()
        val ui = host.show(source)
        assertSame(host.root, ui.view().panel)
        assertTrue(ui.view().open)
        assertEquals(3, ui.view().componentCount)
        assertEquals(1L, ui.view().layoutRevision)
        assertEquals(3, host.attached.getValue(host.root).size)
        assertEquals(0L, ui.view().renderedFrames)

        host.nativePress(TerminalControl.Order("second"))
        assertEquals("second", ui.view().selectedOrderId)
        assertTrue(ui.view().buttons.getValue(TerminalControl.Order("second")).checked)
        assertFalse(ui.view().buttons.getValue(TerminalControl.Order("first")).checked)
        assertEquals(TerminalPress.DISPATCHED, ui.press(TerminalControl.Action.ACCEPT))
        assertEquals(listOf("accept:second"), source.calls)
        assertEquals(WorkOrderStatus.ACTIVE, ui.view().selectedOrderStatus)
        assertEquals(TerminalPress.NOT_PRESENT, ui.press(TerminalControl.Action.ACCEPT))
        ui.customPanelPlugin.buttonPressed(TerminalControl.Action.ACCEPT)
        assertEquals(listOf("accept:second"), source.calls)
        assertEquals(TerminalPress.DISPATCHED, ui.press(TerminalControl.Action.TRACK))
        assertEquals(TerminalPress.DISPATCHED, ui.press(TerminalControl.Action.TRACK))
        assertEquals(listOf("accept:second", "track:second", "track:second"), source.calls)
        assertEquals(3, host.attached.getValue(host.root).size)
    }

    @Test
    fun `native disabled button rejects direct plugin events and semantic events`() {
        val source = Source()
        val host = Host()
        val ui = host.show(source)
        host.buttons.getValue(TerminalControl.Action.ACCEPT).isEnabled = false
        assertFalse(ui.view().buttons.getValue(TerminalControl.Action.ACCEPT).enabled)
        val revision = ui.view().layoutRevision
        assertEquals(TerminalPress.DISABLED, ui.press(TerminalControl.Action.ACCEPT))
        host.nativePress(TerminalControl.Action.ACCEPT)
        assertEquals(revision, ui.view().layoutRevision)
        assertTrue(source.calls.isEmpty())
        host.buttons.getValue(TerminalControl.Action.ACCEPT).isEnabled = true
        assertEquals(TerminalPress.DISPATCHED, ui.press(TerminalControl.Action.ACCEPT))
        assertEquals(listOf("accept:first"), source.calls)
    }

    @Test
    fun `tabs replace native controls preserve selection and reject stale events and locked endings`() {
        val source = Source()
        val host = Host()
        val ui = host.show(source)
        ui.press(TerminalControl.Order("second"))
        host.nativePress(TerminalControl.Tab(TerminalTab.ARCHIVES))
        assertEquals(TerminalTab.ARCHIVES, ui.view().tab)
        assertEquals(TerminalPress.NOT_PRESENT, ui.press(TerminalControl.Action.ACCEPT))
        assertEquals(TerminalPress.NOT_PRESENT, ui.press(TerminalControl.Archive("locked")))
        ui.press(TerminalControl.Archive("archive"))
        assertEquals("archive", ui.view().selectedArchiveId)
        assertTrue(ui.view().buttons.getValue(TerminalControl.Archive("archive")).checked)
        host.nativePress(TerminalControl.Tab(TerminalTab.ACCOUNT))
        assertEquals(TerminalTab.ACCOUNT, ui.view().tab)
        assertEquals(2, ui.view().componentCount)
        assertEquals(TerminalPress.DISABLED, ui.press(TerminalControl.Ending("locked")))
        host.nativePress(TerminalControl.Ending("locked"))
        assertNull(ui.view().reviewingEndingId)
        assertTrue(source.calls.isEmpty())
        ui.press(TerminalControl.Ending("open"))
        assertEquals("open", ui.view().reviewingEndingId)
        ui.press(TerminalControl.Action.ENDING_BACK)
        assertNull(ui.view().reviewingEndingId)
        ui.press(TerminalControl.Ending("open"))
        ui.press(TerminalControl.Tab(TerminalTab.WORK_ORDERS))
        assertNull(ui.view().reviewingEndingId)
        assertEquals("second", ui.view().selectedOrderId)
        assertEquals(3, host.attached.getValue(host.root).size)
    }

    @Test
    fun `close uses native cancel callback and reopening allows one settlement only`() {
        val source = Source()
        val host = Host()
        val old = host.show(source)
        old.press(TerminalControl.Tab(TerminalTab.ACCOUNT))
        old.press(TerminalControl.Ending("open"))
        host.deliverDismissCallback = false
        assertTrue(old.close())
        assertEquals(listOf(1), host.dismissals)
        assertFalse(old.view().closed)
        assertFalse(old.close())
        assertEquals(TerminalPress.NOT_OPEN, old.press(TerminalControl.Action.SIGN_ENDING))
        old.customDialogCancel()
        assertTrue(old.view().closed)
        assertFalse(old.view().open)
        assertNull(old.view().reviewingEndingId)
        assertTrue(source.calls.isEmpty())

        source.statuses["first"] = WorkOrderStatus.READY_TO_SETTLE
        val next = host.show(source)
        assertNotSame(old.view().panel, next.view().panel)
        assertEquals(TerminalPress.DISPATCHED, next.press(TerminalControl.Action.DELIVER))
        assertEquals(WorkOrderStatus.SETTLED, next.view().selectedOrderStatus)
        assertEquals(TerminalPress.NOT_PRESENT, next.press(TerminalControl.Action.DELIVER))
        assertEquals(listOf("deliver:first"), source.calls)
        host.deliverDismissCallback = true
        assertTrue(next.close())
        assertTrue(next.view().closed)
        assertFalse(next.close())
        assertEquals(listOf(1, 1), host.dismissals)
    }

    private class Source : DirectorateTerminalDataSource {
        val statuses = linkedMapOf("first" to WorkOrderStatus.AVAILABLE, "second" to WorkOrderStatus.AVAILABLE)
        val calls = mutableListOf<String>()
        override fun snapshot() = TerminalSnapshot(
            "CT", 1, "cycle",
            batches = listOf(WorkOrderBatch("batch", "chapter", "batch", statuses.map { (id, status) ->
                WorkOrder(id, id, id, 1, "target", status, "commission")
            })),
            archives = listOf(
                ArchiveEntry("archive", 1, "Archive", listOf("body"), true),
                ArchiveEntry("locked", 1, "Locked", listOf("secret"), false),
            ),
            endings = listOf(EndingOption("open", "Open", "document", true), EndingOption("locked", "Locked", "document", false)),
        )
        override fun acceptWorkOrder(orderId: String): Boolean {
            calls += "accept:$orderId"
            check(statuses[orderId] == WorkOrderStatus.AVAILABLE)
            statuses[orderId] = WorkOrderStatus.ACTIVE
            return true
        }
        override fun trackWorkOrder(orderId: String): Boolean {
            calls += "track:$orderId"
            check(statuses[orderId] == WorkOrderStatus.ACTIVE)
            return true
        }
        override fun requestSettlement(orderId: String): Boolean {
            calls += "deliver:$orderId"
            check(statuses[orderId] == WorkOrderStatus.READY_TO_SETTLE)
            statuses[orderId] = WorkOrderStatus.SETTLED
            return true
        }
        override fun chooseEnding(endingId: String): Boolean {
            calls += "ending:$endingId"
            return true
        }
    }

    private class Host {
        lateinit var root: CustomPanelAPI
        val attached = mutableMapOf<CustomPanelAPI, MutableList<UIComponentAPI>>()
        val buttons = mutableMapOf<TerminalControl, ButtonAPI>()
        val events = mutableMapOf<TerminalControl, () -> Unit>()
        val dismissals = mutableListOf<Int>()
        var deliverDismissCallback = true

        fun show(source: DirectorateTerminalDataSource): DirectorateTerminalDelegate {
            val dialog = Mockito.mock(InteractionDialogAPI::class.java)
            Mockito.doAnswer { call ->
                val delegate = call.getArgument<CustomDialogDelegate>(2)
                root = panel(call.getArgument(0), call.getArgument(1), delegate.customPanelPlugin)
                delegate.createCustomDialog(root) { option ->
                    dismissals += option
                    if (deliverDismissCallback) {
                        if (option == 1) delegate.customDialogCancel() else delegate.customDialogConfirm()
                    }
                }
                null
            }.`when`(dialog).showCustomDialog(anyFloat(), anyFloat(), any(CustomDialogDelegate::class.java))
            return DirectorateTerminal.showOn(dialog, source = source)
        }

        fun nativePress(control: TerminalControl) = events.getValue(control).invoke()

        private fun position(width: Float, height: Float): PositionAPI {
            val position = Mockito.mock(PositionAPI::class.java, Mockito.RETURNS_SELF)
            Mockito.`when`(position.width).thenReturn(width)
            Mockito.`when`(position.height).thenReturn(height)
            return position
        }

        private fun panel(width: Float, height: Float, plugin: CustomUIPanelPlugin): CustomPanelAPI {
            val panel = Mockito.mock(CustomPanelAPI::class.java)
            val pos = position(width, height)
            attached[panel] = mutableListOf()
            Mockito.`when`(panel.position).thenReturn(pos)
            Mockito.`when`(panel.plugin).thenReturn(plugin)
            Mockito.`when`(panel.createCustomPanel(anyFloat(), anyFloat(), any(CustomUIPanelPlugin::class.java))).thenAnswer {
                panel(it.getArgument(0), it.getArgument(1), it.getArgument(2))
            }
            Mockito.`when`(panel.createUIElement(anyFloat(), anyFloat(), anyBoolean())).thenAnswer { tooltip(plugin) }
            Mockito.`when`(panel.addUIElement(any(TooltipMakerAPI::class.java))).thenReturn(pos)
            Mockito.`when`(panel.addComponent(any(UIComponentAPI::class.java))).thenAnswer {
                attached.getValue(panel) += it.getArgument<UIComponentAPI>(0)
                pos
            }
            Mockito.doAnswer {
                attached.getValue(panel).remove(it.getArgument<UIComponentAPI>(0))
                null
            }.`when`(panel).removeComponent(any(UIComponentAPI::class.java))
            return panel
        }

        private fun tooltip(plugin: CustomUIPanelPlugin): TooltipMakerAPI {
            val tooltip = Mockito.mock(TooltipMakerAPI::class.java, Mockito.RETURNS_MOCKS)
            Mockito.`when`(tooltip.addButton(anyString(), any(), any(Color::class.java), any(Color::class.java),
                any(Alignment::class.java), any(CutStyle::class.java), anyFloat(), anyFloat(), anyFloat())).thenAnswer {
                button(it.getArgument(1), it.getArgument(6), it.getArgument(7), plugin)
            }
            Mockito.`when`(tooltip.addAreaCheckbox(anyString(), any(), any(Color::class.java), any(Color::class.java),
                any(Color::class.java), anyFloat(), anyFloat(), anyFloat())).thenAnswer {
                button(it.getArgument(1), it.getArgument(5), it.getArgument(6), plugin)
            }
            return tooltip
        }

        private fun button(control: TerminalControl, width: Float, height: Float, plugin: CustomUIPanelPlugin): ButtonAPI {
            var enabled = true
            var checked = false
            val button = Mockito.mock(ButtonAPI::class.java)
            val buttonPosition = position(width, height)
            Mockito.`when`(button.position).thenReturn(buttonPosition)
            Mockito.`when`(button.isEnabled).thenAnswer { enabled }
            Mockito.`when`(button.isChecked).thenAnswer { checked }
            Mockito.doAnswer { enabled = it.getArgument(0); null }.`when`(button).setEnabled(anyBoolean())
            Mockito.doAnswer { checked = it.getArgument(0); null }.`when`(button).setChecked(anyBoolean())
            buttons[control] = button
            events[control] = { plugin.buttonPressed(control) }
            return button
        }
    }
}
