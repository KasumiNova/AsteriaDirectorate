package cn.kasuminova.astd.impl.difficulty

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 轨一读取面全链路：注入系数 → [DifficultyTuningImpl.value] → 三锚点映射；
 * 档位解析（[DifficultySettingsKeys.resolveTier]）的命中/自定义/回退路径。
 */
class DifficultyTuningImplTest {

    private val entry = ScalingEntry(v1 = 6f, v2 = 13f, v5 = 18f)

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    @Test
    fun `注入系数后 value 按分段线性映射`() {
        DifficultyTuningImpl.installScaleForTests(1f)
        assertEquals(6f, DifficultyTuningImpl.value(entry), 1e-6f)
        DifficultyTuningImpl.installScaleForTests(2f)
        assertEquals(13f, DifficultyTuningImpl.value(entry), 1e-6f)
        DifficultyTuningImpl.installScaleForTests(5f)
        assertEquals(18f, DifficultyTuningImpl.value(entry), 1e-6f)
        DifficultyTuningImpl.installScaleForTests(3f)
        assertEquals(13f + 5f / 3f, DifficultyTuningImpl.value(entry), 1e-5f)
    }

    @Test
    fun `清除注入后回退默认档 2_0`() {
        DifficultyTuningImpl.installScaleForTests(5f)
        DifficultyTuningImpl.installScaleForTests(null)
        assertEquals(2f, DifficultyTuningImpl.fixedScale, 1e-6f)
        assertEquals(13f, DifficultyTuningImpl.value(entry), 1e-6f)
    }

    @Test
    fun `档位解析命中预设档`() {
        // 测试环境 i18n 回退为 "category:key" 回声串，与注册侧同源，精确匹配仍然成立
        val names = DifficultySettingsKeys.tierDisplayNames()
        val resolved = DifficultySettingsKeys.resolveTier(names[0], 2f)
        assertTrue(resolved.matched)
        assertEquals(1.0f, resolved.scale, 1e-6f)
        val resolvedDawn = DifficultySettingsKeys.resolveTier(names[3], 2f)
        assertEquals(5.0f, resolvedDawn.scale, 1e-6f)
    }

    @Test
    fun `档位解析自定义档取滑条值并封顶`() {
        val custom = DifficultySettingsKeys.customDisplayName()
        val resolved = DifficultySettingsKeys.resolveTier(custom, 3.7f)
        assertTrue(resolved.matched)
        assertEquals(3.7f, resolved.scale, 1e-6f)
        val overCap = DifficultySettingsKeys.resolveTier(custom, 9.9f)
        assertEquals(5.0f, overCap.scale, 1e-6f)
    }

    @Test
    fun `未命中显示名回退默认档并标记未命中`() {
        val resolved = DifficultySettingsKeys.resolveTier("不存在的档位", 2f)
        assertFalse(resolved.matched)
        assertEquals(DifficultySettingsKeys.DEFAULT_SCALE, resolved.scale, 1e-6f)
    }
}
