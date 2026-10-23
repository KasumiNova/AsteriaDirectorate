package cn.kasuminova.astd.campaign.world

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoryWorldCompletionTest {

    private val starfall = StorySystemSpecs.starfallSystemSpec(42L)
    private val aster = StorySystemSpecs.asterSystemSpec(42L)
    private val main = StorySystemSpecs.mainSystemSpec(42L)

    @Test
    fun `完整星系判定为完整`() {
        assertTrue(starfall.isComplete(starfall.allEntityIds()))
        assertTrue(aster.isComplete(aster.allEntityIds()))
        assertTrue(main.isComplete(main.allEntityIds()))
    }

    @Test
    fun `恒星缺失或中途缺实体均判定不完整`() {
        val ids = starfall.allEntityIds().toSet()
        assertFalse(starfall.isComplete(ids - StoryWorldIds.STARFALL_STAR))
        assertFalse(starfall.isComplete(ids - StoryWorldIds.STARFALL_PLANET_DUANYUAN))
        assertFalse(starfall.isComplete(ids - StoryWorldIds.STARFALL_STATION_MAIN))
        assertFalse(starfall.isComplete(emptySet()))
    }

    @Test
    fun `残缺判定可发现紫菀半途残留`() {
        val partial = aster.allEntityIds().toSet() - StoryWorldIds.ASTER_STATION_SHIGUANG - StoryWorldIds.ASTER_STATION_MAIN
        assertTrue(StoryWorldIds.ASTER_STAR in partial, "恒星已生成")
        assertFalse(aster.isComplete(partial), "恒星存在但实体残缺时必须判定不完整，否则旧版幂等会误判成功")
    }
}
