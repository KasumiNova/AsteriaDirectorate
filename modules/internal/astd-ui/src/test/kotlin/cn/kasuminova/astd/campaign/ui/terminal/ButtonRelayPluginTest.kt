package cn.kasuminova.astd.campaign.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 按钮事件转发链路回归：原版 `CustomPanelImpl` 不冒泡按钮事件，承载按钮的面板插件
 * 必须把 buttonPressed 转发给终端代理，否则 tab 切换/交付核销/关闭终端全部无反应。
 */
class ButtonRelayPluginTest {

    @Test
    fun `relay forwards button id to delegate route`() {
        val received = mutableListOf<Any?>()
        val relay = ButtonRelayPlugin { received += it }

        relay.buttonPressed("tab.orders")
        relay.buttonPressed(Any())

        assertEquals(2, received.size)
        assertEquals("tab.orders", received[0])
    }

    @Test
    fun `receipt panel forwards close button press`() {
        val received = mutableListOf<Any?>()
        val panel = ReceiptPanelPlugin(620f, 300f) { received += it }

        panel.buttonPressed("receipt.close")

        assertEquals(listOf<Any?>("receipt.close"), received)
    }
}
