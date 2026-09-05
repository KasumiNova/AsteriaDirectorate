package cn.kasuminova.astd.campaign.automation

import cn.kasuminova.astd.campaign.world.StoryWorldIds
import cn.kasuminova.astd.campaign.world.StoryWorldSpecs
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CampaignWorldChecks] 纯逻辑部分的单元测试：
 * 验收依赖"与生产一致的种子公式重建随机规格"，此处钉死种子复刻公式与
 * 规格重建的确定性/数量区间，防止验收端漂移导致误报或漏报。
 */
internal class CampaignWorldChecksTest {

    @Test
    fun `replicated seeded random matches production seeding formula`() {
        val seedString = "test_sector_seed"
        val salt = StoryWorldIds.SYSTEM_MAIN

        val replicated = CampaignWorldChecks.replicateSeededRandom(seedString, salt)
        val reference = Random((seedString + ":" + salt).hashCode().toLong())

        // 完整序列前 16 个值逐一相等，钉死公式而非仅验证确定性。
        repeat(16) {
            assertEquals(reference.nextLong(), replicated.nextLong(), "replicated random diverged at step $it")
        }
    }

    @Test
    fun `replicated seeded random falls back to default seed when sector seed is missing`() {
        val replicated = CampaignWorldChecks.replicateSeededRandom(null, "salt")
        val reference = Random(("asteria_directorate:salt").hashCode().toLong())

        repeat(8) {
            assertEquals(reference.nextInt(), replicated.nextInt(), "fallback random diverged at step $it")
        }
    }

    @Test
    fun `spec rebuild with replicated seed is deterministic and inside documented random ranges`() {
        val seed = "astd_acceptance_seed"

        val mainA = StoryWorldSpecs.mainSystem(CampaignWorldChecks.replicateSeededRandom(seed, StoryWorldIds.SYSTEM_MAIN))
        val mainB = StoryWorldSpecs.mainSystem(CampaignWorldChecks.replicateSeededRandom(seed, StoryWorldIds.SYSTEM_MAIN))
        assertEquals(mainA.allEntityIds(), mainB.allEntityIds(), "main system spec rebuild must be deterministic")
        assertEquals(mainA.planets.map { it.typeId }, mainB.planets.map { it.typeId }, "random planet picks must be deterministic")

        // 主星系：3 固定行星 + 荒芜 ×2~4 + 气巨 ×1~2。
        val mainRandomBarren = mainA.planets.count { it.id.startsWith("astd_main_planet_barren_") }
        val mainRandomGas = mainA.planets.count { it.id.startsWith("astd_main_planet_gas_") }
        assertTrue(mainRandomBarren in 2..4, "main barren count $mainRandomBarren outside 2..4")
        assertTrue(mainRandomGas in 1..2, "main gas count $mainRandomGas outside 1..2")

        // 星坠：1 固定行星 + 荒芜 ×1~3 + 气巨 ×1~2。
        val starfall = StoryWorldSpecs.starfallSystem(CampaignWorldChecks.replicateSeededRandom(seed, StoryWorldIds.SYSTEM_STARFALL))
        val starfallBarren = starfall.planets.count { it.id.startsWith("astd_starfall_planet_barren_") }
        val starfallGas = starfall.planets.count { it.id.startsWith("astd_starfall_planet_gas_") }
        assertTrue(starfallBarren in 1..3, "starfall barren count $starfallBarren outside 1..3")
        assertTrue(starfallGas in 1..2, "starfall gas count $starfallGas outside 1..2")

        // 紫菀：荒芜 ×1~2 + 冰封 ×2~4 + 气巨 ×2~4，全部随机星球不得零状况。
        val aster = StoryWorldSpecs.asterSystem(CampaignWorldChecks.replicateSeededRandom(seed, StoryWorldIds.SYSTEM_ASTER))
        val asterBarren = aster.planets.count { it.id.startsWith("astd_aster_planet_barren_") }
        val asterFrozen = aster.planets.count { it.id.startsWith("astd_aster_planet_frozen_") }
        val asterGas = aster.planets.count { it.id.startsWith("astd_aster_planet_gas_") }
        assertTrue(asterBarren in 1..2, "aster barren count $asterBarren outside 1..2")
        assertTrue(asterFrozen in 2..4, "aster frozen count $asterFrozen outside 2..4")
        assertTrue(asterGas in 2..4, "aster gas count $asterGas outside 2..4")
        aster.planets.forEach { planet ->
            assertTrue(planet.conditionIds.isNotEmpty(), "random planet ${planet.id} must carry at least one condition")
        }

        // 规格内实体 ID 唯一（验收端唯一性核对的前置条件）。
        listOf(mainA, starfall, aster).forEach { spec ->
            val ids = spec.allEntityIds()
            assertEquals(ids.size, ids.toSet().size, "duplicate entity ids in spec ${spec.id}")
        }
    }

    @Test
    fun `approxEquals honors epsilon boundaries`() {
        assertTrue(CampaignWorldChecks.approxEquals(1f, 1f + 5e-4f, 1e-3f))
        assertTrue(CampaignWorldChecks.approxEquals(1f, 1f - 1e-3f, 1e-3f))
        assertFalse(CampaignWorldChecks.approxEquals(1f, 1f + 2e-3f, 1e-3f))
        assertFalse(CampaignWorldChecks.approxEquals(Float.NaN, 1f, 1e-3f))
    }
}
