package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.campaign.world.StoryWorldLocations.WorldPoint
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 剧情星系落位逻辑校验：确定性、间距约束、遗址星系的距离/边缘/角距约束。
 */
internal class StoryWorldLocationsTest {

    @Test
    fun `world point distance`() {
        assertEquals(5f, WorldPoint(0f, 0f).distanceTo(WorldPoint(3f, 4f)), 0.001f)
        assertEquals(10f, WorldPoint(-6f, 8f).length(), 0.001f)
    }

    @Test
    fun `main system location is deterministic and inside the ring`() {
        val occupied = listOf(WorldPoint(1000f, 1000f), WorldPoint(-2000f, 3000f))
        val a = StoryWorldLocations.pickMainSystemLocation(Random(11L), occupied)
        val b = StoryWorldLocations.pickMainSystemLocation(Random(11L), occupied)
        assertEquals(a, b, "同种子落位必须确定")

        val radius = a.length()
        assertTrue(
            radius in StoryWorldLocations.MAIN_MIN_RADIUS..StoryWorldLocations.MAIN_MAX_RADIUS,
            "主星系必须落在外环环带: $radius",
        )
        for (point in occupied) {
            assertTrue(
                a.distanceTo(point) >= StoryWorldLocations.MIN_SEPARATION,
                "主星系必须与已有星系保持间距",
            )
        }
    }

    @Test
    fun `main system location keeps separation from crowded sector`() {
        // 构造一批占用点，验证多轮尝试仍能满足最小间距。
        val occupied = (0..200).map {
            val angle = it * 0.31f
            val radius = 15000f + (it % 50) * 300f
            WorldPoint(kotlin.math.cos(angle) * radius, kotlin.math.sin(angle) * radius)
        }
        repeat(20) { seed ->
            val loc = StoryWorldLocations.pickMainSystemLocation(Random(seed.toLong()), occupied)
            assertTrue(loc.length() >= StoryWorldLocations.MAIN_MIN_RADIUS - 0.001f)
            assertTrue(loc.length() <= StoryWorldLocations.MAIN_MAX_RADIUS + 0.001f)
        }
    }

    @Test
    fun `ruin system locations satisfy distance and fringe constraints`() {
        val main = WorldPoint(20000f, 5000f)
        val occupied = listOf(main, WorldPoint(0f, 0f))

        val starfall = StoryWorldLocations.pickRuinSystemLocation(Random(21L), main, 0, occupied)
        val aster = StoryWorldLocations.pickRuinSystemLocation(Random(22L), main, 1, occupied)

        val distStarfall = main.distanceTo(starfall)
        val distAster = main.distanceTo(aster)
        val tolerance = StoryWorldLocations.RUIN_DISTANCE_JITTER + 0.001f
        assertTrue(
            distStarfall in (StoryWorldLocations.RUIN_DISTANCE - tolerance)..(StoryWorldLocations.RUIN_DISTANCE + tolerance),
            "星坠遗址距主星系约 60000su: $distStarfall",
        )
        assertTrue(
            distAster in (StoryWorldLocations.RUIN_DISTANCE - tolerance)..(StoryWorldLocations.RUIN_DISTANCE + tolerance),
            "紫菀遗址距主星系约 60000su: $distAster",
        )
        assertTrue(starfall.length() >= StoryWorldLocations.FRINGE_MIN_RADIUS, "遗址必须位于边缘星区")
        assertTrue(aster.length() >= StoryWorldLocations.FRINGE_MIN_RADIUS, "遗址必须位于边缘星区")
        assertTrue(
            StoryWorldLocations.ruinPlacementValid(main, starfall, aster),
            "两个遗址星系须满足最小角距",
        )
    }

    @Test
    fun `ruin locations are deterministic`() {
        val main = WorldPoint(-15000f, 18000f)
        val a = StoryWorldLocations.pickRuinSystemLocation(Random(31L), main, 0, emptyList())
        val b = StoryWorldLocations.pickRuinSystemLocation(Random(31L), main, 0, emptyList())
        assertEquals(a, b)
    }

    @Test
    fun `ruin location handles main system near sector center`() {
        // 主星系压在星区中心附近时退化为随机方向，仍需满足边缘约束。
        val main = WorldPoint(0f, 0f)
        val ruin = StoryWorldLocations.pickRuinSystemLocation(Random(41L), main, 0, emptyList())
        assertTrue(ruin.length() >= StoryWorldLocations.FRINGE_MIN_RADIUS)
        assertTrue(
            main.distanceTo(ruin) >= StoryWorldLocations.RUIN_DISTANCE - StoryWorldLocations.RUIN_DISTANCE_JITTER - 0.001f,
        )
    }
}
