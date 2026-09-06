package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.campaign.world.StoryPlacement.Vec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 剧情星系落位算法校验：距离/边缘半径/角距约束与种子确定性。
 */
class StoryPlacementTest {

    @Test
    fun `夹角计算正确`() {
        assertEquals(90f, StoryPlacement.angleBetweenDeg(Vec(1f, 0f), Vec(0f, 1f)), 1e-3f)
        assertEquals(180f, StoryPlacement.angleBetweenDeg(Vec(1f, 0f), Vec(-1f, 0f)), 1e-3f)
        assertEquals(0f, StoryPlacement.angleBetweenDeg(Vec(3f, 4f), Vec(6f, 8f)), 1e-3f)
    }

    @Test
    fun `第二章落位满足距离与角距约束`() {
        val mainLocs = listOf(
            Vec(0f, 0f),
            Vec(5000f, 0f),
            Vec(-8000f, 12000f),
            Vec(15000f, -15000f),
        )
        for (mainLoc in mainLocs) {
            for (seed in 0L..30L) {
                val (starfall, aster) = StoryPlacement.placeChapter2Systems(mainLoc, seed)
                assertTrue(
                    StoryPlacement.chapter2ConstraintsSatisfied(mainLoc, starfall, aster),
                    "落位违反约束：main=$mainLoc seed=$seed starfall=$starfall aster=$aster",
                )
            }
        }
    }

    @Test
    fun `同一种子落位确定`() {
        val mainLoc = Vec(6000f, -3000f)
        assertEquals(
            StoryPlacement.placeChapter2Systems(mainLoc, 7L),
            StoryPlacement.placeChapter2Systems(mainLoc, 7L),
        )
    }

    @Test
    fun `主星系落位避开既有星系`() {
        val existing = listOf(Vec(0f, 0f), Vec(10000f, 10000f), Vec(-10000f, -5000f))
        for (seed in 0L..20L) {
            val loc = StoryPlacement.placeMainSystem(seed, existing)
            assertTrue(loc.length() <= StoryPlacement.MAIN_MAX_RADIUS)
            // 多数种子下应直接满足间距约束（极端拥挤时允许兜底，此处构造不拥挤）
            assertTrue(
                existing.all { it.minus(loc).length() >= StoryPlacement.MAIN_MIN_CLEARANCE },
                "主星系落位与既有星系过近：seed=$seed loc=$loc",
            )
        }
    }
}
