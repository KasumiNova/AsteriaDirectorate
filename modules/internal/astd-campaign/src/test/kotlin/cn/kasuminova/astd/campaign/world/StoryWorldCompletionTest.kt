package cn.kasuminova.astd.campaign.world

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 剧情星系「完整性判定」纯逻辑校验（问题 1 修复配套测试）。
 *
 * 直接调用生产状态机同源实现 [StorySystemSpecs.SystemSpec.isComplete]：
 * - 完整规格实体全部落地 → 完整；
 * - 恒星缺失 / 中途缺固定行星 / 缺自定义实体 / 星系整体不存在 → 均不完整
 *   （createSystem 半途失败留下的残缺星系会被识别并触发回滚重建，而非被「恒星已存在」误判为成功）。
 */
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
        // 恒星缺失（createSystem 中断：initStar 前失败的残留）
        assertFalse(starfall.isComplete(ids - StoryWorldIds.STARFALL_STAR))
        // 中途缺一颗固定行星（createSystem 抛异常前的残留）
        assertFalse(starfall.isComplete(ids - StoryWorldIds.STARFALL_PLANET_DUANYUAN))
        // 缺一个自定义实体
        assertFalse(starfall.isComplete(ids - StoryWorldIds.STARFALL_STATION_MAIN))
        // 星系整体不存在
        assertFalse(starfall.isComplete(emptySet()))
    }

    @Test
    fun `残缺判定可发现紫菀半途残留`() {
        // 紫菀生成到一半（黑洞恒星 + 部分行星）就抛异常：实体集合含恒星但缺核心实体。
        val partial = aster.allEntityIds().toSet() - StoryWorldIds.ASTER_CORE_VAULT - StoryWorldIds.ASTER_STATION_MAIN
        assertTrue(StoryWorldIds.ASTER_STAR in partial, "恒星已生成")
        assertFalse(aster.isComplete(partial), "恒星存在但实体残缺时必须判定不完整，否则旧版幂等会误判成功")
    }
}
