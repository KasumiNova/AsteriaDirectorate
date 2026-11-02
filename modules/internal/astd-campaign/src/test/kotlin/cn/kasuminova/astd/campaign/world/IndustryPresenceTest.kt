package cn.kasuminova.astd.campaign.world

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndustryPresenceTest {

    private val chain = mapOf("spaceport" to "megaport", "megaport" to null, "population" to null)

    private fun reaches(present: Set<String>, id: String): Boolean =
        StoryWorldGenerator.reachesIndustryOrUpgrade(present, id) { chain[it] }

    @Test
    fun `产业本身存在时判定存在`() {
        assertTrue(reaches(setOf("spaceport", "population"), "spaceport"))
    }

    @Test
    fun `已升级到下游产业时基础产业不再重复补齐`() {
        assertTrue(reaches(setOf("megaport", "population"), "spaceport"))
    }

    @Test
    fun `升级链未命中时判定缺失`() {
        assertFalse(reaches(setOf("population"), "spaceport"))
    }

    @Test
    fun `升级链末端产业自身存在时判定存在`() {
        assertTrue(reaches(setOf("megaport"), "megaport"))
    }

    @Test
    fun `无升级关系的产业只看自身`() {
        assertFalse(reaches(setOf("megaport"), "population"))
    }
}
