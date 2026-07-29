package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.impl.buff.WarnCapture
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.ShipAPI
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 规格 07 §4.1 用例 1/4/5/6：flashMult 三锚点链、terminalDamageFractions 段表、
 * jumpRange 折跃范围（含 0 值 WARN 防线）、decideAfterFlash 决策矩阵——全部驱动
 * [SevenStarsChainMath] / [SevenStarsDifficulty.snapshot] 真实逻辑断言输出。
 */
class SevenStarsChainMathTest {
    private var warnCapture: WarnCapture? = null

    @AfterTest
    fun tearDown() {
        DifficultyTuningImpl.installScaleForTests(null)
        warnCapture?.detach()
        warnCapture = null
    }

    private fun enemyTuningAt(scale: Float): SevenStarsDifficulty.SevenStarsTuning {
        DifficultyTuningImpl.installScaleForTests(scale)
        val ship = mock(ShipAPI::class.java)
        `when`(ship.owner).thenReturn(1)
        return SevenStarsDifficulty.snapshot(ship)
    }

    @Test
    fun `用例1 flashMult 三锚点链，逐档逐跳断言`() {
        val v1 = enemyTuningAt(1f)
        assertEquals(1.00f, SevenStarsChainMath.flashMult(v1, 1), 1e-6f, "v1 首发倍率")
        assertEquals(1.00f * 1.5f, SevenStarsChainMath.flashMult(v1, 2), 1e-6f, "v1 第 2 跳 +50%")
        assertEquals(1.00f * 2.0f, SevenStarsChainMath.flashMult(v1, 3), 1e-6f, "v1 第 3 跳达 cap +100%")
        for (i in 4..7) {
            assertEquals(2.0f, SevenStarsChainMath.flashMult(v1, i), 1e-6f, "v1 第 $i 跳恒 cap")
        }

        val v2 = enemyTuningAt(2f)
        assertEquals(1.25f, SevenStarsChainMath.flashMult(v2, 1), 1e-6f, "v2 首发倍率")
        assertEquals(1.25f * 1.625f, SevenStarsChainMath.flashMult(v2, 2), 1e-6f, "v2 第 2 跳 +62.5%")
        assertEquals(1.25f * 2.25f, SevenStarsChainMath.flashMult(v2, 3), 1e-6f, "v2 第 3 跳 +125%")
        for (i in 4..7) {
            assertEquals(1.25f * 2.75f, SevenStarsChainMath.flashMult(v2, i), 1e-6f, "v2 第 $i 跳恒 cap +175%")
        }

        val v5 = enemyTuningAt(5f)
        assertEquals(2.0f, SevenStarsChainMath.flashMult(v5, 1), 1e-6f, "v5 首发倍率")
        assertEquals(2.0f * 2.0f, SevenStarsChainMath.flashMult(v5, 2), 1e-6f, "v5 第 2 跳 +100%")
        assertEquals(2.0f * 3.0f, SevenStarsChainMath.flashMult(v5, 3), 1e-6f, "v5 第 3 跳 +200%")
        assertEquals(2.0f * 4.0f, SevenStarsChainMath.flashMult(v5, 4), 1e-6f, "v5 第 4 跳 +300%")
        for (i in 5..7) {
            assertEquals(2.0f * 5.0f, SevenStarsChainMath.flashMult(v5, i), 1e-6f, "v5 第 $i 跳恒 cap +400%")
        }
    }

    @Test
    fun `用例4 terminalDamageFractions 段表`() {
        assertEquals(
            listOf(0.5f),
            SevenStarsChainMath.terminalDamageFractions(multi = false, jumps = 5),
            "单段（玩家恒此）恒 [0.5]",
        )
        assertEquals(listOf(0.5f), SevenStarsChainMath.terminalDamageFractions(true, 0), "jumps=0 保底 1 段 50%")
        assertEquals(listOf(0.5f), SevenStarsChainMath.terminalDamageFractions(true, 1), "jumps=1 单段 50%")
        assertEquals(
            listOf(0.5f, 0.75f, 1.0f),
            SevenStarsChainMath.terminalDamageFractions(true, 3),
            "jumps=3 段表",
        )
        assertEquals(
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f),
            SevenStarsChainMath.terminalDamageFractions(true, 7),
            "jumps=7 段表拉满 200%",
        )
        val overflow = SevenStarsChainMath.terminalDamageFractions(true, 8)
        assertEquals(8, overflow.size, "jumps=8 越界输入段数 = jumps，不静默截断")
        assertEquals(2.0f, overflow[7], 1e-6f, "第 8 段 clamp 在 200%")
    }

    @Test
    fun `用例5 jumpRange 折跃范围与 0 值 WARN 防线`() {
        assertEquals(400f, SevenStarsChainMath.jumpRange(800f), 1e-6f, "基准 800 → 400")
        assertEquals(800f, SevenStarsChainMath.jumpRange(1600f), 1e-6f, "吃射程修正后 1600 → 800")

        val capture = WarnCapture(SevenStarsChainMath::class.java)
        warnCapture = capture
        assertEquals(0f, SevenStarsChainMath.jumpRange(0f), "射程 0 → 折跃范围 0（候选恒空进终结）")
        assertEquals(1, capture.events.size, "射程 0 应输出恰好一条 WARN（不静默恒零）")
    }

    @Test
    fun `用例6 decideAfterFlash 决策矩阵`() {
        assertEquals(
            SevenStarsChainMath.ChainDecision.CONTINUE,
            SevenStarsChainMath.decideAfterFlash(kills = 1, jumps = 3, hasPdCandidates = true),
            "kills>=1 且 jumps<7 → CONTINUE",
        )
        assertEquals(
            SevenStarsChainMath.ChainDecision.TERMINAL,
            SevenStarsChainMath.decideAfterFlash(kills = 2, jumps = 7, hasPdCandidates = true),
            "kills>=1 且 jumps=7 → TERMINAL",
        )
        assertEquals(
            SevenStarsChainMath.ChainDecision.TERMINAL,
            SevenStarsChainMath.decideAfterFlash(kills = 1, jumps = 3, hasPdCandidates = false),
            "kills>=1 但无 PD 候选 → TERMINAL",
        )
        assertEquals(
            SevenStarsChainMath.ChainDecision.DISSIPATE,
            SevenStarsChainMath.decideAfterFlash(kills = 0, jumps = 3, hasPdCandidates = true),
            "kills=0 → DISSIPATE（未击杀断链不触发终结）",
        )
        assertEquals(
            SevenStarsChainMath.ChainDecision.DISSIPATE,
            SevenStarsChainMath.decideAfterFlash(kills = 0, jumps = 7, hasPdCandidates = true),
            "kills=0 优先于 7 跳上限（jumps 任意）",
        )
        assertEquals(
            SevenStarsChainMath.ChainDecision.DISSIPATE,
            SevenStarsChainMath.decideAfterFlash(kills = 0, jumps = 0, hasPdCandidates = false),
            "jumps=0/kills=0（首发空爆）→ DISSIPATE",
        )
    }
}
