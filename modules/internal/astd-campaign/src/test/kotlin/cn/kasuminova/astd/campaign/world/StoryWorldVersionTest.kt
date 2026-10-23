package cn.kasuminova.astd.campaign.world

import kotlin.test.Test
import kotlin.test.assertEquals

class StoryWorldVersionTest {

    @Test
    fun `版本键缺失判定为首次载入`() {
        assertEquals(
            WorldgenVersionCheck.FIRST_LOAD,
            StoryWorldState.compareWorldgenVersion(null, "1.2.3"),
        )
    }

    @Test
    fun `版本一致判定为常规读档`() {
        assertEquals(
            WorldgenVersionCheck.CURRENT,
            StoryWorldState.compareWorldgenVersion("1.2.3", "1.2.3"),
        )
    }

    @Test
    fun `版本不同判定为版本变更`() {
        assertEquals(
            WorldgenVersionCheck.VERSION_CHANGED,
            StoryWorldState.compareWorldgenVersion("1.2.2", "1.2.3"),
        )
        assertEquals(
            WorldgenVersionCheck.VERSION_CHANGED,
            StoryWorldState.compareWorldgenVersion("1.2.4", "1.2.3"),
        )
    }
}
