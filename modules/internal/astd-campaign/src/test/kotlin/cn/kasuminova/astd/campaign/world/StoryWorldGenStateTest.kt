package cn.kasuminova.astd.campaign.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 生成状态机的纯逻辑校验：幂等标志位、第二章解锁门槛、IndEvo 分支判定。
 */
internal class StoryWorldGenStateTest {

    @Test
    fun `fresh state always has pending work`() {
        assertTrue(StoryWorldGenState().pendingWork(indEvoEnabled = false))
        assertTrue(StoryWorldGenState().pendingWork(indEvoEnabled = true))
    }

    @Test
    fun `main system done settles pending work when chapter two locked and IndEvo absent`() {
        val state = StoryWorldGenState()
        state.mainSystemGenerated = true
        assertFalse(state.pendingWork(indEvoEnabled = false))
        // IndEvo 在场时仍需附加扩展
        assertTrue(state.pendingWork(indEvoEnabled = true))
    }

    @Test
    fun `chapter two unlock requires both ruin systems`() {
        val state = StoryWorldGenState()
        state.mainSystemGenerated = true
        state.chapterTwoUnlocked = true
        assertTrue(state.pendingWork(indEvoEnabled = false))

        state.starfallSystemGenerated = true
        assertTrue(state.pendingWork(indEvoEnabled = false))

        state.asterSystemGenerated = true
        assertFalse(state.pendingWork(indEvoEnabled = false))
    }

    @Test
    fun `IndEvo extras tracked independently per system`() {
        val state = StoryWorldGenState()
        state.mainSystemGenerated = true
        state.chapterTwoUnlocked = true
        state.starfallSystemGenerated = true
        state.asterSystemGenerated = true

        assertTrue(state.pendingWork(indEvoEnabled = true))

        state.indEvoMainExtrasApplied = true
        assertTrue(state.pendingWork(indEvoEnabled = true))

        state.indEvoStarfallExtrasApplied = true
        assertFalse(state.pendingWork(indEvoEnabled = true))

        // 未安装 IndEvo 时扩展标志不影响完成判定
        val noIndEvo = StoryWorldGenState()
        noIndEvo.mainSystemGenerated = true
        noIndEvo.chapterTwoUnlocked = true
        noIndEvo.starfallSystemGenerated = true
        noIndEvo.asterSystemGenerated = true
        assertFalse(noIndEvo.pendingWork(indEvoEnabled = false))
    }

    @Test
    fun `IndEvo railgun type constant matches IndEvo artillery contract`() {
        // IndEvo ArtilleryStationScript 的类型取值集合为 mortar/railgun/missile。
        assertEquals("railgun", StoryWorldIds.INDEVO_ARTILLERY_TYPE_RAILGUN)
    }

    @Test
    fun `canonical id helper produces stable market ids`() {
        assertEquals("astd_market_astd_main_station_branch", StoryWorldIds.marketIdFor(StoryWorldIds.MAIN_STATION_BRANCH))
    }
}
