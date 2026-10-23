package cn.kasuminova.astd.combat.effect.lens.stellar

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StellarMrmDifficultyTest {

    @AfterTest
    fun tearDown() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    @Test
    fun `用例12 玩家固定v2，敌版三档随k_s映射`() {
        val entry = StellarMrmDifficulty.WEAPON_EMP
        DifficultyTuningImpl.installScaleForTests(1f)
        assertEquals(entry.v2, StellarMrmDifficulty.resolve(entry, sourceOwner = 0), "玩家 owner=0 即使 k_s=1 也恒 v2")
        DifficultyTuningImpl.installScaleForTests(5f)
        assertEquals(entry.v2, StellarMrmDifficulty.resolve(entry, sourceOwner = 0), "玩家 owner=0 即使 k_s=5 也恒 v2")

        DifficultyTuningImpl.installScaleForTests(1f)
        assertEquals(entry.v1, StellarMrmDifficulty.resolve(entry, sourceOwner = 1), "敌版 k_s=1 → v1")
        DifficultyTuningImpl.installScaleForTests(2f)
        assertEquals(entry.v2, StellarMrmDifficulty.resolve(entry, sourceOwner = 1), "敌版 k_s=2 → v2")
        DifficultyTuningImpl.installScaleForTests(5f)
        assertEquals(entry.v5, StellarMrmDifficulty.resolve(entry, sourceOwner = 1), "敌版 k_s=5 → v5")
    }
}
