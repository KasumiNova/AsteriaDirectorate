package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.BountyCampaignManager
import cn.kasuminova.astd.campaign.bounty.BountyState
import com.fs.starfarer.api.impl.campaign.ids.Factions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 归档三选势力变强计划验证（[ArchiveBoost]）：幅度/对象/节奏与 13 文档口径一致，
 * 立即档与延迟档不重叠、按激活标记挂载不叠乘。
 */
class ArchiveBoostPlanTest {

    private val majors = BountyCampaignManager.TRADEABLE_FACTIONS

    @Test
    fun `公开：全体主要势力立即 +25 且无延迟档`() {
        val plan = ArchiveBoost.computePlan(BountyCampaignManager.ARCHIVE_PUBLIC, null)!!
        assertEquals(majors.toSet(), plan.immediate.keys)
        assertTrue(plan.immediate.values.all { it == ArchiveBoost.PUBLIC_BONUS })
        assertTrue(plan.delayed.isEmpty())
        assertEquals(0, plan.delayedCycles)
    }

    @Test
    fun `封存：全体主要势力延迟 2 周期 +12 且无立即档`() {
        val plan = ArchiveBoost.computePlan(BountyCampaignManager.ARCHIVE_SEALED, null)!!
        assertTrue(plan.immediate.isEmpty())
        assertEquals(majors.toSet(), plan.delayed.keys)
        assertTrue(plan.delayed.values.all { it == ArchiveBoost.SEALED_BONUS })
        assertEquals(ArchiveBoost.SEALED_DELAY_CYCLES, plan.delayedCycles)
    }

    @Test
    fun `交易：对象立即 +50 其余延迟 1 周期 +10 且两档不重叠`() {
        val target = Factions.TRITACHYON
        val plan = ArchiveBoost.computePlan(BountyCampaignManager.ARCHIVE_TRADED, target)!!
        assertEquals(mapOf(target to ArchiveBoost.TRADED_TARGET_BONUS), plan.immediate)
        assertEquals(majors.toSet() - target, plan.delayed.keys)
        assertTrue(plan.delayed.values.all { it == ArchiveBoost.TRADED_OTHERS_BONUS })
        assertEquals(ArchiveBoost.TRADED_DELAY_CYCLES, plan.delayedCycles)
        assertTrue(plan.immediate.keys.intersect(plan.delayed.keys).isEmpty())
    }

    @Test
    fun `交易：非法对象或未知选择返回 null`() {
        assertNull(ArchiveBoost.computePlan(BountyCampaignManager.ARCHIVE_TRADED, null))
        assertNull(ArchiveBoost.computePlan(BountyCampaignManager.ARCHIVE_TRADED, Factions.REMNANTS))
        assertNull(ArchiveBoost.computePlan("not_a_choice", null))
    }

    @Test
    fun `激活集合受签署与生效标记门控`() {
        val state = BountyState()
        val ending = EndingState()
        state.archiveChoice = BountyCampaignManager.ARCHIVE_TRADED
        state.archiveTradeFactionId = Factions.HEGEMONY

        // 未签发合同：无增幅
        assertTrue(ArchiveBoost.activeBoosts(state, ending).isEmpty())

        // 签署后仅立即档激活
        state.infiniteContractor = true
        ending.archiveImmediateApplied = true
        var active = ArchiveBoost.activeBoosts(state, ending)
        assertEquals(mapOf(Factions.HEGEMONY to ArchiveBoost.TRADED_TARGET_BONUS), active)

        // 延迟档到期后合并，目标势力不被延迟档重复覆盖（不叠乘）
        ending.archiveDelayedApplied = true
        active = ArchiveBoost.activeBoosts(state, ending)
        assertEquals(majors.size, active.size)
        assertEquals(ArchiveBoost.TRADED_TARGET_BONUS, active[Factions.HEGEMONY])
        assertEquals(ArchiveBoost.TRADED_OTHERS_BONUS, active[Factions.LUDDIC_CHURCH])
    }
}
