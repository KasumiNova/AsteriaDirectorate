package cn.kasuminova.astd.campaign.ending

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「执行官」核心保管纯规则：person/commodity 形态互斥、commodity 总量封顶 1 枚、
 * 回收量仓储优先分配。
 */
class ExecutorCustodyRulesTest {

    @Test
    fun `无复制体不回收`() {
        // 正常流程：person 在编（assigned）时 commodity 不存在；卸载后货舱恰有 1 枚
        assertEquals(0f to 0f, ExecutorCustodyRules.reclaimAllocation(0f, 0f, assigned = true))
        assertEquals(0f to 0f, ExecutorCustodyRules.reclaimAllocation(0f, 1f, assigned = false))
        assertEquals(0f to 0f, ExecutorCustodyRules.reclaimAllocation(1f, 0f, assigned = false))
    }

    @Test
    fun `person在编时commodity清零`() {
        // 军官/管理官在用：commodity 形态全量回收
        assertEquals(1f to 0f, ExecutorCustodyRules.reclaimAllocation(1f, 0f, assigned = true))
        assertEquals(0f to 1f, ExecutorCustodyRules.reclaimAllocation(0f, 1f, assigned = true))
        assertEquals(2f to 1f, ExecutorCustodyRules.reclaimAllocation(2f, 1f, assigned = true))
    }

    @Test
    fun `复制体回收仓储优先且封顶一枚`() {
        // 仓储 2 + 货舱 1，无 person 在编：允许 1 枚（留货舱），仓储全收
        assertEquals(2f to 0f, ExecutorCustodyRules.reclaimAllocation(2f, 1f, assigned = false))
        // 仓储 1 + 货舱 3：留 1 枚（仓储先扣 1，货舱再扣 1，货舱剩 2... 即扣仓储 1 + 货舱 2）
        assertEquals(1f to 2f, ExecutorCustodyRules.reclaimAllocation(1f, 3f, assigned = false))
    }

    @Test
    fun `负值输入按零处理`() {
        assertEquals(0f to 0f, ExecutorCustodyRules.reclaimAllocation(-1f, -1f, assigned = true))
        assertEquals(0f to 0f, ExecutorCustodyRules.reclaimAllocation(0f, 0f, assigned = false))
    }
}
