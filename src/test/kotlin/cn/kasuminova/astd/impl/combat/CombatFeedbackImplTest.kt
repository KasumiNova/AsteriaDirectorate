package cn.kasuminova.astd.impl.combat

import cn.kasuminova.astd.impl.buff.WarnCapture
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.awt.Color
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 规格 §4.2：反馈通道直通与 0 值/空串防线。
 */
class CombatFeedbackImplTest {
    private val captures = mutableListOf<WarnCapture>()

    @AfterTest
    fun tearDown() {
        captures.forEach { it.detach() }
        captures.clear()
    }

    private val point = Vector2f(100f, 200f)
    private val color = Color(255, 200, 120)

    @Test
    fun `maintainPlayerStatus forwards to the engine verbatim`() {
        val engine = mock(CombatEngineAPI::class.java)

        CombatFeedbackImpl.maintainPlayerStatus(engine, "astd_test_key", "graphics/icons/icon.png", "标题", "描述", true)

        verify(engine).maintainStatusForPlayerShip("astd_test_key", "graphics/icons/icon.png", "标题", "描述", true)
    }

    @Test
    fun `floatingDamage forwards positive amounts`() {
        val engine = mock(CombatEngineAPI::class.java)
        val target = mock(CombatEntityAPI::class.java)
        val source = mock(CombatEntityAPI::class.java)

        CombatFeedbackImpl.floatingDamage(engine, point, 250f, color, target, source)

        verify(engine).addFloatingDamageText(point, 250f, color, target, source)
    }

    @Test
    fun `floatingDamage blocks non-positive and NaN amounts with warn`() {
        val capture = WarnCapture(CombatFeedbackImpl::class.java).also { captures += it }
        val engine = mock(CombatEngineAPI::class.java)
        val target = mock(CombatEntityAPI::class.java)

        CombatFeedbackImpl.floatingDamage(engine, point, 0f, color, target, null)
        CombatFeedbackImpl.floatingDamage(engine, point, -10f, color, target, null)
        CombatFeedbackImpl.floatingDamage(engine, point, Float.NaN, color, target, null)

        verify(engine, never()).addFloatingDamageText(
            org.mockito.ArgumentMatchers.any(Vector2f::class.java),
            org.mockito.ArgumentMatchers.anyFloat(),
            org.mockito.ArgumentMatchers.any(Color::class.java),
            org.mockito.ArgumentMatchers.any(CombatEntityAPI::class.java),
            org.mockito.ArgumentMatchers.nullable(CombatEntityAPI::class.java),
        )
        assertEquals(3, capture.messages().size, "三次非法 amount 各记一次 WARN: ${capture.messages()}")
    }

    @Test
    fun `floatingText forwards non-blank text`() {
        val engine = mock(CombatEngineAPI::class.java)
        val anchor = mock(CombatEntityAPI::class.java)

        CombatFeedbackImpl.floatingText(engine, point, "演算转移", 24f, color, anchor, 0.5f, 1.5f)

        verify(engine).addFloatingText(point, "演算转移", 24f, color, anchor, 0.5f, 1.5f)
    }

    @Test
    fun `floatingText blocks blank text with warn`() {
        val capture = WarnCapture(CombatFeedbackImpl::class.java).also { captures += it }
        val engine = mock(CombatEngineAPI::class.java)

        CombatFeedbackImpl.floatingText(engine, point, "   ", 24f, color, null, 0.5f, 1.5f)

        verify(engine, never()).addFloatingText(
            org.mockito.ArgumentMatchers.any(Vector2f::class.java),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyFloat(),
            org.mockito.ArgumentMatchers.any(Color::class.java),
            org.mockito.ArgumentMatchers.nullable(CombatEntityAPI::class.java),
            org.mockito.ArgumentMatchers.anyFloat(),
            org.mockito.ArgumentMatchers.anyFloat(),
        )
        assertTrue(capture.messages().any { it.contains("空白") }, "必须记 WARN: ${capture.messages()}")
    }
}
