package cn.kasuminova.astd.campaign.bounty

import com.fs.starfarer.api.impl.campaign.ids.Factions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 赏金存档状态迁移与重复交付保护的可测试部分：
 * 无参构造默认值即旧档反序列化语义（新增字段默认值不得改变旧档行为）；
 * 核销/签署入口在没有待交付战果或未受理时的拒绝路径（真实调用，守卫不得抛异常）。
 */
class BountyStateMigrationTest {

    @Test
    fun `BountyState 无参构造即旧档反序列化默认语义`() {
        val state = BountyState()
        assertEquals(0, state.mainCompleted)
        assertEquals(0, state.contractorLevel, "旧档未注册承包商，等级应为 0")
        assertTrue(state.unlockedAffixIds.isEmpty())
        assertTrue(state.patchedBountyKeys.isEmpty())
        assertTrue(state.concludedBountyKeys.isEmpty())
        assertTrue(state.succeededBountyKeys.isEmpty())
        assertTrue(state.defeatedBountyKeys.isEmpty())
        assertTrue(state.quotedRewards.isEmpty())
        assertTrue(state.settlementRequests.isEmpty())
        assertTrue(state.clearedGroupIds.isEmpty())
        assertTrue(state.completedChapters.isEmpty())
        assertEquals(0f, state.liquidationProgress)
        assertFalse(state.archivalPending)
        assertEquals("", state.archiveChoice)
        assertNull(state.archiveTradeFactionId)
        assertFalse(state.infiniteContractor)
        assertEquals("", state.contractorId)
        assertEquals("", state.registerCycle)
        assertTrue(state.ledgerEntries.isEmpty())
        assertFalse(state.executiveCoreIssued)
    }

    @Test
    fun `履约流水账次构造完整填充且默认行为空行`() {
        val entry = BountyLedgerEntry("YJ-c206-1102／核销-17", "c206.04.12", 300_000L, "核销报酬")
        assertEquals("YJ-c206-1102／核销-17", entry.code)
        assertEquals("c206.04.12", entry.date)
        assertEquals(300_000L, entry.amount)
        assertEquals("核销报酬", entry.note)

        val empty = BountyLedgerEntry()
        assertEquals("", empty.code)
        assertEquals("", empty.date)
        assertEquals(0L, empty.amount)
        assertEquals("", empty.note)
    }

    @Test
    fun `重复交付保护：没有待交付战果的核销请求一律拒绝`() {
        // 无星区环境下 getOrCreate 回落全新状态：主线单/无限单/未知 key 均无待交付战果，必须拒绝而非抛异常。
        assertFalse(BountyCampaignManager.settleBounty("astd_main_c1_b1_a"), "未击破的主线单不得受理核销")
        assertFalse(BountyCampaignManager.settleBounty("astd_inf_s0_g1"), "未击破的无限单不得受理核销")
        assertFalse(BountyCampaignManager.settleBounty("not_a_bounty"), "未知 key 不得受理核销")
    }

    @Test
    fun `档案处置未受理时签署请求被拒绝`() {
        assertFalse(
            BountyCampaignManager.recordArchiveChoice(BountyCampaignManager.ARCHIVE_PUBLIC),
            "archivalPending=false 时不得受理签署",
        )
        assertFalse(
            BountyCampaignManager.recordArchiveChoice(BountyCampaignManager.ARCHIVE_TRADED, Factions.HEGEMONY),
            "archivalPending=false 时交易选同样不得受理",
        )
    }
}
