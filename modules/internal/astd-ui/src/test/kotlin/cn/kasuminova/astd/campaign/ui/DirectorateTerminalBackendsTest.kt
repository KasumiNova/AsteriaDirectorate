package cn.kasuminova.astd.campaign.ui

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 数据源装配点测试：未注入时必须报错拒绝（不存在默认兜底实现），注入后原样返回。
 */
class DirectorateTerminalBackendsTest {

    /** 接口契约的最小真实实现（纯内存应答，不涉及任何持久化占位 map）。 */
    private class FakeSource : DirectorateTerminalDataSource {
        override fun snapshot(): TerminalSnapshot =
            TerminalSnapshot(contractorId = "CT-1", contractorLevel = 1, registerCycle = "c+206")

        override fun acceptWorkOrder(orderId: String): Boolean = false
        override fun trackWorkOrder(orderId: String): Boolean = false
        override fun requestSettlement(orderId: String): Boolean = false
        override fun chooseEnding(endingId: String): Boolean = false
    }

    @Test
    fun `get without install refuses with error instead of fake fallback`() {
        assertFalse(DirectorateTerminalBackends.isInstalled)
        val ex = assertFailsWith<IllegalStateException> { DirectorateTerminalBackends.get() }
        assertTrue(ex.message?.contains("未注入") == true)

        val source = FakeSource()
        DirectorateTerminalBackends.install(source)
        assertTrue(DirectorateTerminalBackends.isInstalled)
        assertSame(source, DirectorateTerminalBackends.get())
    }
}
