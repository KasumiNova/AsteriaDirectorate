package cn.kasuminova.astd.campaign.bounty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * BountyState 旧存档迁移：XStream 反序列化不调用构造函数，旧档取出的实例可能缺失
 * 后加的集合/Map 字段（运行时为 null）；normalize() 必须将其重建为空实例。
 */
class BountyStateTest {

    @Test
    fun `字段缺失的旧实例经 normalize 重建全部集合字段`() {
        val legacy = BountyStateTestSupport.legacyInstanceWithNullCollections()
        legacy.normalize()

        assertNotNull(legacy.patchedBountyKeys)
        assertNotNull(legacy.concludedBountyKeys)
        assertNotNull(legacy.clearedGroups)
        assertNotNull(legacy.postedWorkOrders)
        assertNotNull(legacy.destroyedWorkOrders)
        assertNotNull(legacy.settledWorkOrders)
        assertNotNull(legacy.workOrderStageIndex)
        assertNotNull(legacy.quotedRewards)
        assertNotNull(legacy.grantedGroupBonuses)
        assertNotNull(legacy.chapterHooks)
        assertNotNull(legacy.chapterClearingOrders)

        // 重建为空实例：可直接投入主线逻辑（挂出→击毁→核销全流程不抛 NPE）
        val key = MainBounties.KEY_PROLOGUE
        val d = assertNotNull(MainBounties.byKey(key))
        legacy.quotedRewards[MainlineProgression.quoteKey(key)] =
            MainlineProgression.quoteOrderReward(d, 1f, 1L)
        legacy.postedWorkOrders.add(key)
        MainlineProgression.onStageDestroyed(legacy, key)
        val result = MainlineProgression.settle(legacy, key, 1f)
        assertEquals(true, result.success)
    }

    @Test
    fun `normalize 不覆盖既有内容`() {
        val state = BountyState()
        state.settledWorkOrders.add(MainBounties.KEY_PROLOGUE)
        state.quotedRewards["k"] = 42
        state.normalize()
        assertEquals(setOf(MainBounties.KEY_PROLOGUE), state.settledWorkOrders)
        assertEquals(mapOf("k" to 42), state.quotedRewards)
    }
}
