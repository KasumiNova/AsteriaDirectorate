package cn.kasuminova.astd.impl.combat

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「双模式切换器自动模式免自动化点数」开关读取面：默认值、测试注入/清除、
 * [DualModeSettingsImpl.applyResolvedExempt] 的变更钩子触发路径。
 */
class DualModeSettingsImplTest {

    @AfterTest
    fun clearOverride() {
        DualModeSettingsImpl.installExemptForTests(null)
        DualModeSettingsImpl.exemptChangedHook = null
    }

    @Test
    fun `默认开启`() {
        assertTrue(DualModeSettingsImpl.automatedModeExemptFromAutoPoints)
    }

    @Test
    fun `注入关闭后读取为关闭 清除后回到默认开启`() {
        DualModeSettingsImpl.installExemptForTests(false)
        assertFalse(DualModeSettingsImpl.automatedModeExemptFromAutoPoints)
        DualModeSettingsImpl.installExemptForTests(null)
        assertTrue(DualModeSettingsImpl.automatedModeExemptFromAutoPoints)
    }

    @Test
    fun `applyResolvedExempt 变更时触发钩子 同值不触发`() {
        val calls = mutableListOf<Boolean>()
        DualModeSettingsImpl.exemptChangedHook = { calls.add(it) }
        try {
            // 默认开启 → 解析出关闭：变更，触发一次
            DualModeSettingsImpl.applyResolvedExempt(false)
            // 同值再解析：不触发
            DualModeSettingsImpl.applyResolvedExempt(false)
            // 解析回开启：变更，再触发一次
            DualModeSettingsImpl.applyResolvedExempt(true)
        } finally {
            // applyResolvedExempt 改的是 settings 层持久值，恢复默认避免污染同进程其他测试
            DualModeSettingsImpl.applyResolvedExempt(DualModeSettingsImpl.DEFAULT_FREE_AUTO_POINTS)
        }
        assertEquals(listOf(false, true), calls)
    }

    @Test
    fun `钩子未安装时 applyResolvedExempt 仅打日志不抛异常`() {
        assertNull(DualModeSettingsImpl.exemptChangedHook)
        try {
            DualModeSettingsImpl.applyResolvedExempt(false)
            assertFalse(DualModeSettingsImpl.automatedModeExemptFromAutoPoints)
        } finally {
            DualModeSettingsImpl.applyResolvedExempt(DualModeSettingsImpl.DEFAULT_FREE_AUTO_POINTS)
        }
        assertTrue(DualModeSettingsImpl.automatedModeExemptFromAutoPoints)
    }
}
