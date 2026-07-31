package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.ShipAPI
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 规格 07 §4.1 用例 2/3：玩家固定 v2（与难度档无关、恒单段终结）与
 * 多段终结解锁口径（破晓敌版限定：owner != 0 且 fixedScale >= 5）。
 * 经 [DifficultyTuningImpl.installScaleForTests] 走完整映射链路（对齐 PositronShockwaveDifficultyTest 先例）。
 */
class SevenStarsDifficultyTest {

    @AfterTest
    fun tearDown() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun stubShip(owner: Int): ShipAPI {
        val ship = mock(ShipAPI::class.java)
        `when`(ship.owner).thenReturn(owner)
        return ship
    }

    @Test
    fun `用例2 玩家来源固定 v2，与 fixedScale 无关，恒单段终结`() {
        for (scale in listOf(1f, 2f, 5f)) {
            DifficultyTuningImpl.installScaleForTests(scale)
            val t = SevenStarsDifficulty.snapshot(stubShip(owner = 0))
            assertEquals(SevenStarsDifficulty.FIRST_HIT_MULT.v2, t.firstHitMult, 1e-6f, "k_s=$scale 玩家首发倍率恒 v2")
            assertEquals(SevenStarsDifficulty.PER_JUMP_BONUS.v2, t.perJumpBonus, 1e-6f, "k_s=$scale 玩家每跳提升恒 v2")
            assertEquals(SevenStarsDifficulty.BONUS_CAP.v2, t.bonusCap, 1e-6f, "k_s=$scale 玩家累计上限恒 v2")
            assertFalse(t.multiSegmentTerminal, "k_s=$scale 玩家恒单段终结（破晓多段为敌版限定）")
        }
    }

    @Test
    fun `用例3 多段终结解锁口径：敌方 fixedScale 达到 5 才解锁`() {
        DifficultyTuningImpl.installScaleForTests(5f)
        assertTrue(
            SevenStarsDifficulty.snapshot(stubShip(owner = 1)).multiSegmentTerminal,
            "敌方 k_s=5（破晓）解锁多段终结",
        )
        for (scale in listOf(1f, 2f, 3f, 4.99f)) {
            DifficultyTuningImpl.installScaleForTests(scale)
            assertFalse(
                SevenStarsDifficulty.snapshot(stubShip(owner = 1)).multiSegmentTerminal,
                "敌方 k_s=$scale（远征及以下）不解锁多段终结",
            )
        }
    }
}
